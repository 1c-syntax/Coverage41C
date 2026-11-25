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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Список разрешённых пользователей ИБ: чтение из файлов --config и из --include-user, проверка значений.
class IncludeUsersTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesListAndDropsDuplicatesKeepingOrder() throws Exception {
        write("a.json", "{\"includeUsers\":[\"ui\",\"unit\",\"ui\"]}");
        IncludeUsers users = IncludeUsers.load(tempDir.toFile(), List.of());
        assertTrue(users.isActive());
        assertEquals(List.of("ui", "unit"), new ArrayList<>(users.getUsers()));
        assertEquals("a.json → includeUsers", users.getSource());
        assertEquals(List.of(), users.getReplacedSources());
        assertEquals("Include users: ui, unit (a.json → includeUsers)", users.describe());
    }

    @Test
    void mergesFilesOfDirectoryInReadingOrder() throws Exception {
        write("a.json", "{\"includeUsers\":[\"u1\"]}");
        write("b.json", "{\"includeUsers\":[\"u2\",\"u1\"],\"team\":\"B\"}");
        write("c.json", "{\"exclude\":{\"objects\":[\"CommonModule.X\"]}}");
        IncludeUsers users = IncludeUsers.load(tempDir.toFile(), List.of());
        assertEquals(List.of("u1", "u2"), new ArrayList<>(users.getUsers()));
        assertEquals("a.json → includeUsers, b.json → includeUsers", users.getSource());
    }

    @Test
    void commandLineReplacesConfigAndNamesIgnoredFiles() throws Exception {
        write("a.json", "{\"includeUsers\":[\"smoke\"]}");
        IncludeUsers users = IncludeUsers.load(tempDir.toFile(), List.of("unit", "unit", "ui"));
        assertEquals(List.of("unit", "ui"), new ArrayList<>(users.getUsers()));
        assertEquals("--include-user", users.getSource());
        assertEquals(List.of("a.json"), users.getReplacedSources());
        assertEquals("Include users: unit, ui (--include-user; includeUsers of a.json ignored)", users.describe());
    }

    @Test
    void commandLineWithoutConfigHasNothingToReplace() throws Exception {
        IncludeUsers users = IncludeUsers.load(null, List.of("unit"));
        assertEquals("Include users: unit (--include-user)", users.describe());
    }

    @Test
    void inactiveWithoutUsersAndWithoutConfig() throws Exception {
        assertFalse(IncludeUsers.load(null, List.of()).isActive());
        write("a.json", "{\"exclude\":{\"objects\":[\"CommonModule.X\"]}}");
        assertFalse(IncludeUsers.load(tempDir.toFile(), List.of()).isActive());
        assertFalse(IncludeUsers.NONE.isActive());
        assertFalse(IncludeUsers.NONE.accepts("anyone"));
    }

    @Test
    void acceptsExactNameOnly() throws Exception {
        IncludeUsers users = IncludeUsers.load(null, List.of("coverage_unit", "Иванов Иван"));
        assertTrue(users.accepts("coverage_unit"));
        assertTrue(users.accepts("Иванов Иван"));
        assertFalse(users.accepts("Coverage_Unit"));
        assertFalse(users.accepts(" coverage_unit"));
        assertFalse(users.accepts(""));
        assertFalse(users.accepts(null));
    }

    @Test
    void rejectsWrongTypesEmptyListAndBlankValues() throws Exception {
        assertMessage("{\"includeUsers\":null}", "a.json → includeUsers: expected a list of user names");
        assertMessage("{\"includeUsers\":\"unit\"}", "a.json → includeUsers: expected a list of user names");
        assertMessage("{\"includeUsers\":{}}", "a.json → includeUsers: expected a list of user names");
        assertMessage("{\"includeUsers\":[]}", "a.json → includeUsers: empty list");
        assertMessage("{\"includeUsers\":[\"unit\",1]}", "a.json → includeUsers[1]: expected a string");
        assertMessage("{\"includeUsers\":[\"unit\",\" \"]}", "a.json → includeUsers[1]: empty value");
        IllegalArgumentException cli = assertThrows(IllegalArgumentException.class,
                () -> IncludeUsers.load(null, List.of("unit", "  ")));
        assertEquals("--include-user: empty value", cli.getMessage());
    }

    @Test
    void rejectsUnicodeWhitespaceWithoutChangingNonblankNames() throws Exception {
        assertMessage("{\"includeUsers\":[\"\u2003\"]}", "a.json → includeUsers[0]: empty value");
        assertThrows(IllegalArgumentException.class, () -> IncludeUsers.load(null, List.of("\u2003")));
        IncludeUsers users = IncludeUsers.load(null, List.of("\u2003unit"));
        assertTrue(users.accepts("\u2003unit"));
        assertFalse(users.accepts("unit"));
    }

    @Test
    void invalidConfigValueIsRejectedEvenWhenCommandLineReplacesIt() throws Exception {
        write("a.json", "{\"includeUsers\":[]}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IncludeUsers.load(tempDir.toFile(), List.of("unit")));
        assertEquals("a.json → includeUsers: empty list", e.getMessage());
    }

    private void assertMessage(String json, String message) throws Exception {
        write("a.json", json);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> IncludeUsers.load(tempDir.toFile(), List.of()));
        assertEquals(message, e.getMessage());
    }

    private void write(String name, String json) throws IOException {
        Files.writeString(tempDir.resolve(name), json);
    }
}
