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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StandardOutputConversionTest {
    @TempDir Path directory;

    @Test
    void rejectsJsonAtRedirectedStandardOutput() throws Exception {
        Path output = directory.resolve("report.json");
        Process process = convert("--exclusion-report", output.toString())
                .redirectOutput(output.toFile()).start();
        checkExit(process, 1);
        assertTrue(log().contains("different files"), log());
        assertEquals(0, Files.size(output));
    }

    @Test
    void startRejectsJsonAtRedirectedStandardOutputBeforeMeasuring() throws Exception {
        Path report = Files.writeString(directory.resolve("report.json"), "{}");
        Process process = cli("start", "-s", "src/test/resources/configuration", "-i", "stdout-" + UUID.randomUUID(),
                "--exclusion-report", report.toString())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(report.toFile())).start();
        checkExit(process, 1);
        assertTrue(log().contains("different files"), log());
        assertEquals("{}", Files.readString(report));
    }

    @Test
    void rejectsConfiguredJsonAtHardLinkToAppendOutput() throws Exception {
        Path report = Files.writeString(directory.resolve("report.json"), "{\"previous\":true}");
        Path alias = Files.createLink(directory.resolve("alias"), report);
        Path config = directory.resolve("rules.json");
        new ObjectMapper().writeValue(config.toFile(), Map.of("exclusionReport", report.toString()));
        Process process = convert("--config", config.toString())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(alias.toFile())).start();
        checkExit(process, 1);
        assertTrue(log().contains("different files"), log());
        assertEquals("{\"previous\":true}", Files.readString(report));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsJsonAndCoverageSharingTheStandardOutputPipe() throws Exception {
        Process process = convert("--exclusion-report", "/dev/stdout").start();
        byte[] bytes = process.getInputStream().readAllBytes();
        checkExit(process, 1);
        assertTrue(log().contains("different files"), log());
        assertEquals(0, bytes.length);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void validateCanWriteJsonDirectlyToStandardOutputPipe() throws Exception {
        Process process = cli("validate", "-s", "src/test/resources/configuration",
                "--exclusion-report", "/dev/stdout").start();
        byte[] bytes = process.getInputStream().readAllBytes();
        checkExit(process, 0);
        assertTrue(new ObjectMapper().readTree(bytes).has("totalLines"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unlinkedStdoutKeepsItsOwnIdentity() throws Exception {
        for (boolean same : List.of(false, true)) {
            Path output = Files.createFile(directory.resolve("stdout-" + same));
            Path report = directory.resolve("report-" + same + ".json");
            if (same) {
                Files.createLink(report, output);
            } else {
                Files.writeString(report, "{}");
            }
            ProcessBuilder builder = convert("--exclusion-report", report.toString());
            List<String> launch = new ArrayList<>(List.of("/bin/sh", "-c",
                    "exec 1>>\"$1\" && rm \"$1\" && shift && exec \"$@\"", "convert", output.toString()));
            launch.addAll(builder.command());
            checkExit(builder.command(launch).start(), same ? 1 : 0);
            assertFalse(Files.exists(output));
            if (same) {
                assertEquals(0, Files.size(report));
                assertTrue(log().contains("different files"), log());
            } else {
                assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
            }
        }
    }

    @Test
    void writesCoverageToPipeAndJsonToSeparateFile() throws Exception {
        Path report = directory.resolve("exclusions.json");
        Process process = convert("--exclusion-report", report.toString()).start();
        assertCoverage(process.getInputStream().readAllBytes());
        checkExit(process, 0);
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
    }

    @Test
    void soleStandardOutputStreamsAllFormatsWithUnusableTempDirectory() throws Exception {
        Path blocked = Files.createFile(directory.resolve("not-a-directory"));
        for (String format : List.of("GENERIC_COVERAGE", "COBERTURA", "LCOV")) {
            ProcessBuilder builder = convert("-f", format);
            builder.command().add(1, "-Djava.io.tmpdir=" + blocked);
            Process process = builder.start();
            byte[] bytes = process.getInputStream().readAllBytes();
            checkExit(process, 0);
            if (format.equals("LCOV")) {
                assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("end_of_record"));
            } else {
                assertCoverage(bytes);
            }
        }
    }

    @Test
    void closedPipeFailsConversion() throws Exception {
        Process process = convert().start();
        process.getInputStream().close();
        checkExit(process, 1);
        assertTrue(log().contains("Cannot write coverage to standard output"), log());
    }

    private static void assertCoverage(byte[] bytes) throws Exception {
        assertEquals("coverage", Utils.newInputXmlBuilder()
                .parse(new ByteArrayInputStream(bytes)).getDocumentElement().getTagName());
    }

    private ProcessBuilder convert(String... options) {
        ProcessBuilder builder = cli("convert", "-s", "src/test/resources/configuration", "-c",
                "src/test/resources/coverage/internal.xml");
        builder.command().addAll(List.of(options));
        return builder;
    }

    private ProcessBuilder cli(String... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "-cp", System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                Coverage41C.class.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectError(directory.resolve("errors.log").toFile());
    }

    private String log() throws Exception {
        return Files.readString(directory.resolve("errors.log"));
    }

    private void checkExit(Process process, int expected) throws Exception {
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CLI timed out");
            assertEquals(expected, process.exitValue(), log());
        } finally {
            process.destroyForcibly();
        }
    }
}
