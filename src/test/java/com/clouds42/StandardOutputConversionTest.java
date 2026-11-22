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

import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StandardOutputConversionTest {
    @TempDir Path directory;

    @Test
    void preparedConversionKeepsTheExplicitStreamWhenAnExistingOutputChangesToStdout() throws Exception {
        var options = CommandLine.populateCommand(new MetadataOptions(), "-s", "src/test/resources/configuration");
        var data = new HashMap<URI, Map<BigDecimal, Integer>>();
        var stream = new ByteArrayOutputStream();
        Path existing = Files.writeString(directory.resolve("output.xml"), "previous report");
        Path raw = Path.of("src/test/resources/coverage/internal.xml");

        try (var conversion = Utils.prepareConversion(options, "", "", data, raw.toFile(), existing.toFile(), stream)) {
            conversion.writeConversion(data, options,
                    CommandLine.populateCommand(new OutputOptions(), "-f", "GENERIC_COVERAGE"));
        }

        assertCoverage(stream.toByteArray());
        assertEquals("previous report", Files.readString(existing));
    }

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
        byte[] bytes = readOutput(process, 1);
        assertTrue(log().contains("different files"), log());
        assertEquals(0, bytes.length);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void validateCanWriteJsonDirectlyToStandardOutputPipe() throws Exception {
        Process process = cli("validate", "-s", "src/test/resources/configuration",
                "--exclusion-report", "/dev/stdout").start();
        byte[] bytes = readOutput(process, 0);
        assertTrue(new ObjectMapper().readTree(bytes).has("totalLines"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void legacyMetadataCanWriteJsonBeforeTheCoverageFileIsChosen() throws Exception {
        for (String reader : List.of("measuring", "metadata", "conversion")) {
            Path coverage = directory.resolve("coverage-" + reader + ".xml");
            Process process = javaMain(LegacyReports.class, reader,
                    "/dev/stdout", coverage.toString()).start();
            byte[] json = readOutput(process, 0);
            assertTrue(new ObjectMapper().readTree(json).has("totalLines"));
            assertCoverage(Files.readAllBytes(coverage));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void legacyDumpWritesAnExplicitDescriptorWhenSystemOutIsReplaced() throws Exception {
        Path report = directory.resolve("exclusions.json");
        Process process = javaMain(LegacyReports.class, "measuring", report.toString(), "/dev/stdout").start();
        byte[] xml = readOutput(process, 0);
        assertCoverage(xml);
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
    }

    public static final class LegacyReports {
        @CommandLine.Mixin MetadataOptions metadata;
        @CommandLine.Mixin OutputOptions output;

        public static void main(String[] args) throws Exception {
            LegacyReports options = new LegacyReports();
            new CommandLine(options).parseArgs("-s", "src/test/resources/configuration",
                    "--exclusion-report", args[1], "-o", args[2]);
            ByteArrayOutputStream capture = new ByteArrayOutputStream();
            try (PrintStream redirected = new PrintStream(capture)) {
                System.setOut(redirected);
                Map<URI, Map<BigDecimal, Integer>> coverage = new HashMap<>();
                if (args[0].equals("conversion")) {
                    Utils.readMetadataForConversion(options.metadata, coverage,
                            Path.of("src/test/resources/coverage/internal.xml").toFile());
                } else {
                    Utils.readMetadata(options.metadata, coverage, args[0].equals("measuring"));
                }
                Utils.dumpCoverageFile(coverage, options.metadata, options.output);
            }
            if (capture.size() != 0) {
                throw new AssertionError("An explicitly named file was sent to System.out");
            }
        }
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
        assertCoverage(readOutput(process, 0));
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
    }

    @Test
    void embeddedMemoryCaptureCanAccompanyJsonAtProcessStdout() throws Exception {
        Path report = directory.resolve("exclusions.json");
        Path captured = directory.resolve("captured.xml");
        ProcessBuilder builder = embedded("memory", captured, report);
        checkExit(builder.redirectOutput(report.toFile()).start(), 0);
        assertCoverage(Files.readAllBytes(captured));
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
    }

    @Test
    void embeddedStdoutAppendingToTheNamedReportRollsBack() throws Exception {
        assertSharedOutputRollsBack("append");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void embeddedStdoutOverwritingTheNamedCoverageRollsBack() throws Exception {
        assertSharedOutputRollsBack("overwrite");
    }

    private void assertSharedOutputRollsBack(String mode) throws Exception {
        Path named = directory.resolve("report");
        Path alias = Files.writeString(directory.resolve("alias"), "previous report");
        Files.createLink(named, alias);
        checkExit(embedded(mode, alias, named).start(), 1);
        assertTrue(log().contains("must use different files"), log());
        assertEquals("previous report", Files.readString(named));
        assertTrue(Files.isSameFile(named, alias));
    }

    private ProcessBuilder embedded(String mode, Path destination, Path named) {
        return javaMain(EmbeddedConvert.class, mode, destination.toString(), named.toString());
    }

    public static final class EmbeddedConvert {
        public static void main(String[] args) throws Exception {
            boolean memory = args[0].equals("memory");
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            int result;
            try (PrintStream redirected = new PrintStream(memory ? captured : Files.newOutputStream(Path.of(args[1]),
                    args[0].equals("append") ? StandardOpenOption.APPEND : StandardOpenOption.WRITE))) {
                System.setOut(redirected);
                List<String> command = new ArrayList<>(List.of("convert", "-s", "src/test/resources/configuration",
                        "-c", "src/test/resources/coverage/internal.xml", "--exclusion-report"));
                if (args[0].equals("overwrite")) {
                    // JSON is shorter than the XML file it overwrites. Its size alone cannot identify the collision.
                    command.add("/dev/stdout");
                    command.add("-o");
                }
                command.add(args[2]);
                result = Coverage41C.getCommandLine().execute(command.toArray(new String[0]));
            }
            if (memory) {
                Files.write(Path.of(args[1]), captured.toByteArray());
            }
            System.exit(result);
        }
    }

    @Test
    void soleStandardOutputStreamsAllFormatsWithUnusableTempDirectory() throws Exception {
        Path blocked = Files.createFile(directory.resolve("not-a-directory"));
        for (String format : List.of("GENERIC_COVERAGE", "COBERTURA", "LCOV")) {
            ProcessBuilder builder = convert("-f", format);
            builder.command().add(1, "-Djava.io.tmpdir=" + blocked);
            Process process = builder.start();
            byte[] bytes = readOutput(process, 0);
            if (format.equals("LCOV")) {
                assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("end_of_record"));
            } else {
                assertCoverage(bytes);
                if (format.equals("COBERTURA")) {
                    var root = Utils.newInputXmlBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
                    assertTrue(root.hasAttribute("line-rate"), "Expected Cobertura coverage");
                }
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

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void validateTreatsExplicitStdoutAsAnOpenStream() throws Exception {
        Path report = Files.writeString(directory.resolve("report.json"), " ");
        ProcessBuilder validation = cli("validate", "-s", "src/test/resources/configuration",
                "--exclusion-report", "/dev/stdout");
        checkExit(validation.redirectOutput(ProcessBuilder.Redirect.appendTo(report.toFile())).start(), 0);
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
        assertTrue(Files.readString(report).startsWith(" "));

        Path original = Files.writeString(directory.resolve("original"), " ");
        Path survivor = Files.createLink(directory.resolve("survivor"), original);
        List<String> command = new ArrayList<>(List.of("/bin/sh", "-c",
                "exec 1>>\"$1\" && rm \"$1\" && shift && exec \"$@\"", "validate", original.toString()));
        command.addAll(validation.command());
        validation.redirectOutput(ProcessBuilder.Redirect.PIPE);
        checkExit(validation.command(command).start(), 0);
        assertTrue(new ObjectMapper().readTree(survivor.toFile()).has("totalLines"));
        assertTrue(Files.readString(survivor).startsWith(" "));
        assertFalse(Files.exists(original.resolveSibling("original (deleted)")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void explicitStdoutCoverageAndJsonCanUseAPipe() throws Exception {
        Path alias = Files.createSymbolicLink(directory.resolve("pipe"), Path.of("/dev/stdout"));
        for (String target : List.of("/dev/stdout", "/dev/fd/1", alias.toString())) {
            Process process = convert("-o", target).start();
            assertCoverage(readOutput(process, 0));
        }

        Path xml = directory.resolve("coverage.xml");
        Process process = convert("-o", xml.toString(), "--exclusion-report", alias.toString()).start();
        byte[] output = readOutput(process, 0);
        assertTrue(new ObjectMapper().readTree(output).has("totalLines"));
        assertCoverage(Files.readAllBytes(xml));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void twoExplicitStdoutAliasesCannotShareThePipe() throws Exception {
        Process process = convert("-o", "/dev/fd/1", "--exclusion-report", "/dev/stdout").start();
        byte[] output = readOutput(process, 1);
        assertEquals(0, output.length);
        assertTrue(log().contains("different files"), log());
    }

    @Test
    void stdoutCannotAppendCoverageToInputRaw() throws Exception {
        Path raw = Files.copy(Path.of("src/test/resources/coverage/internal.xml"), directory.resolve("raw.xml"));
        byte[] before = Files.readAllBytes(raw);
        Path alias = Files.createLink(directory.resolve("raw-alias.xml"), raw);
        ProcessBuilder convert = cli("convert", "-s", "src/test/resources/configuration", "-c", raw.toString());
        checkExit(convert.redirectOutput(ProcessBuilder.Redirect.appendTo(alias.toFile())).start(), 1);
        assertTrue(log().contains("would overwrite input file"), log());
        assertArrayEquals(before, Files.readAllBytes(raw));
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
        return javaMain(Coverage41C.class, args);
    }

    private ProcessBuilder javaMain(Class<?> mainClass, String... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "-cp", System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                mainClass.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectError(directory.resolve("errors.log").toFile());
    }

    private String log() throws Exception {
        return Files.readString(directory.resolve("errors.log"));
    }

    private byte[] readOutput(Process process, int expected) throws Exception {
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try (var stream = process.getInputStream()) {
                return stream.readAllBytes();
            } catch (java.io.IOException error) {
                throw new UncheckedIOException(error);
            }
        });
        try {
            byte[] bytes = output.get(60, TimeUnit.SECONDS);
            checkExit(process, expected);
            return bytes;
        } finally {
            process.destroyForcibly();
        }
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
