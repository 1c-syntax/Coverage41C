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

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Infobase users whose debug targets are measured: includeUsers of --config files or --include-user of start.
// The command line replaces the files; files of a directory are merged in reading order.
public final class IncludeUsers {

    public static final IncludeUsers NONE = new IncludeUsers(Collections.emptySet(), "", Collections.emptyList());

    private final Set<String> users;
    private final String source;
    private final List<String> replacedSources;

    private IncludeUsers(Set<String> users, String source, List<String> replacedSources) {
        this.users = Collections.unmodifiableSet(users);
        this.source = source;
        this.replacedSources = Collections.unmodifiableList(replacedSources);
    }

    public static IncludeUsers load(File configPath, List<String> commandLine) throws IOException {
        return load(configPath, commandLine, ExclusionRules::read);
    }

    static IncludeUsers load(File configPath, List<String> commandLine, ExclusionRules.ConfigReader reader) throws IOException {
        List<String> fromCommandLine = parseCommandLine(commandLine);
        Set<String> users = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        if (configPath != null) {
            for (File file : ExclusionRules.configFiles(configPath)) {
                CoverageConfig config = reader.read(file);
                if (config.getIncludeUsers() == null) {
                    continue;
                }
                users.addAll(parse(file.getName(), config.getIncludeUsers()));
                sources.add(file.getName() + " → includeUsers");
                fileNames.add(file.getName());
            }
        }
        if (!fromCommandLine.isEmpty()) {
            return new IncludeUsers(new LinkedHashSet<>(fromCommandLine), "--include-user", fileNames);
        }
        if (users.isEmpty()) {
            return NONE;
        }
        return new IncludeUsers(users, String.join(", ", sources), Collections.emptyList());
    }

    // One file's value: a non-empty list of non-blank strings; duplicates inside the file are dropped.
    static List<String> parse(String fileName, JsonNode node) {
        String source = fileName + " → includeUsers";
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(source + ": expected a list of user names");
        }
        if (node.isEmpty()) {
            throw new IllegalArgumentException(source + ": empty list");
        }
        Set<String> users = new LinkedHashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode value = node.get(index);
            if (!value.isTextual()) {
                throw new IllegalArgumentException(source + "[" + index + "]: expected a string");
            }
            if (value.asText().isBlank()) {
                throw new IllegalArgumentException(source + "[" + index + "]: empty value");
            }
            users.add(value.asText());
        }
        return new ArrayList<>(users);
    }

    static List<String> parseCommandLine(List<String> values) {
        Set<String> users = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("--include-user: empty value");
            }
            users.add(value);
        }
        return new ArrayList<>(users);
    }

    public boolean isActive() {
        return !users.isEmpty();
    }

    // Exact match of the name dbgs reports: no masks, no case folding, no trimming.
    public boolean accepts(String userName) {
        return userName != null && !userName.isEmpty() && users.contains(userName);
    }

    public Set<String> getUsers() {
        return users;
    }

    public String getSource() {
        return source;
    }

    // Files whose includeUsers were ignored because --include-user was given.
    public List<String> getReplacedSources() {
        return replacedSources;
    }

    public String describe() {
        StringBuilder text = new StringBuilder("Include users: ").append(String.join(", ", users))
                .append(" (").append(source);
        if (!replacedSources.isEmpty()) {
            text.append("; includeUsers of ").append(String.join(", ", replacedSources)).append(" ignored");
        }
        return text.append(")").toString();
    }
}
