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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
class ConversionFileLimitTest {
    @TempDir Path directory;

    @Test
    void partialJsonWritePreservesBothPreviousReports() throws Exception {
        Path coverage = directory.resolve("coverage.xml");
        Path report = directory.resolve("exclusions.json");
        List<String> args = convertTo(coverage, "--exclusion-report", report.toString());
        assertEquals(0, Coverage41C.getCommandLine().execute(args.toArray(new String[0])));
        byte[] previousCoverage = Files.readAllBytes(coverage);
        byte[] previousReport = Files.readAllBytes(report);

        // Select no modules so the coverage fits, while the JSON lists all unmatched prefixes.
        Path rules = unmatchedPrefixes();
        Path smallCoverage = directory.resolve("small.xml");
        Path largeReport = directory.resolve("large.json");
        List<String> control = convertTo(smallCoverage, "--exclusion-report", largeReport.toString(), "--config", rules.toString());
        assertEquals(0, Coverage41C.getCommandLine().execute(control.toArray(new String[0])));
        assertTrue(Files.size(smallCoverage) < 2048);
        assertTrue(Files.size(largeReport) > 4096);

        Collections.addAll(args, "--config", rules.toString());
        runUnderFileSizeLimit(args);
        assertArrayEquals(previousCoverage, Files.readAllBytes(coverage));
        assertArrayEquals(previousReport, Files.readAllBytes(report));
    }

    @Test
    void partialCoverageWritePreservesPreviousStandaloneReport() throws Exception {
        Path coverage = directory.resolve("coverage.xml");
        List<String> args = convertTo(coverage);
        assertEquals(0, Coverage41C.getCommandLine().execute(args.toArray(new String[0])));
        assertTrue(Files.size(coverage) > 4096);
        byte[] previous = "<coverage/>".getBytes(StandardCharsets.UTF_8);
        Files.write(coverage, previous);

        runUnderFileSizeLimit(args);
        assertArrayEquals(previous, Files.readAllBytes(coverage));
    }

    @Test
    void partialValidationReportPreservesPreviousJsonAndLeavesMissingFileAbsent() throws Exception {
        Path report = directory.resolve("exclusions.json");
        List<String> args = List.of("validate", "-s", "src/test/resources/configuration",
                "--config", unmatchedPrefixes().toString(), "--exclusion-report", report.toString());
        assertEquals(0, Coverage41C.getCommandLine().execute(args.toArray(new String[0])));
        assertTrue(Files.size(report) > 4096);
        byte[] previous = "{}".getBytes(StandardCharsets.UTF_8);
        Files.write(report, previous);

        runUnderFileSizeLimit(args);
        assertArrayEquals(previous, Files.readAllBytes(report));
        Files.delete(report);
        runUnderFileSizeLimit(args);
        assertTrue(Files.notExists(report));
    }

    private Path unmatchedPrefixes() throws Exception {
        String prefixes = IntStream.range(0, 100).mapToObj(i -> "\"Missing" + i + "\"")
                .collect(Collectors.joining(","));
        return Files.writeString(directory.resolve("rules.json"), "{\"includePrefix\":[" + prefixes + "]}");
    }

    private static List<String> convertTo(Path coverage, String... options) {
        List<String> args = new ArrayList<>(List.of("convert", "-s", "src/test/resources/configuration",
                "-c", "src/test/resources/coverage/internal.xml", "-o", coverage.toString()));
        Collections.addAll(args, options);
        return args;
    }

    private static void runUnderFileSizeLimit(List<String> args) throws Exception {
        // Ignore SIGXFSZ so a partial write is reported to the application as an I/O error.
        List<String> command = new ArrayList<>(List.of("/bin/sh", "-c", "trap '' XFSZ; ulimit -f 4 && exec \"$@\"",
                "limited-convert", Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                Coverage41C.class.getName()));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().remove("LC_ALL");
        builder.environment().put("LC_MESSAGES", "C");
        Path output = Files.createTempFile("limited-convert-", ".log");
        try {
            // The child inherits the file size limit. Keep its stdout on a pipe and drain it in
            // the unrestricted test JVM, so the expected error can always be captured.
            Process process = builder.start();
            try {
                var logCopy = process.getInputStream();
                var reader = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try (logCopy; var log = Files.newOutputStream(output,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        logCopy.transferTo(log);
                    } catch (java.io.IOException error) {
                        throw new java.io.UncheckedIOException(error);
                    }
                });
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Limited JVM timed out");
                reader.get(10, TimeUnit.SECONDS);
                String log = Files.readString(output, StandardCharsets.UTF_8);
                assertEquals(1, process.exitValue(), log);
                assertTrue(log.contains("File too large"), log);
            } finally {
                process.destroyForcibly();
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
