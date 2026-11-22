/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2026
 * Kosolapov Stanislav aka proDOOMman <prodoomman@gmail.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * Coverage41C is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * Coverage41C is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Coverage41C.
 */
package com.clouds42;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Extracts preview and module access for the SDK probe without executing user options. */
final class EdtProbeOptions {
    private static final String PREVIEW = "--enable-preview";
    private static final Set<String> WITH_OPERAND = Set.of(
            "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
            "--add-modules", "--limit-modules", "--add-reads", "--add-exports", "--add-opens",
            "--patch-module", "--enable-native-access", "--source");

    private enum Syntax { JVM_ENVIRONMENT, ARGUMENT_FILE }

    private EdtProbeOptions() { }

    static List<String> readFrom(Reader input) throws IOException {
        List<String> options = fromVmArguments(tokens(readOptionGroup(input), Syntax.JVM_ENVIRONMENT));
        List<String> launcher = tokens(readOptionGroup(input), Syntax.JVM_ENVIRONMENT);
        options.addAll(fromVmArguments(tokens(readOptionGroup(input), Syntax.JVM_ENVIRONMENT)));
        int scriptArgCount = Integer.parseInt(readOptionGroup(input));
        for (int i = 0; i < scriptArgCount; i++) {
            launcher.add(readOptionGroup(input));
        }
        addLauncherOptions(launcher, options);
        return options;
    }

    private static String readOptionGroup(Reader input) throws IOException {
        StringBuilder value = new StringBuilder();
        int c;
        while ((c = input.read()) != 0) {
            if (c == -1) {
                throw new IOException("Incomplete EDT launcher input");
            }
            value.append((char) c);
        }
        return value.toString();
    }

    private static void addLauncherOptions(List<String> launcher, List<String> options) throws IOException {
        boolean expand = true;
        String operandFor = null;
        for (String token : launcher) {
            List<String> args = List.of(token);
            if (expand && token.startsWith("@") && token.length() > 1) {
                if (token.startsWith("@@")) {
                    args = List.of(token.substring(1));
                } else {
                    Path file = Path.of(token.substring(1));
                    try {
                        // Keep arbitrary bytes in comments from blocking a valid option.
                        args = tokens(Files.readString(file, StandardCharsets.ISO_8859_1), Syntax.ARGUMENT_FILE);
                    } catch (IOException e) {
                        throw new IOException("Cannot read Java argument file " + file + ": " + e.getMessage(), e);
                    }
                }
            }
            for (String arg : args) {
                if (operandFor != null) {
                    addModuleAccess(operandFor, arg, options);
                    operandFor = null;
                } else if (arg.equals("-jar") || arg.equals("-m") || arg.equals("--module")
                        || arg.startsWith("--module=") || !arg.startsWith("-")) {
                    return;
                } else if (arg.equals("--disable-@files") || arg.equals("-disable-@files")) {
                    expand = false;
                } else if (WITH_OPERAND.contains(arg)) {
                    operandFor = arg;
                } else {
                    addVmOption(arg, options);
                }
            }
        }
    }

    // HotSpot's JAVA_TOOL_OPTIONS and _JAVA_OPTIONS only accept the joined --option=value form.
    static List<String> fromVmArguments(List<String> args) {
        List<String> options = new ArrayList<>();
        for (String arg : args) {
            addVmOption(arg, options);
        }
        return options;
    }

    private static void addVmOption(String arg, List<String> options) {
        if (arg.equals(PREVIEW)) {
            options.add(PREVIEW);
            return;
        }
        int equals = arg.indexOf('=');
        if (equals > 0) {
            addModuleAccess(arg.substring(0, equals), arg.substring(equals + 1), options);
        }
    }

    private static void addModuleAccess(String option, String value, List<String> options) {
        if (!option.equals("--add-opens") && !option.equals("--add-exports")) {
            return;
        }
        int slash = value.indexOf('/');
        int equals = value.indexOf('=', slash + 1);
        if (slash <= 0 || equals <= slash + 1) {
            return;
        }
        // Only real modules/packages can affect this JVM. Never put unrelated option values on probe argv.
        ModuleLayer boot = ModuleLayer.boot();
        Module source = boot.findModule(value.substring(0, slash)).orElse(null);
        String pkg = value.substring(slash + 1, equals);
        if (source == null || !source.getPackages().contains(pkg)) {
            return;
        }
        for (String target : value.substring(equals + 1).split(",")) {
            if (target.equals("ALL-UNNAMED") || boot.findModule(target).isPresent()) {
                options.add(option + "=" + source.getName() + "/" + pkg + "=" + target);
            }
        }
    }

    private static List<String> tokens(String text, Syntax syntax) {
        boolean argumentFile = syntax == Syntax.ARGUMENT_FILE;
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (argumentFile && quote == 0 && c == '#') {
                // The launcher drops an unfinished token when a comment begins.
                token.setLength(0);
                started = false;
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n' && text.charAt(i + 1) != '\r') {
                    i++;
                }
            } else if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) {
                quote = quote == 0 ? c : 0;
                started = true;
            } else if (argumentFile && quote != 0 && c == '\\' && i + 1 < text.length()) {
                c = text.charAt(++i);
                if (c == '\n' || c == '\r') {
                    while (i + 1 < text.length() && whitespace(text.charAt(i + 1), syntax)) {
                        i++;
                    }
                } else {
                    token.append(unescape(c));
                }
            } else if (whitespace(c, syntax) && (quote == 0 || (argumentFile && (c == '\r' || c == '\n')))) {
                if (started) {
                    result.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
                quote = 0;
            } else {
                token.append(c);
                started = true;
            }
        }
        if (started) {
            result.add(token.toString());
        }
        return result;
    }

    private static boolean whitespace(char c, Syntax syntax) {
        // OpenJDK also treats vertical tab as whitespace in environment variables.
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'
                || (syntax == Syntax.JVM_ENVIRONMENT && c == 0x0b);
    }

    private static char unescape(char c) {
        switch (c) {
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'f': return '\f';
            default: return c;
        }
    }
}
