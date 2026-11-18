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

import com.clouds42.CommandLineOptions.ConnectionOptions;
import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.clouds42.Commands.CoverServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CoverServerTest {

    @TempDir
    Path tempDir;

    // Любая ошибка записи отчёта превращается в ответ ER, а не в исключение на потоке команды.
    @Test
    void unexpectedErrorWhileWritingCoverageIsReportedAsFailure() {
        Map<BigDecimal, Integer> broken = new HashMap<>() {
            @Override
            public boolean isEmpty() {
                throw new IllegalStateException("broken coverage data");
            }
        };
        Map<URI, Map<BigDecimal, Integer>> coverageData = new HashMap<>();
        coverageData.put(tempDir.resolve("Module.bsl").toUri(), broken);

        assertFalse(new Server(coverageData, tempDir.resolve("coverage.xml")).dump());
    }

    private static final class Server extends CoverServer {
        private final Map<URI, Map<BigDecimal, Integer>> coverageData;
        private final MetadataOptions metadataOptions;
        private final OutputOptions outputOptions;

        Server(Map<URI, Map<BigDecimal, Integer>> coverageData, Path output) {
            this.coverageData = coverageData;
            this.metadataOptions = CommandLine.populateCommand(new OptionsCommand(), "-P", output.getParent().toString())
                    .metadataOptions;
            this.outputOptions = CommandLine.populateCommand(new OptionsCommand(), "-o", output.toString())
                    .outputOptions;
        }

        boolean dump() {
            return dumpCoverage();
        }

        @Override
        public ConnectionOptions getConnectionOptions() {
            return null;
        }

        @Override
        protected void cleanCoverageData() {
        }

        @Override
        protected boolean getSystemStarted() {
            return true;
        }

        @Override
        protected void gracefulShutdown(PrintWriter out) {
        }

        @Override
        protected MetadataOptions getMetadataOptions() {
            return metadataOptions;
        }

        @Override
        protected Map<URI, Map<BigDecimal, Integer>> getCoverageData() {
            return coverageData;
        }

        @Override
        protected OutputOptions getOutputOptions() {
            return outputOptions;
        }
    }

    @CommandLine.Command
    static class OptionsCommand {
        @CommandLine.Mixin
        MetadataOptions metadataOptions;

        @CommandLine.Mixin
        OutputOptions outputOptions;
    }
}
