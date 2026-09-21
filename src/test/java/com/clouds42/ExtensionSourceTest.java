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

import com.clouds42.CommandLineOptions.ExtensionSource;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionSourceTest {

    @Test
    void parse() {
        ExtensionSource source = ExtensionSource.parse("Доработки=src/cfe/Доработки");
        assertEquals("Доработки", source.getName());
        assertEquals("src/cfe/Доработки", source.getPath());
    }

    @Test
    void parseSplitsOnFirstSeparator() {
        ExtensionSource source = ExtensionSource.parse("Доработки=C:/sources/a=b");
        assertEquals("Доработки", source.getName());
        assertEquals("C:/sources/a=b", source.getPath());
    }

    @Test
    void parseErrors() {
        assertThrows(IllegalArgumentException.class, () -> ExtensionSource.parse("Доработки"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionSource.parse("src/cfe/Доработки"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionSource.parse("=src/cfe/Доработки"));
        assertThrows(IllegalArgumentException.class, () -> ExtensionSource.parse("Доработки="));
        assertThrows(IllegalArgumentException.class, () -> ExtensionSource.parse("="));
        assertThrows(IllegalArgumentException.class, () -> ExtensionSource.parse(""));
    }

    @Test
    void converterReportsParameterError() {
        ExtensionSource.Converter converter = new ExtensionSource.Converter();
        assertEquals("Доработки", converter.convert("Доработки=src/cfe/Доработки").getName());
        assertThrows(CommandLine.TypeConversionException.class, () -> converter.convert("Доработки"));
    }

    @Test
    void wrongOptionValue() {
        String[] arguments = {
                PipeMessages.CONVERT_COMMAND,
                "-P", ".",
                "-s", "src/test/resources/extensions/src/cf",
                "--extension", "src/test/resources/extensions/src/cfe/Доработки",
                "-c", "src/test/resources/extensions/coverage/internal.xml"};
        assertEquals(CommandLine.ExitCode.USAGE, Coverage41C.getCommandLine().execute(arguments));
    }

    @Test
    void extensionRequiresProjectDir() {
        String[] arguments = {
                PipeMessages.CONVERT_COMMAND,
                "-s", "src/test/resources/extensions/src/cf",
                "--extension", "Доработки=src/test/resources/extensions/src/cfe/Доработки",
                "-c", "src/test/resources/extensions/coverage/internal.xml"};
        assertEquals(CommandLine.ExitCode.USAGE, Coverage41C.getCommandLine().execute(arguments));
    }

    @Test
    void duplicateExtension() {
        String[] arguments = {
                PipeMessages.CONVERT_COMMAND,
                "-P", "src/test/resources/extensions",
                "-s", "src/cf",
                "--extension", "Доработки=src/cfe/Доработки",
                "--extension", "Доработки=src/cfe/Второе",
                "-c", "src/test/resources/extensions/coverage/internal.xml"};
        assertEquals(CommandLine.ExitCode.USAGE, Coverage41C.getCommandLine().execute(arguments));
    }

    @Test
    void extensionOfSrcDirIsNotRepeated() {
        // -e задаёт расширение основного дерева -s, то же расширение в --extension - ошибка.
        // Проверка выполняется до подключения к отладчику
        String[] arguments = {
                PipeMessages.START_COMMAND,
                "-i", "Coverage41CExtensionSourceTest",
                "-u", "http://127.0.0.1:1",
                "-P", "src/test/resources/extensions",
                "-s", "src/cfe/Доработки",
                "-e", "Доработки",
                "--extension", "Доработки=src/cfe/Доработки"};
        assertEquals(CommandLine.ExitCode.USAGE, Coverage41C.getCommandLine().execute(arguments));
    }
}
