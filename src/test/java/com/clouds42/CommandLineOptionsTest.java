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

import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com.clouds42.CommandLineOptions.ConnectionOptions;
import com.clouds42.CommandLineOptions.DebuggerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLineOptionsTest {

    @TempDir
    Path tempDir;

    @Test
    void passwordIsEmptyByDefault() {
        assertEquals("", debuggerOptions().getPassword());
    }

    @Test
    void passwordComesFromEnvironmentVariable() {
        assertEquals(System.getenv("PATH"), debuggerOptions("-p:env", "PATH").getPassword());
    }

    @Test
    void autoconnectsAllKnownTargetsByDefault() {
        List<DebugTargetType> expected = new ArrayList<>(DebugTargetType.VALUES);
        expected.remove(DebugTargetType.UNKNOWN);

        assertEquals(expected, debuggerOptions().getAutoconnectTargets());
    }

    @Test
    void autoconnectsChosenTargets() {
        assertEquals(List.of(DebugTargetType.SERVER, DebugTargetType.CLIENT),
                debuggerOptions("-a", "SERVER", "CLIENT").getAutoconnectTargets());
    }

    @Test
    void platformBefore8316DoesNotAutoconnectMobileClient() {
        assertFalse(debuggerOptions().getFilteredAutoconnectTargets(ModuleDescriptor.Version.parse("8.3.15"))
                .contains(DebugTargetType.MOBILE_MANAGED_CLIENT));
        assertTrue(debuggerOptions().getFilteredAutoconnectTargets(ModuleDescriptor.Version.parse("8.3.16"))
                .contains(DebugTargetType.MOBILE_MANAGED_CLIENT));
    }

    @Test
    void debugAreasAreEmptyByDefault() {
        assertEquals(List.of(), debuggerOptions().getDebugAreaNames());
        assertEquals(List.of("Область1", "Область2"), debuggerOptions("-n", "Область1", "Область2").getDebugAreaNames());
    }

    @Test
    void pingTimeoutDefaultsToOneSecond() {
        assertEquals(1000, debuggerOptions().getPingTimeout());
    }

    @Test
    void debuggerUrlComesFromFile() throws Exception {
        Path file = tempDir.resolve("dbgs.url");
        Files.writeString(file, "127.0.0.1:1561\n");

        assertEquals("http://127.0.0.1:1561", connectionOptions("-u:file", file.toString()).getDebugServerUrl());
    }

    @Test
    void missingDebuggerUrlFileKeepsDefaultUrl() {
        assertEquals("http://127.0.0.1:1550/",
                connectionOptions("-u:file", tempDir.resolve("missing").toString()).getDebugServerUrl());
    }

    @Test
    void pipeNameIsBuiltFromInfobaseAndDebuggerUrl() throws Exception {
        ConnectionOptions options = connectionOptions("-i", "База", "-u", "http://host:1550/");

        assertEquals("/tmp/coverage41c/База_http___host_1550_.sock", Utils.getPipeName(options));
    }

    private static DebuggerOptions debuggerOptions(String... arguments) {
        return CommandLine.populateCommand(new DebuggerOptionsCommand(), arguments).options;
    }

    private static ConnectionOptions connectionOptions(String... arguments) {
        return CommandLine.populateCommand(new ConnectionOptions(), arguments);
    }

    @CommandLine.Command
    static class DebuggerOptionsCommand {
        @CommandLine.Mixin
        DebuggerOptions options;
    }
}
