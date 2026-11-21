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

import com._1c.g5.v8.dt.debug.model.base.data.AttachDebugUIResult;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetId;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.CommandsFactory;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoBase;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoMeasure;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmds;
import com.clouds42.CommandLineOptions.ConnectionOptions;
import com.clouds42.Commands.CoverageCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.scalasbt.ipcsocket.UnixDomainSocket;
import org.scalasbt.ipcsocket.Win32NamedPipeSocket;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Команда start против имитации сервера отладки: замеры, команды через сокет, итоговый отчёт.
// Команды и start ждут ответа не дольше TIMEOUT: зависание становится падением теста.
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class StartCommandTest {

    private static final String MODULE = "configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";
    private static final String OBJECT_ID = "1be4af7e-334e-49fa-a9f9-d80c737ff954";
    private static final String PROPERTY_ID = "d5963243-262e-4398-b4d7-fb16d06484f6";
    // Строки 2 и 10 — обычные, 4 — под Cover:off, 7 и 8 — под Cover:auto, 11 — конец процедуры.
    private static final String[] MARKED_MODULE = {
            "Процедура А() Экспорт",
            "\tБ();",
            "\t// Cover:off",
            "\tВ();",
            "\t// Cover:on",
            "\t// Cover:auto",
            "\tГ();",
            "\tД();",
            "\t// Cover:on",
            "\tЕ();",
            "КонецПроцедуры"};

    @TempDir
    Path tempDir;

    private FakeDebugServer dbgs;
    private final String infobase = "test_" + UUID.randomUUID().toString().replace("-", "");
    private static final long TIMEOUT = 20;
    private static final Duration TEST_FINAL_MEASURE_WINDOW = Duration.ofSeconds(1);
    private static final Duration TEST_FINAL_CONFIRMATION_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration AFTER_OBSERVATION_WINDOW = TEST_FINAL_MEASURE_WINDOW.plusNanos(1);

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private Path output;

    @BeforeEach
    void startDebugServer() throws IOException {
        dbgs = new FakeDebugServer();
        output = tempDir.resolve("coverage.xml");
    }

    @AfterEach
    void stopDebugServer() {
        executor.shutdownNow();
        dbgs.close();
    }

    @Test
    void initialTargetAttachFailureDoesNotDiscardLaterMeasures() throws Exception {
        dbgs.fail("attachDetachDbgTargets", 503);
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void newTargetAttachFailureDoesNotDiscardLaterMeasures() throws Exception {
        Future<Integer> start = start();
        dbgs.fail("attachDetachDbgTargets", 503);
        dbgs.deliver(FakeDebugServer.targetStarted(UUID.randomUUID()));
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void unavailableDebugAreaDoesNotPreventCollectingMeasures() throws Exception {
        dbgs.fail("getDbgTargets", 503);
        Future<Integer> start = start("-n", "Область1");
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void unavailableDebugAreaFailsEmptyRawCollection() throws Exception {
        dbgs.fail("getDbgTargets", 204);
        Future<Integer> start = startRaw("-n", "Area");

        int stopResult = send(PipeMessages.EXIT_COMMAND);
        int startResult = start.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, startResult);
        NodeList files = Utils.newInputXmlBuilder().parse(output.toFile()).getElementsByTagName("file");
        assertEquals(0, files.getLength());
    }

    @Test
    void unavailableDebugAreaFailsWhenAllMeasuresAreExcluded() throws Exception {
        Path config = tempDir.resolve("exclusions.json");
        Files.writeString(config, "{\"exclude\":{\"objects\":[\"CommonModule.ПростойОбщийМодуль\"]}}");
        dbgs.fail("getDbgTargets", 204);
        Future<Integer> start = start("-n", "Area", "--config", config.toString());
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));

        int stopResult = send(PipeMessages.EXIT_COMMAND);
        int startResult = start.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(null, coverage());
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, startResult);
    }

    @Test
    void stopWritesMeasuredCoverage() throws Exception {
        Future<Integer> start = start();

        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 3, 4, 1, 7, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 7, true, 10, false, 11, false), coverage());
        List<FakeDebugServer.Request> measureModes = dbgs.requests("setMeasureMode");
        assertTrue(measureModes.get(measureModes.size() - 2).body.contains("measureModeSeanceID"));
        assertFalse(measureModes.get(measureModes.size() - 1).body.contains("measureModeSeanceID"));
        assertTrue(dbgs.commands().contains("detachDebugUI"));
    }

    @Test
    void startRejectsSharedReportAndCoveragePathBeforeMeasuring() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(output, "existing coverage");
        StringWriter stderr = new StringWriter();
        CommandLine command = Coverage41C.getCommandLine();
        command.setErr(new PrintWriter(stderr, true));

        dbgs.fail("attachDebugUI", 503);
        int result = command.execute(PipeMessages.START_COMMAND, "-i", infobase, "-u", dbgs.url(),
                "-P", project.toString(), "-s", "configuration", "-o", output.toString(),
                "--exclusion-report", output.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, result, stderr.toString());
        assertTrue(stderr.toString().contains("Exclusion report and coverage output must use different files"),
                stderr.toString());
        assertEquals("existing coverage", Files.readString(output));
        assertTrue(dbgs.requests("attachDebugUI").isEmpty());
    }

    @Test
    void exclusionReportIsWrittenBeforeMeasuring() throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        Future<Integer> start = start("--exclusion-report", report.toString());

        assertTrue(Files.exists(report));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void readOnlyPublishedJsonSurvivesSharedAndSeparateEmbeddedDumps() throws Exception {
        Path json = tempDir.resolve("exclusions.json");
        Future<Integer> start = executor.submit(() -> Coverage41C.getCommandLine().execute("start",
                "-i", infobase, "-u", dbgs.url(), "-t", "10", "-s", "src/test/resources/configuration",
                "--exclusion-report", json.toString()));
        awaitStarted(start);
        byte[] expected = Files.readAllBytes(json);
        Path link = Files.createLink(tempDir.resolve("alias"), json);
        var originalMode = Files.getPosixFilePermissions(json);
        var readOnly = PosixFilePermissions.fromString("r--r--r--");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (PrintStream shared = new PrintStream(new FileOutputStream(link.toFile(), true));
             PrintStream separate = new PrintStream(captured)) {
            Files.setPosixFilePermissions(json, readOnly);
            System.setOut(shared);
            assertEquals(1, send(PipeMessages.DUMP_COMMAND));
            assertArrayEquals(expected, Files.readAllBytes(link));
            System.setOut(separate);
            assertEquals(0, send(PipeMessages.DUMP_COMMAND));
            assertEquals("coverage", Utils.newInputXmlBuilder().parse(
                    new ByteArrayInputStream(captured.toByteArray())).getDocumentElement().getTagName());
            captured.reset();
            assertEquals(0, send(PipeMessages.EXIT_COMMAND));
            assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));
            assertEquals("coverage", Utils.newInputXmlBuilder().parse(
                    new ByteArrayInputStream(captured.toByteArray())).getDocumentElement().getTagName());
            assertTrue(Files.isSameFile(link, json));
            assertArrayEquals(expected, Files.readAllBytes(json));
            assertEquals(readOnly, Files.getPosixFilePermissions(json));
        } finally {
            System.setOut(original);
            Files.setPosixFilePermissions(json, originalMode);
        }
    }

    @Test
    void defaultJavaStartUsesCurrentSystemOutForDumpAndStop() throws Exception {
        assertStartUsesCurrentSystemOut(Coverage41C.getCommandLine(), "start");
    }

    @Test
    void directCollectorUsesCurrentSystemOutForDumpAndStop() throws Exception {
        assertStartUsesCurrentSystemOut(new CommandLine(new CoverageCommand(TEST_FINAL_MEASURE_WINDOW,
                TEST_FINAL_CONFIRMATION_TIMEOUT) { }));
    }

    private void assertStartUsesCurrentSystemOut(CommandLine command, String... prefix) throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        List<String> arguments = new ArrayList<>(Arrays.asList(prefix));
        arguments.addAll(List.of("-i", infobase, "-u", dbgs.url(), "-t", "10",
                "-s", "src/test/resources/configuration", "--exclusion-report", report.toString()));
        Future<Integer> start = executor.submit(() -> command.execute(arguments.toArray(new String[0])));
        awaitStarted(start);
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        byte[] json = Files.readAllBytes(report);
        ByteArrayOutputStream dump = new ByteArrayOutputStream();
        ByteArrayOutputStream stop = new ByteArrayOutputStream();
        PrintStream original = System.out;
        PrintStream failed = new PrintStream(OutputStream.nullOutputStream());
        failed.close();
        failed.write(0);
        try (PrintStream first = new PrintStream(dump); PrintStream second = new PrintStream(stop);
             PrintStream conflicting = new PrintStream(new FileOutputStream(report.toFile(), true))) {
            System.setOut(failed);
            int failedDump = send(PipeMessages.DUMP_COMMAND);
            System.setOut(conflicting);
            int conflictingDump = send(PipeMessages.DUMP_COMMAND);
            System.setOut(first);
            int dumpResult = send(PipeMessages.DUMP_COMMAND);
            System.setOut(second);
            assertEquals(0, send(PipeMessages.EXIT_COMMAND));
            assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(1, failedDump);
            assertEquals(1, conflictingDump);
            assertEquals(0, dumpResult);
        } finally {
            System.setOut(original);
        }
        for (ByteArrayOutputStream captured : List.of(dump, stop)) {
            assertTrue(captured.size() > 0, "The current System.out did not receive coverage");
            var xml = Utils.newInputXmlBuilder().parse(new ByteArrayInputStream(captured.toByteArray()));
            assertEquals("coverage", xml.getDocumentElement().getTagName());
        }
        assertArrayEquals(json, Files.readAllBytes(report));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void explicitStartFactoryUsesBorrowedStreamForStdoutAlias() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Path report = tempDir.resolve("exclusions.json");
        Future<Integer> start = executor.submit(() -> Coverage41C.getCommandLine(captured).execute("start",
                "-i", infobase, "-u", dbgs.url(), "-t", "10", "-s", "src/test/resources/configuration",
                "-o", "/dev/stdout", "--exclusion-report", report.toString()));
        awaitStarted(start);
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));
        var xml = Utils.newInputXmlBuilder().parse(new ByteArrayInputStream(captured.toByteArray()));
        assertEquals("coverage", xml.getDocumentElement().getTagName());
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void explicitStdoutPathOnDefaultStartUsesDescriptorDespiteSystemSetOut() throws Exception {
        Path capture = tempDir.resolve("capture.xml");
        Path report = tempDir.resolve("exclusions.json");
        Path alias = Files.createSymbolicLink(tempDir.resolve("stdout"), Path.of("/dev/stdout"));
        Process collector = embeddedStart("redirected", capture, report, output, "-o", alias.toString());
        try {
            awaitStarted(collector.onExit().thenApply(Process::exitValue));
            assertEquals(0, send(PipeMessages.EXIT_COMMAND));
            assertTrue(collector.waitFor(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, collector.exitValue());
            assertEquals(0, Files.size(capture));
            assertEquals("coverage", Utils.newInputXmlBuilder().parse(output.toFile()).getDocumentElement().getTagName());
            assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
        } finally {
            collector.destroyForcibly();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void explicitStdoutPathOnDefaultStartRejectsJsonAtDescriptorBeforeMeasuring() throws Exception {
        Path capture = tempDir.resolve("capture.xml");
        Files.writeString(output, "original");
        dbgs.fail("attachDebugUI", 503);
        Process collector = embeddedStart("redirected", capture, output, output, "-o", "/dev/stdout");
        try {
            assertTrue(collector.waitFor(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(1, collector.exitValue());
            assertEquals("original", Files.readString(output));
            assertTrue(dbgs.requests("attachDebugUI").isEmpty());
        } finally {
            collector.destroyForcibly();
        }
    }

    @Test
    void embeddedStartKeepsJsonWhenSystemStdoutIsRedirectedToIt() throws Exception {
        assertEmbeddedReports("memory", false);
    }

    @Test
    void embeddedStartWritesSeparateFileStreamAndJson() throws Exception {
        assertEmbeddedReports("file", false);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void embeddedStartCanWriteJsonToProcessStdoutAndCoverageToMemory() throws Exception {
        assertEmbeddedReports("memory", true);
    }

    private void assertEmbeddedReports(String sink, boolean jsonToStdout) throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        Process collector = embeddedStart(sink, output, jsonToStdout ? Path.of("/dev/stdout") : report, report);
        try {
            awaitStarted(collector.onExit().thenApply(Process::exitValue));
            assertEquals(0, send(PipeMessages.EXIT_COMMAND));
            assertTrue(collector.waitFor(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, collector.exitValue());
            assertEquals("coverage", Utils.newInputXmlBuilder().parse(output.toFile()).getDocumentElement().getTagName());
            assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
        } finally {
            collector.destroyForcibly();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void embeddedStartSendsExplicitStdoutJsonToProcessWhenCoverageIsNamed() throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        Path unused = tempDir.resolve("unused");
        Process collector = embeddedStart("unused", unused, Path.of("/dev/stdout"), report, "-o", output.toString());
        try {
            awaitStarted(collector.onExit().thenApply(Process::exitValue));
            assertEquals(0, send(PipeMessages.EXIT_COMMAND));
            assertTrue(collector.waitFor(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(0, collector.exitValue());
            assertEquals("coverage", Utils.newInputXmlBuilder().parse(output.toFile()).getDocumentElement().getTagName());
            assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
        } finally {
            collector.destroyForcibly();
        }
    }

    @Test
    void embeddedDumpAndStopPreserveJsonAtHardLinkToCoverageStream() throws Exception {
        Path report = Files.writeString(tempDir.resolve("exclusions.json"), "{}");
        Path link = Files.createLink(output, report);
        Process collector = embeddedStart("file", link, report, tempDir.resolve("stdout"));
        try {
            awaitStarted(collector.onExit().thenApply(Process::exitValue));
            String json = Files.readString(report);
            assertTrue(new ObjectMapper().readTree(json).has("totalLines"));
            assertEquals(1, send(PipeMessages.DUMP_COMMAND));
            assertEquals(json, Files.readString(link));
            assertEquals(1, send(PipeMessages.EXIT_COMMAND));
            assertTrue(collector.waitFor(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(1, collector.exitValue());
            assertEquals(json, Files.readString(report));
        } finally {
            collector.destroyForcibly();
        }
    }

    @Test
    void namedDumpAndStopRejectAnOutputNowLinkedToTheExclusionReport() throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        Future<Integer> collector = start("--exclusion-report", report.toString());
        byte[] original;
        int dumpResult;
        int stopResult;
        boolean linkedAfterDump;
        try {
            original = Files.readAllBytes(report);
            Files.deleteIfExists(output);
            Files.createLink(output, report);
            dumpResult = send(PipeMessages.DUMP_COMMAND);
            linkedAfterDump = Files.isSameFile(output, report);
        } finally {
            stopResult = send(PipeMessages.EXIT_COMMAND);
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, dumpResult);
        assertTrue(linkedAfterDump);
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(Files.isSameFile(output, report));
        assertArrayEquals(original, Files.readAllBytes(report));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void namedDumpAndStopRejectAnOutputNowLinkedToTheConfig() throws Exception {
        Path config = Files.writeString(tempDir.resolve("coverage41c.json"),
                "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}");
        byte[] original = Files.readAllBytes(config);
        Future<Integer> collector = start("--config", config.toString());
        int dumpResult;
        int stopResult;
        try {
            Files.deleteIfExists(output);
            Files.createSymbolicLink(output, config);
            dumpResult = send(PipeMessages.DUMP_COMMAND);
        } finally {
            stopResult = send(PipeMessages.EXIT_COMMAND);
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, dumpResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertArrayEquals(original, Files.readAllBytes(config));
    }

    @Test
    void namedDumpAndStopPreserveThePublishedJsonAfterItIsMoved() throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        Future<Integer> collector = start("--exclusion-report", report.toString());
        Path archived = Files.move(report, tempDir.resolve("archived.json"));
        Files.setLastModifiedTime(archived, java.nio.file.attribute.FileTime.fromMillis(1_000));
        byte[] original = Files.readAllBytes(archived);
        Files.writeString(report, "replacement");
        int dumpResult;
        int stopResult;
        try {
            Files.createLink(output, archived);
            dumpResult = send(PipeMessages.DUMP_COMMAND);
        } finally {
            stopResult = send(PipeMessages.EXIT_COMMAND);
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, dumpResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertArrayEquals(original, Files.readAllBytes(archived));
        assertEquals("replacement", Files.readString(report));
    }

    @Test
    void replacingLoadedConfigDoesNotBlockACoverageFile() throws Exception {
        String rules = "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}";
        Path config = Files.writeString(tempDir.resolve("coverage41c.json"), rules);
        Path replacement = Files.writeString(tempDir.resolve("replacement.json"), rules);
        Future<Integer> collector = start("--config", config.toString());
        try {
            Files.move(replacement, config, StandardCopyOption.REPLACE_EXISTING);
            Files.createFile(output);
            assertFalse(Files.isSameFile(config, output));
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.DUMP_COMMAND));
        } finally {
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        }
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals("coverage", Utils.newInputXmlBuilder().parse(output.toFile()).getDocumentElement().getTagName());
        assertEquals(rules, Files.readString(config));
    }

    @Test
    void defaultEmbeddedStartCanWriteToReplacedSystemOutWhenNativeStdoutNamesJson() throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        Process collector = embeddedStart("redirected", output, report, report);
        try {
            awaitStarted(collector.onExit().thenApply(Process::exitValue));
            byte[] json = Files.readAllBytes(report);
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.DUMP_COMMAND));
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
            assertTrue(collector.waitFor(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(CommandLine.ExitCode.OK, collector.exitValue());
            assertTrue(Files.readString(output).contains("<coverage "));
            assertArrayEquals(json, Files.readAllBytes(report));
        } finally {
            collector.destroyForcibly();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void dumpAndStopProtectAConfigAlreadyOpenOnStdout() throws Exception {
        Path config = Files.writeString(tempDir.resolve("coverage41c.json"),
                "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}");
        byte[] original = Files.readAllBytes(config);
        Path report = tempDir.resolve("exclusions.json");
        Process collector = embeddedStart("system", output, report, config,
                "-o", output.toString(), "--config", config.toString());
        try {
            awaitStarted(collector.onExit().thenApply(Process::exitValue));
            Path archived = Files.move(config, tempDir.resolve("archived-config.json"));
            Files.write(config, original);
            Files.createSymbolicLink(output, Path.of("/dev/stdout"));

            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.DUMP_COMMAND));
            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
            assertTrue(collector.waitFor(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(CommandLine.ExitCode.SOFTWARE, collector.exitValue());
            assertArrayEquals(original, Files.readAllBytes(archived));
            assertArrayEquals(original, Files.readAllBytes(config));
        } finally {
            collector.destroyForcibly();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void dumpAndStopPreserveJsonInitiallyWrittenThroughStdoutAlias() throws Exception {
        Path reportAlias = Files.createSymbolicLink(tempDir.resolve("exclusions.json"), Path.of("/dev/stdout"));
        Path published = tempDir.resolve("published.json");
        Path replacement = Files.writeString(tempDir.resolve("replacement.json"), "{}");
        Process collector = embeddedStart("unused", output, reportAlias, published, "-o", output.toString());
        try {
            awaitStarted(collector.onExit().thenApply(Process::exitValue));
            byte[] json = Files.readAllBytes(published);
            assertTrue(json.length > 2);
            Files.delete(reportAlias);
            Files.createSymbolicLink(reportAlias, replacement);
            Files.createLink(output, published);

            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.DUMP_COMMAND));
            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
            assertTrue(collector.waitFor(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(CommandLine.ExitCode.SOFTWARE, collector.exitValue());
            assertArrayEquals(json, Files.readAllBytes(published));
            assertEquals("{}", Files.readString(replacement));
        } finally {
            collector.destroyForcibly();
        }
    }

    private Process embeddedStart(String sink, Path capture, Path report, Path stdout, String... options) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                EmbeddedStartProbe.class.getName(), sink, capture.toString(), "-i", infobase, "-u", dbgs.url(),
                "-s", "src/test/resources/configuration", "-t", "10", "--exclusion-report", report.toString());
        builder.command().addAll(Arrays.asList(options));
        return builder.redirectOutput(ProcessBuilder.Redirect.appendTo(stdout.toFile()))
                .redirectError(tempDir.resolve("embedded-start.log").toFile()).start();
    }

    public static class EmbeddedStartProbe {
        public static void main(String[] args) throws Exception {
            boolean memory = args[0].equals("memory");
            Path path = Path.of(args[1]);
            int code;
            if (args[0].equals("redirected")) {
                try (PrintStream captured = new PrintStream(path.toFile())) {
                    System.setOut(captured);
                    List<String> start = new ArrayList<>(List.of("start"));
                    start.addAll(Arrays.asList(args).subList(2, args.length));
                    code = Coverage41C.getCommandLine().execute(start.toArray(new String[0]));
                }
                System.exit(code);
            }
            try (OutputStream sink = args[0].equals("file") ? new FileOutputStream(path.toFile(), true) : new ByteArrayOutputStream()) {
                CoverageCommand command = new CoverageCommand(TEST_FINAL_MEASURE_WINDOW, TEST_FINAL_CONFIRMATION_TIMEOUT) {
                    @Override
                    protected OutputStream coverageOutput() {
                        if (args[0].equals("system")) {
                            return super.coverageOutput();
                        }
                        if (args[0].equals("unused")) {
                            throw new AssertionError("A named coverage file does not use the coverage sink");
                        }
                        return sink;
                    }
                };
                code = new CommandLine(command).execute(Arrays.copyOfRange(args, 2, args.length));
                if (memory && code == 0) {
                    Files.write(path, ((ByteArrayOutputStream) sink).toByteArray());
                }
            }
            System.exit(code);
        }
    }

    @Test
    void stopDrainsSixFinalMeasuresReturnedInSeparatePolls() throws Exception {
        Future<Integer> collector = start("-f", "LCOV");
        dbgs.deliverAcrossFinalPolls(separateFinalMeasures(6));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        List<String> lcov = Files.readAllLines(output);
        assertTrue(lcov.contains("DA:10,6"), lcov.toString());
    }

    @Test
    void stopKeepsFinalMeasuresAfterTwoEmptyPolls() throws Exception {
        Future<Integer> collector = startRaw();
        List<List<DBGUIExtCmdInfoBase>> polls = new ArrayList<>();
        for (int line = 1; line <= 6; line++) {
            polls.add(List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, line, 1)));
        }
        polls.add(1, List.of());
        polls.add(2, List.of());
        dbgs.deliverAcrossFinalPolls(polls);

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(1, true, 2, true, 3, true, 4, true, 5, true, 6, true),
                coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
    }

    @Test
    void stopFailsAndKeepsLateMeasurementsThroughConfirmationTimeout() throws Exception {
        FinalPollClock clock = new FinalPollClock();
        Future<Integer> collector = startRaw(startCommandLine(System::nanoTime, clock));
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 1, 1));
        dbgs.pause("pingDebugUIParams");
        Future<Integer> stop;
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            // The in-flight poll returns empty. The next poll will start after the observation window.
            dbgs.deliverAcrossFinalPolls(List.of(List.of(),
                    List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1)), List.of(),
                    List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 3, 1)), List.of(),
                    List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 4, 1))));
            stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            waitForFinalMeasureThread();
            clock.advance(AFTER_OBSERVATION_WINDOW);
            dbgs.resume("pingDebugUIParams");
            dbgs.awaitFinalPollProcessing();
        } finally {
            dbgs.resume("pingDebugUIParams");
            clock.advance(TEST_FINAL_CONFIRMATION_TIMEOUT);
            clock.resumeRealTime();
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(1, true, 2, true, 3, true, 4, true), coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
    }

    @Test
    void inFlightEmptyPollDoesNotConfirmFinalQuietWindow() throws Exception {
        FinalPollClock clock = new FinalPollClock();
        Future<Integer> collector = start(startCommandLine(System::nanoTime, clock), "-f", "LCOV");
        dbgs.pause("pingDebugUIParams");
        Future<Integer> stop;
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            dbgs.deliverAcrossFinalPolls(List.of(List.of(),
                    List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1)), List.of(),
                    List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1))));
            stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            waitForFinalMeasureThread();
            clock.advance(AFTER_OBSERVATION_WINDOW);
            dbgs.resume("pingDebugUIParams");
            dbgs.awaitFinalPollProcessing();
        } finally {
            dbgs.resume("pingDebugUIParams");
            clock.advance(TEST_FINAL_CONFIRMATION_TIMEOUT);
            clock.resumeRealTime();
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        List<String> lcov = Files.readAllLines(output);
        assertTrue(lcov.contains("DA:10,2"), lcov.toString());
    }

    @Test
    void stopAcceptsTimelyMeasureWhenTargetLookupFinishesAfterObservationWindow() throws Exception {
        FinalPollClock clock = new FinalPollClock();
        dbgs.setTargets();
        Future<Integer> collector = start(startCommandLine(System::nanoTime, clock), "--include-user", "suite");
        DebugTargetId target = user("suite");
        DebugTargetId nameless = FakeDebugServer.target(UUID.fromString(target.getId()),
                DebugTargetType.MANAGED_CLIENT, null);
        dbgs.setTargets(target);

        dbgs.pause("pingDebugUIParams");
        dbgs.pause("getDbgTargets");
        Future<Integer> stop;
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            dbgs.deliverAcrossFinalPolls(List.of(List.of(FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 10, 1))));
            stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            waitForFinalMeasureThread();
            dbgs.resume("pingDebugUIParams");
            dbgs.awaitPaused("getDbgTargets");
            clock.advance(AFTER_OBSERVATION_WINDOW);
        } finally {
            dbgs.resume("pingDebugUIParams");
            dbgs.resume("getDbgTargets");
            clock.resumeRealTime();
        }

        assertEquals(CommandLine.ExitCode.OK, stop.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void sustainedFinalMeasuresFailAtDeadlineAndWriteAvailableCoverage() throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        // At 40 ms per scripted poll these packets outlast the observation and confirmation windows.
        dbgs.deliverAcrossFinalPolls(separateFinalMeasures(75));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void stopKeepsReceivedMeasuresWhenTargetAttachmentStalls() throws Exception {
        assertStopKeepsReceivedMeasuresWhenAttachmentStalls(true);
    }

    @Test
    void stopKeepsReceivedMeasuresWhenTargetAttachmentStallsWithoutUserFilter() throws Exception {
        assertStopKeepsReceivedMeasuresWhenAttachmentStalls(false);
    }

    private void assertStopKeepsReceivedMeasuresWhenAttachmentStalls(boolean filterByUser) throws Exception {
        dbgs.setTargets();
        Future<Integer> collector = filterByUser ? start("--include-user", "suite") : start();
        DebugTargetId announced = user("suite");
        DebugTargetId nameless = FakeDebugServer.target(UUID.fromString(announced.getId()),
                DebugTargetType.MANAGED_CLIENT, null);
        DebugTargetId another = user("suite");

        dbgs.pause("attachDetachDbgTargets");
        dbgs.deliverOnMeasureOff(FakeDebugServer.targetStarted(announced),
                FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 10, 1),
                FakeDebugServer.targetStarted(another),
                FakeDebugServer.measure(user("other"), OBJECT_ID, PROPERTY_ID, 11, 1));
        Future<Integer> stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
        try {
            dbgs.awaitPaused("attachDetachDbgTargets");
            assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(Map.of(2, false, 10, true, 11, !filterByUser), coverage());
        } finally {
            dbgs.resume("attachDetachDbgTargets");
        }
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1, dbgs.requests("attachDetachDbgTargets").size());
    }

    @Test
    void stopKeepsKnownUsersMeasurementsWhenEarlierTargetLookupStalls() throws Exception {
        DebugTargetId known = user("suite");
        dbgs.setTargets(known);
        Future<Integer> collector = start("--include-user", "suite");
        DebugTargetId late = user("suite");
        DebugTargetId nameless = FakeDebugServer.target(UUID.fromString(late.getId()),
                DebugTargetType.MANAGED_CLIENT, null);
        dbgs.setTargets(known, late);

        dbgs.pause("getDbgTargets");
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 11, 1),
                FakeDebugServer.measure(known, OBJECT_ID, PROPERTY_ID, 10, 1));
        Future<Integer> stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
        try {
            dbgs.awaitPaused("getDbgTargets");
            assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
            assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
        } finally {
            dbgs.resume("getDbgTargets");
        }
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1, dbgs.requests("attachDetachDbgTargets").size());
    }

    @Test
    void pendingAttachmentIsDroppedOnQuitButKnownUserMeasurementsAreKept() throws Exception {
        dbgs.setTargets();
        Future<Integer> collector = start("--include-user", "suite");
        DebugTargetId target = user("suite");
        DebugTargetId nameless = FakeDebugServer.target(UUID.fromString(target.getId()),
                DebugTargetType.MANAGED_CLIENT, null);
        dbgs.deliver(FakeDebugServer.targetStarted(target), FakeDebugServer.targetQuit(target),
                FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(dbgs.requests("attachDetachDbgTargets").isEmpty());
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void earlierNamelessPacketUsesLookupInsteadOfLaterConflictingPacketName() throws Exception {
        dbgs.setTargets();
        Future<Integer> collector = start("--include-user", "suite");
        DebugTargetId listed = user("suite");
        UUID id = UUID.fromString(listed.getId());
        DebugTargetId nameless = FakeDebugServer.target(id, DebugTargetType.MANAGED_CLIENT, null);
        DebugTargetId named = FakeDebugServer.target(id, DebugTargetType.MANAGED_CLIENT, "other");
        dbgs.setTargets(listed);
        dbgs.deliver(FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 11, 1),
                FakeDebugServer.measure(named, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(2, dbgs.requests("getDbgTargets").size());
        assertTrue(dbgs.requests("attachDetachDbgTargets").isEmpty());
        assertEquals(Map.of(2, false, 10, false, 11, true), coverage());
    }


    @Test
    void cleanDropsDeferredRawMeasurements() throws Exception {
        assertCleanDropsDeferredMeasurements(true, false);
    }

    @Test
    void cleanDropsDeferredMappedMeasurements() throws Exception {
        assertCleanDropsDeferredMeasurements(false, false);
    }

    @Test
    void cleanAcceptsNewRawMeasurementsAfterDroppingDeferredOnes() throws Exception {
        assertCleanDropsDeferredMeasurements(true, true);
    }

    @Test
    void cleanAcceptsNewMappedMeasurementsAfterDroppingDeferredOnes() throws Exception {
        assertCleanDropsDeferredMeasurements(false, true);
    }

    private void assertCleanDropsDeferredMeasurements(boolean raw, boolean addFreshMeasurement) throws Exception {
        DebugTargetId known = user("suite");
        DebugTargetId late = user("suite");
        DebugTargetId nameless = FakeDebugServer.target(UUID.fromString(late.getId()),
                DebugTargetType.MANAGED_CLIENT, null);
        dbgs.setTargets(known);
        Future<Integer> collector = raw ? startRaw("--include-user", "suite") : start("--include-user", "suite");
        dbgs.setTargets(known, late);
        dbgs.pause("getDbgTargets");
        Future<Integer> delivery = executor.submit(() -> {
            dbgs.deliver(FakeDebugServer.measure(known, OBJECT_ID, PROPERTY_ID, 10, 1),
                    FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 11, 1));
            return 0;
        });
        try {
            dbgs.awaitPaused("getDbgTargets");
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.CLEAN_COMMAND));
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.CLEAN_COMMAND));
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.DUMP_COMMAND));
            assertCleanedMeasurements(raw, false);
        } finally {
            dbgs.resume("getDbgTargets");
        }
        delivery.get(TIMEOUT, TimeUnit.SECONDS);
        if (addFreshMeasurement) {
            dbgs.deliver(FakeDebugServer.measure(known, OBJECT_ID, PROPERTY_ID, 2, 1));
        }
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertCleanedMeasurements(raw, addFreshMeasurement);
    }

    private void assertCleanedMeasurements(boolean raw, boolean freshMeasurement) throws Exception {
        if (raw) {
            NodeList lines = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
                    .getElementsByTagName("lineToCover");
            assertEquals(freshMeasurement ? 1 : 0, lines.getLength());
            if (freshMeasurement) {
                assertEquals("2", ((Element) lines.item(0)).getAttribute("lineNumber"));
                assertEquals("true", ((Element) lines.item(0)).getAttribute("covered"));
            }
        } else {
            assertEquals(Map.of(2, freshMeasurement, 10, false, 11, false), coverage());
        }
    }

    private List<List<DBGUIExtCmdInfoBase>> separateFinalMeasures(int count) {
        List<List<DBGUIExtCmdInfoBase>> finals = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            finals.add(List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1)));
        }
        return finals;
    }

    @Test
    void stopKeepsMeasuresSentAfterProfilingIsOff() throws Exception {
        Future<Integer> start = start();
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void collectionCommandsAreRejectedWhileProfilingIsBeingDisabled() throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.pause("setMeasureMode");
        Future<Integer> stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));

        try {
            dbgs.awaitPaused("setMeasureMode");
            assertEquals(List.of(1, 1, 1), List.of(send(PipeMessages.CHECK_COMMAND),
                    send(PipeMessages.CLEAN_COMMAND), send(PipeMessages.DUMP_COMMAND)));
            assertFalse(Files.exists(output));
        } finally {
            dbgs.resume("setMeasureMode");
        }

        assertEquals(0, stop.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(0, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void collectionCommandsCannotOverwriteStoppedRawReport() throws Exception {
        Future<Integer> collector = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
        dbgs.pause("detachDebugUI");

        try {
            assertEquals(0, send(PipeMessages.EXIT_COMMAND));
            dbgs.awaitPaused("detachDebugUI");
            String reported = Files.readString(output);

            assertEquals(List.of(1, 1, 1), List.of(send(PipeMessages.CLEAN_COMMAND),
                    send(PipeMessages.DUMP_COMMAND), send(PipeMessages.CHECK_COMMAND)));
            assertEquals(reported, Files.readString(output));
        } finally {
            dbgs.resume("detachDebugUI");
        }

        assertEquals(0, collector.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void finalMeasureOfMissingModuleRejectsFractionalLineAndKeepsKnownHits() throws Exception {
        DBGUIExtCmdInfoMeasure measure = FakeDebugServer.measure(UUID.randomUUID().toString(), PROPERTY_ID, 3, 1);
        measure.getMeasure().getModuleData().get(0).getLineInfo().get(0).setLineNo(new BigDecimal("3.5"));
        assertInvalidUnmappedFinalMeasure(measure);
    }

    @Test
    void finalMeasureOfOtherExtensionRejectsNegativeHitsAndKeepsKnownHits() throws Exception {
        DBGUIExtCmdInfoMeasure measure = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "OtherExtension", 3, -1);
        assertInvalidUnmappedFinalMeasure(measure);
    }

    @Test
    void finalMeasureOfOtherExtensionRequiresModuleIdAndKeepsKnownHits() throws Exception {
        assertInvalidUnmappedFinalMeasure(FakeDebugServer.measure("", PROPERTY_ID, "OtherExtension", 3, 1));
    }

    private void assertInvalidUnmappedFinalMeasure(DBGUIExtCmdInfoMeasure measure) throws Exception {
        Future<Integer> collector = start();
        dbgs.deliverOnMeasureOff(measure, FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void unreadableDecimalInFinalPingPreservesSiblingLinesModulesAndCommands() throws Exception {
        Future<Integer> collector = start();
        dbgs.transformFinalPingXml(xml -> xml.replace(">98765<", ">NaN<").replace(">98766<", ">bad-line<"));
        DBGUIExtCmdInfoMeasure first = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 98765, 98766, 1, 10, 1);
        first.getMeasure().getModuleData().add(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 11, 1)
                .getMeasure().getModuleData().get(0));
        dbgs.deliverOnMeasureOff(first, FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 7, 1));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 7, true, 10, true, 11, true), coverage());
    }

    @Test
    void unreadableTotalDurationKeepsMeasuredHitsInRawOutput() throws Exception {
        Future<Integer> collector = startRaw();
        dbgs.transformFinalPingXml(xml -> xml.replace(">98765<", ">NaN<"));
        DBGUIExtCmdInfoMeasure first = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 3);
        first.getMeasure().setTotalDurability(BigDecimal.valueOf(98765));
        dbgs.deliverOnMeasureOff(first, FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 11, 7));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 11, true), coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
    }

    @Test
    void unreadableDurationInRejectedUsersPacketDoesNotDiscardAllowedUserHits() throws Exception {
        DebugTargetId allowed = user("suite");
        dbgs.setTargets(allowed);
        Future<Integer> collector = start("--include-user", "suite");
        dbgs.transformFinalPingXml(xml -> xml.replace(">98765<", ">NaN<"));
        DBGUIExtCmdInfoMeasure rejected = FakeDebugServer.measure(user("other"), OBJECT_ID, PROPERTY_ID, 2, 3);
        rejected.getMeasure().setTotalDurability(BigDecimal.valueOf(98765));
        dbgs.deliverOnMeasureOff(rejected, FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 11, 7));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, false, 11, true), coverage());
    }

    @Test
    void unreadableLineDurationKeepsItsValidLineNumberAndHits() throws Exception {
        Future<Integer> collector = start();
        dbgs.transformFinalPingXml(xml -> xml.replace(">98765<", ">NaN<"));
        DBGUIExtCmdInfoMeasure first = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 3);
        first.getMeasure().getModuleData().get(0).getLineInfo().get(0).setDurability(BigDecimal.valueOf(98765));
        dbgs.deliverOnMeasureOff(first, FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 11, 7));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, true), coverage());
    }

    @Test
    void unreadableStartedTargetDoesNotDiscardOtherFinalCommands() throws Exception {
        assertInvalidFinalTargetEventKeepsMeasures(true);
    }

    @Test
    void unreadableQuitTargetDoesNotDiscardOtherFinalCommands() throws Exception {
        assertInvalidFinalTargetEventKeepsMeasures(false);
    }

    private void assertInvalidFinalTargetEventKeepsMeasures(boolean starting) throws Exception {
        dbgs.setTargets();
        Future<Integer> collector = start();
        dbgs.transformFinalPingXml(xml -> xml.replace(">98765<", ">NaN<"));
        DebugTargetId target = user("suite");
        target.setSeanceNo(BigDecimal.valueOf(98765));
        DBGUIExtCmdInfoBase event = starting ? FakeDebugServer.targetStarted(target) : FakeDebugServer.targetQuit(target);
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1),
                event, FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 11, 1));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, true), coverage());
        assertTrue(dbgs.requests("attachDetachDbgTargets").isEmpty());
    }

    @Test
    void rawStopKeepsValidLinesWhenFinalMeasureHasNoLineNumber() throws Exception {
        Future<Integer> start = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        DBGUIExtCmdInfoMeasure measure = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1, 11, 1);
        measure.getMeasure().getModuleData().get(0).getLineInfo().get(0).setLineNo(null);
        dbgs.deliverOnMeasureOff(measure);

        int stopResult = send(PipeMessages.EXIT_COMMAND);
        int startResult = start.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(Map.of(2, true, 11, true), coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, startResult);
    }


    @Test
    void proxyPingsCannotConfirmFinalCollection() throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
        dbgs.sendProxyPings();

        assertEquals(1, send(PipeMessages.EXIT_COMMAND));
        assertEquals(1, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void oneProxyReplyDuringFinalCollectionKeepsFailureAfterDebuggerRecovers() throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));

        dbgs.pause("pingDebugUIParams");
        dbgs.pause("setMeasureMode");
        Future<Integer> stop;
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            dbgs.awaitPaused("setMeasureMode");
            dbgs.sendProxyReplyOnNextPing();
            dbgs.queueEvents(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
            dbgs.resume("pingDebugUIParams");
            dbgs.awaitEventProcessing();
            dbgs.fail("pingDebugUIParams", 204);
        } finally {
            dbgs.resume("pingDebugUIParams");
            dbgs.resume("setMeasureMode");
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void stopRejectsConcatenatedPingDocumentsAndKeepsOtherMeasurements() throws Exception {
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.pause("pingDebugUIParams");
        Future<Integer> stop;
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            waitForFinalMeasureThread();
            dbgs.deliverAcrossFinalPolls(List.of(List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 7, 1)),
                    List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1))));
            dbgs.sendConcatenatedXmlOnNextPing();
        } finally {
            dbgs.resume("pingDebugUIParams");
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void finalCollectionKeepsLaterMeasurementsAfterOneFailedPing() throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.pause("pingDebugUIParams");

        Future<Integer> stop;
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            waitForFinalMeasureThread();
            dbgs.failNextPing(503);
            dbgs.queueEvents(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
        } finally {
            dbgs.resume("pingDebugUIParams");
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void liveCollectionContinuesAfterOneProxyReply() throws Exception {
        Future<Integer> collector = start();
        dbgs.pause("pingDebugUIParams");
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            dbgs.sendProxyReplyOnNextPing();
            dbgs.queueEvents(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
        } finally {
            dbgs.resume("pingDebugUIParams");
        }
        dbgs.awaitEventProcessing();

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void finalMeasurementCommandWithWrongPayloadTypePreservesAdjacentMeasurements() throws Exception {
        Future<Integer> collector = start();
        var wrongPayload = FakeDebugServer.targetStarted(UUID.randomUUID());
        wrongPayload.setCmdID(DBGUIExtCmds.MEASURE_RESULT_PROCESSING);
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1), wrongPayload,
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void finalMeasurementPayloadWithWrongCommandIdPreservesAdjacentMeasurements() throws Exception {
        Future<Integer> collector = start();
        var wrongCommand = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 11, 1);
        wrongCommand.setCmdID(DBGUIExtCmds.TARGET_STARTED);
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1), wrongCommand,
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void stopRejectsMismatchedFinalTargetCommandAndKeepsValidHits() throws Exception {
        var wrongModel = FakeDebugServer.targetQuit(user("synthetic_user"));
        wrongModel.setCmdID(DBGUIExtCmds.TARGET_STARTED);
        assertStopRejectsInvalidFinalCommand(wrongModel);
    }

    @Test
    void stopRejectsStartedPayloadForFinalTargetQuitAndKeepsValidHits() throws Exception {
        var wrongModel = FakeDebugServer.targetStarted(user("synthetic_user"));
        wrongModel.setCmdID(DBGUIExtCmds.TARGET_QUIT);
        assertStopRejectsInvalidFinalCommand(wrongModel);
    }

    @Test
    void stopRejectsStartedPayloadForUnrelatedFinalCommandAndKeepsValidHits() throws Exception {
        var wrongModel = FakeDebugServer.targetStarted(user("synthetic_user"));
        wrongModel.setCmdID(DBGUIExtCmds.CORRECTED_BP);
        assertStopRejectsInvalidFinalCommand(wrongModel);
    }

    @Test
    void stopRejectsQuitPayloadForUnrelatedFinalCommandAndKeepsValidHits() throws Exception {
        var wrongModel = FakeDebugServer.targetQuit(user("synthetic_user"));
        wrongModel.setCmdID(DBGUIExtCmds.CORRECTED_BP);
        assertStopRejectsInvalidFinalCommand(wrongModel);
    }

    @Test
    void stopRejectsFinalDebuggerCommandWithoutIdAndKeepsValidHits() throws Exception {
        assertStopRejectsInvalidFinalCommand(CommandsFactory.eINSTANCE.createDBGUIExtCmdInfoCorrectedBP());
    }

    @Test
    void stopRejectsFinalDebuggerCommandWithUnknownIdAndKeepsValidHits() throws Exception {
        var command = CommandsFactory.eINSTANCE.createDBGUIExtCmdInfoCorrectedBP();
        command.setCmdID(DBGUIExtCmds.UNKNOWN);
        assertStopRejectsInvalidFinalCommand(command);
    }

    @Test
    void unknownLiveDebuggerCommandsDoNotPoisonHealthyStop() throws Exception {
        Future<Integer> collector = start();
        var missingId = CommandsFactory.eINSTANCE.createDBGUIExtCmdInfoCorrectedBP();
        var unknownId = CommandsFactory.eINSTANCE.createDBGUIExtCmdInfoCorrectedBP();
        unknownId.setCmdID(DBGUIExtCmds.UNKNOWN);
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1), missingId, unknownId,
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void stopIgnoresUnrelatedFinalDebuggerCommandAndKeepsValidHits() throws Exception {
        Future<Integer> collector = start();
        var breakpoint = CommandsFactory.eINSTANCE.createDBGUIExtCmdInfoCorrectedBP();
        breakpoint.setCmdID(DBGUIExtCmds.CORRECTED_BP);
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1), breakpoint,
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 11, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, true), coverage());
    }

    @Test
    void stopRejectsFinalTargetStartedWithoutTargetAndKeepsValidHits() throws Exception {
        var event = FakeDebugServer.targetStarted(user("synthetic_user"));
        event.setTargetID(null);
        assertStopRejectsInvalidFinalCommand(event);
    }

    @Test
    void stopRejectsFinalTargetStartedWithoutIdAndKeepsValidHits() throws Exception {
        assertStopRejectsInvalidFinalCommand(FakeDebugServer.targetStarted(targetWithId(null)));
    }

    @Test
    void stopRejectsFinalTargetStartedWithEmptyIdAndKeepsValidHits() throws Exception {
        assertStopRejectsInvalidFinalCommand(FakeDebugServer.targetStarted(targetWithId("")));
    }

    @Test
    void stopRejectsFinalTargetStartedWithAbbreviatedIdAndKeepsValidHits() throws Exception {
        assertStopRejectsInvalidFinalCommand(FakeDebugServer.targetStarted(abbreviatedTarget("synthetic_user")));
    }

    @Test
    void stopRejectsFinalTargetQuitWithoutTargetAndKeepsValidHits() throws Exception {
        var event = FakeDebugServer.targetQuit(user("synthetic_user"));
        event.setTargetID(null);
        assertStopRejectsInvalidFinalCommand(event);
    }

    @Test
    void stopRejectsFinalTargetQuitWithoutIdAndKeepsValidHits() throws Exception {
        assertStopRejectsInvalidFinalCommand(FakeDebugServer.targetQuit(targetWithId(null)));
    }

    @Test
    void stopRejectsFinalTargetQuitWithEmptyIdAndKeepsValidHits() throws Exception {
        assertStopRejectsInvalidFinalCommand(FakeDebugServer.targetQuit(targetWithId("")));
    }

    @Test
    void stopRejectsFinalTargetQuitWithAbbreviatedIdAndKeepsValidHits() throws Exception {
        assertStopRejectsInvalidFinalCommand(FakeDebugServer.targetQuit(abbreviatedTarget("synthetic_user")));
    }

    @Test
    void malformedLiveTargetEventsDoNotPoisonHealthyStop() throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.targetStarted(targetWithId(null)),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1),
                FakeDebugServer.targetQuit(abbreviatedTarget("synthetic_user")),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    private void assertStopRejectsInvalidFinalCommand(DBGUIExtCmdInfoBase command) throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.deliverAcrossFinalPolls(List.of(List.of(
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1), command,
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 11, 1))));

        int stopResult = send(PipeMessages.EXIT_COMMAND);
        int collectorResult = collector.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(Map.of(2, true, 10, true, 11, true), coverage());
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, collectorResult);
    }

    @Test
    void malformedMeasurementInFlightWhenStopBeginsFailsWhileSavingGoodMeasurements() throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        DBGUIExtCmdInfoMeasure mixed = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 4, 1);
        mixed.getMeasure().getModuleData().getFirst().setModuleID(null);
        mixed.getMeasure().getModuleData().add(
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 7, 1).getMeasure().getModuleData().getFirst());

        dbgs.pause("pingDebugUIParams");
        dbgs.pause("setMeasureMode");
        Future<Integer> stop;
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            dbgs.awaitPaused("setMeasureMode");
            dbgs.queueEvents(mixed, FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
            dbgs.resume("pingDebugUIParams");
            dbgs.awaitEventProcessing();
        } finally {
            dbgs.resume("pingDebugUIParams");
            dbgs.resume("setMeasureMode");
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 7, true, 10, true, 11, false), coverage());
    }

    @Test
    void stopRejectsProxyAcknowledgementOfProfilingDisableAndPreservesFinalHits() throws Exception {
        assertStopRejectsProfilingDisableAcknowledgement(dbgs::sendProxyReplyWhenProfilingIsDisabled);
    }

    @Test
    void stopRejectsMissingDeflateAcknowledgementAndPreservesFinalHits() throws Exception {
        assertStopRejectsProfilingDisableAcknowledgement(
                () -> dbgs.sendMissingCompressedReplyWhenProfilingIsDisabled("deflate"));
    }

    @Test
    void stopRejectsMissingGzipAcknowledgementAndPreservesFinalHits() throws Exception {
        assertStopRejectsProfilingDisableAcknowledgement(
                () -> dbgs.sendMissingCompressedReplyWhenProfilingIsDisabled("gzip"));
    }

    @Test
    void stopKeepsQueuedFinalHitWhenRetryIntervalExceedsWindow() throws Exception {
        assertStopWithFinalPingReply(FakeDebugServer.FinalPingReply.UNAVAILABLE, "3000", CommandLine.ExitCode.SOFTWARE);
    }

    @Test
    void stopRejectsCorruptGzipFinalPingAndKeepsLaterHits() throws Exception {
        assertStopWithFinalPingReply(FakeDebugServer.FinalPingReply.CORRUPT_GZIP, "10", CommandLine.ExitCode.SOFTWARE);
    }

    @Test
    void stopAcceptsValidGzipFinalPingAndKeepsLaterHits() throws Exception {
        assertStopWithFinalPingReply(FakeDebugServer.FinalPingReply.GZIP, "10", CommandLine.ExitCode.OK);
    }

    private void assertStopWithFinalPingReply(FakeDebugServer.FinalPingReply reply, String pingTimeout, int exitCode)
            throws Exception {
        Future<Integer> start = startWithPingTimeout(startCommandLine(), pingTimeout);
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
        dbgs.replyToFirstFinalPingWith(reply);

        assertEquals(exitCode, send(PipeMessages.EXIT_COMMAND));
        assertEquals(exitCode, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    private void assertStopRejectsProfilingDisableAcknowledgement(Runnable setInvalidReply) throws Exception {
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.pause("pingDebugUIParams");
        Future<Integer> stop;
        try {
            dbgs.awaitPaused("pingDebugUIParams");
            dbgs.deliverOnMeasureOff(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
            setInvalidReply.run();
            stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            waitForFinalMeasureThreadOrStopCompletion(stop);
        } finally {
            dbgs.resume("pingDebugUIParams");
        }

        int stopResult = stop.get(TIMEOUT, TimeUnit.SECONDS);
        int startResult = start.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, startResult);
    }

    @Test
    void stopFailsAndWritesPartialCoverageWhenProfilingCannotBeDisabled() throws Exception {
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.fail("setMeasureMode", 503);

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void stopFailsAndWritesPartialCoverageWhenFinalMeasuresTimeOut() throws Exception {
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.pause("pingDebugUIParams");

        try {
            dbgs.awaitPaused("pingDebugUIParams");
            dbgs.deliverOnMeasureOff(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
            assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
        } finally {
            dbgs.resume("pingDebugUIParams");
        }
        assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void interruptedFinalMeasureWaitFailsStopAndWritesPartialCoverage() throws Exception {
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.pause("pingDebugUIParams");

        try {
            dbgs.awaitPaused("pingDebugUIParams");
            Future<Integer> stop = executor.submit(() -> send(PipeMessages.EXIT_COMMAND));
            Thread waiter = waitForFinalMeasureThread();
            waiter.interrupt();
            assertEquals(CommandLine.ExitCode.SOFTWARE, stop.get(TIMEOUT, TimeUnit.SECONDS));
        } finally {
            dbgs.resume("pingDebugUIParams");
        }
        assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    private Thread waitForFinalMeasureThread() throws InterruptedException {
        return waitForFinalMeasureThreadOrStopCompletion(null);
    }

    private Thread waitForFinalMeasureThreadOrStopCompletion(Future<Integer> stop) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() - deadline < 0) {
            if (stop != null && stop.isDone()) {
                return null;
            }
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                boolean waiting = Arrays.stream(entry.getValue())
                        .anyMatch(frame -> frame.getClassName().equals("com.clouds42.Commands.CoverageCommand")
                                && frame.getMethodName().equals("awaitFinalMeasures"));
                if (waiting && entry.getKey().getState() == Thread.State.TIMED_WAITING) {
                    return entry.getKey();
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("stop did not wait for final measures");
    }

    @Test
    void stateChangingCommandsFailUntilProfilingIsReady() throws Exception {
        dbgs.pause("setMeasureMode", body -> body.contains("measureModeSeanceID"));
        Future<Integer> start = executor.submit(() -> startCommandLine().execute(PipeMessages.START_COMMAND,
                "-i", infobase, "-u", dbgs.url(), "-o", output.toString(), "-t", "10"));
        try {
            dbgs.awaitPaused("setMeasureMode");
            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.DUMP_COMMAND));
            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.CLEAN_COMMAND));
            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
            assertFalse(Files.exists(output));
        } finally {
            dbgs.resume("setMeasureMode");
        }
        awaitStarted(start);
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void hitsAccumulateAcrossMeasures() throws Exception {
        Future<Integer> start = start("-f", "LCOV");

        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 3));
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 4, 7, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        List<String> lcov = Files.readAllLines(output);
        assertTrue(lcov.contains("DA:2,7"), lcov.toString());
        assertTrue(lcov.contains("DA:7,1"), lcov.toString());
    }

    @Test
    void dumpWritesCoverageWhileMeasuring() throws Exception {
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.DUMP_COMMAND));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());

        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void cleanForgetsHitsAndKeepsAutoLinesOptional() throws Exception {
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1, 7, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.CLEAN_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void cleanDropsRawRecordsFromPreviouslyMeasuredSources() throws Exception {
        Future<Integer> start = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "ExtensionA", 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.CLEAN_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(Files.readString(output).contains("ExtensionA"));
    }

    @Test
    void statsPrintsReportToTheCollectorOutput() throws Exception {
        Future<Integer> start = start();
        String report = captureStatistics(() -> assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.STATS_COMMAND)));
        assertTrue(report.contains("Lines to cover"));
        assertTrue(report.contains("Module.bsl"));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void statsPrintsZeroCountersBeforeAnyRawMeasurements() throws Exception {
        Future<Integer> start = startRaw();
        String report = captureStatistics(() -> assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.STATS_COMMAND)));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));

        assertTrue(report.contains("Lines to cover"), report);
        assertTrue(report.contains("Covered lines"), report);
        assertTrue(report.lines().anyMatch(line -> line.matches(".*Total\\W+0\\W+0\\W+0\\W*")), report);
    }

    @Test
    void statsReturnsErrorUntilProfilingIsReady() throws Exception {
        dbgs.pause("setMeasureMode", body -> body.contains("measureModeSeanceID"));
        Future<Integer> start = executor.submit(() -> startCommandLine().execute(PipeMessages.START_COMMAND,
                "-i", infobase, "-u", dbgs.url(), "-o", output.toString(), "-t", "10"));
        int startingResult;
        try {
            dbgs.awaitPaused("setMeasureMode");
            startingResult = send(PipeMessages.STATS_COMMAND);
        } finally {
            dbgs.resume("setMeasureMode");
        }
        awaitStarted(start);
        int readyResult = send(PipeMessages.STATS_COMMAND);
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));

        assertEquals(CommandLine.ExitCode.SOFTWARE, startingResult);
        assertEquals(CommandLine.ExitCode.OK, readyResult);
    }

    @Test
    void hitsOfModuleExcludedByConfigAreIgnored() throws Exception {
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"exclude\":{\"objects\":[\"CommonModule.ПростойОбщийМодуль\"]}}");
        Future<Integer> start = start("--config", config.toString());
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(null, coverage());
    }

    @Test
    void changeBlockOfModuleOutOfScopeTakesOnlyItsHits() throws Exception {
        Future<Integer> start = startWithChangeBlock();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1, 4, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(4, true), coverage());
    }

    @Test
    void cleanKeepsChangeBlockOfModuleOutOfScope() throws Exception {
        Future<Integer> start = startWithChangeBlock();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 4, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.CLEAN_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(4, false), coverage());
    }

    @Test
    void prefixedMethodOfModuleOutOfScopeTakesOnlyItsHits() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), "Процедура А() Экспорт\n\tТиповой();\nКонецПроцедуры\n\n"
                + "Процедура тестБ() Экспорт\n\tНаш();\nКонецПроцедуры\n");
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"includePrefix\":[\"тест\"],\"prefixScope\":{\"routines\":true}}");
        Future<Integer> start = start("--config", config.toString());
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1, 6, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(6, true, 7, false), coverage());
    }

    // ПростойОбщийМодуль вне охвата ПодчиненнаяПодсистема; блок доработки — строка 4.
    private Future<Integer> startWithChangeBlock() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE),
                "Процедура А() Экспорт\n\tТиповой();\n\t// ++ уч\n\tНаш();\n\t// -- уч\nКонецПроцедуры\n");
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"includeSubsystems\":[\"ПодчиненнаяПодсистема\"],\"changeMarkers\":{}}");
        return start("--config", config.toString());
    }

    @Test
    void hitsOfUnknownModuleOrOtherExtensionAreIgnored() throws Exception {
        Future<Integer> start = start("-e", "");
        dbgs.deliver(
                FakeDebugServer.measure(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2, 1),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "Расширение1", 2, 1),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 99, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void attachesTargetsAtStartAndWhenTheyStart() throws Exception {
        Future<Integer> start = start();
        int attachedAtStart = dbgs.requests("attachDetachDbgTargets").size();
        UUID target = UUID.randomUUID();

        dbgs.deliver(FakeDebugServer.targetStarted(target));

        assertEquals(1, attachedAtStart);
        List<FakeDebugServer.Request> attaches = dbgs.requests("attachDetachDbgTargets");
        assertEquals(2, attaches.size());
        assertTrue(attaches.get(1).body.contains(target.toString()));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void rawModeKeepsHitsByModuleIds() throws Exception {
        Future<Integer> start = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 5, 2));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(5, true), coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
    }

    @Test
    void rawLiveMeasureWithoutLineNumberKeepsValidLines() throws Exception {
        Future<Integer> collector = startRaw();
        dbgs.deliver(measureWithMissingLineNumber(7));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(7, true), coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
    }

    @Test
    void rawFinalMeasureWithoutLineNumberFailsAndKeepsValidLines() throws Exception {
        Future<Integer> collector = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.deliverOnMeasureOff(measureWithMissingLineNumber(10));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true), coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
    }

    private static DBGUIExtCmdInfoMeasure measureWithMissingLineNumber(int validLine) {
        DBGUIExtCmdInfoMeasure packet = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 4, 1, validLine, 1);
        packet.getMeasure().getModuleData().getFirst().getLineInfo().getFirst().setLineNo(null);
        return packet;
    }

    @Test
    void rawFinalMeasurementRejectsInvalidModuleKeysAndStillConverts() throws Exception {
        Future<Integer> collector = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));

        var mixed = FakeDebugServer.measure("", PROPERTY_ID, 4, 1);
        mixed.getMeasure().getModuleData().add(
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1).getMeasure().getModuleData().getFirst());
        dbgs.deliverOnMeasureOff(mixed,
                FakeDebugServer.measure(OBJECT_ID, "", 11, 1),
                FakeDebugServer.measure(" ", PROPERTY_ID, "SampleExtension", 11, 1),
                FakeDebugServer.measure(OBJECT_ID, "nested/property", 11, 1),
                FakeDebugServer.measure("escaped%2Fobject", PROPERTY_ID, 11, 1));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));

        Path converted = tempDir.resolve("converted.xml");
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), String.join("\n", MARKED_MODULE));
        assertEquals(CommandLine.ExitCode.OK, Coverage41C.getCommandLine().execute("convert",
                "-P", project.toString(), "-s", "configuration", "-c", output.toString(), "-o", converted.toString()));
        assertEquals(Map.of(2, true, 10, true, 11, false), MultipleSourcesTest.coverage(converted).get(MODULE));
        NodeList files = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
                .getElementsByTagName("file");
        assertEquals(1, files.getLength());
    }

    @Test
    void rawMeasurementKeepsNonUuidModuleKeys() throws Exception {
        Future<Integer> collector = startRaw();
        dbgs.deliver(FakeDebugServer.measure("sample-object_v1", "module.2", 6, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(6, true), coverage("/sample-object_v1/module.2"));
    }

    @Test
    void decimalScaleDoesNotChangeMeasuredLineIdentity() throws Exception {
        Future<Integer> collector = start();
        var packet = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1);
        packet.getMeasure().getModuleData().getFirst().getLineInfo().getFirst().setLineNo(new BigDecimal("2.00"));
        dbgs.deliver(packet);

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void convertUsesCanonicalLineNumbersFromRawReports() throws Exception {
        Path input = tempDir.resolve("scaled.xml");
        Files.writeString(input, """
                <coverage version="1" sourceIdentity="true">
                  <file path="/%s/%s"><lineToCover covered="true" lineNumber="2.00"/></file>
                </coverage>
                """.formatted(OBJECT_ID, PROPERTY_ID));
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), String.join("\n", MARKED_MODULE));

        assertEquals(CommandLine.ExitCode.OK, Coverage41C.getCommandLine().execute("convert",
                "-P", project.toString(), "-s", "configuration", "-c", input.toString(), "-o", output.toString()));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void malformedFinalLineNumbersAndCountsLeaveAdjacentRawHits() throws Exception {
        Future<Integer> collector = startRaw();
        var packet = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1, 4, 1, 7, 1, 8, 1, 10, 1);
        var lines = packet.getMeasure().getModuleData().getFirst().getLineInfo();
        lines.get(1).setFrequency(new BigDecimal("0.5"));
        lines.get(2).setFrequency(BigDecimal.valueOf(-3));
        lines.get(3).setLineNo(new BigDecimal("8.5"));
        dbgs.deliverOnMeasureOff(packet);

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true), coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
    }

    @Test
    void largeHitFrequenciesSaturateInsteadOfWrapping() throws Exception {
        Future<Integer> collector = start("-f", "LCOV");
        var packet = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1, 10, Integer.MAX_VALUE);
        packet.getMeasure().getModuleData().getFirst().getLineInfo().getFirst()
                .setFrequency(new BigDecimal("4294967296.0"));
        dbgs.deliver(packet, FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        String lcov = Files.readString(output);
        assertTrue(lcov.contains("DA:2,2147483647"), lcov);
        assertTrue(lcov.contains("DA:10,2147483647"), lcov);
    }

    @Test
    void rawModeSeparatesSourcesWithTheSameModuleIds() throws Exception {
        Future<Integer> start = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "A", 4, 1),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "Б", 7, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));

        String suffix = "/" + OBJECT_ID + "/" + PROPERTY_ID;
        assertEquals(Map.of(2, true), coverage(suffix, ""));
        assertEquals(Map.of(4, true), coverage(suffix, "A"));
        assertEquals(Map.of(7, true), coverage(suffix, "Б"));
    }

    @Test
    void directAndDeferredCoverageAgreeForProcessorWithExtension() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(Path.of("src/test/resources/externalprocessor/configurator"), project);
        TestUtils.copyDirectory(TestUtils.CONFIGURATION, project.resolve("A"));
        Files.writeString(project.resolve("A/" + MultipleSourcesTest.MODULE), MultipleSourcesTest.TEXT);
        String url = "file:///ВнешняяОбработка.epf";
        DBGUIExtCmdInfoMeasure processor = FakeDebugServer.measure(
                "8fe68a1c-d99b-4417-9ee7-27dfaf75ded0", "a637f77f-3840-441d-a1c3-699c8c5cb7e0", 2, 1);
        processor.getMeasure().getModuleData().getFirst().getModuleID().setURL(url);
        DBGUIExtCmdInfoMeasure extension = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "A", 4, 1);
        List<String> sources = List.of("-P", project.toString(), "-s", "ВнешняяОбработка.xml",
                "-x", url, "--extension", "A=A");
        List<String> startArgs = new ArrayList<>(List.of("start", "-i", infobase, "-u", dbgs.url(),
                "-o", output.toString(), "-t", "10"));
        startArgs.addAll(sources);

        Future<Integer> start = submit(startArgs);
        dbgs.deliver(processor, extension);
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));
        Map<String, Map<Integer, Boolean>> direct = MultipleSourcesTest.coverage(output);
        assertEquals(Map.of(2, true, 3, false), direct.get("ВнешняяОбработка/Ext/ObjectModule.bsl"));
        assertEquals(Map.of(2, false, 4, true, 6, false), direct.get("A/" + MultipleSourcesTest.MODULE));

        Future<Integer> raw = startRaw();
        dbgs.deliver(processor, extension);
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, raw.get(TIMEOUT, TimeUnit.SECONDS));

        Path converted = tempDir.resolve("converted.xml");
        List<String> convertArgs = new ArrayList<>(List.of("convert", "-c", output.toString(),
                "-o", converted.toString()));
        convertArgs.addAll(sources);
        assertEquals(0, Coverage41C.getCommandLine().execute(convertArgs.toArray(String[]::new)));
        assertEquals(direct, MultipleSourcesTest.coverage(converted));
    }

    @Test
    void rawFromMainDoesNotCoverAnExtensionWithTheSameModuleId() throws Exception {
        Future<Integer> start = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));

        Path converted = tempDir.resolve("converted.xml");
        Path project = TestUtils.copyConfiguration(tempDir);
        assertEquals(1, Coverage41C.getCommandLine().execute("convert", "-P", project.toString(),
                "-s", "configuration", "-e", "DemoExtension", "-c", output.toString(), "-o", converted.toString()));
        assertFalse(Files.exists(converted));
    }

    @Test
    void rawFromSeveralSourcesKeepsOnlyMainHitsWhenNoExtensionIsSelected() throws Exception {
        Future<Integer> start = startRaw();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "DemoExtension", 10, 1));
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));

        Path converted = tempDir.resolve("converted.xml");
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), String.join("\n", MARKED_MODULE));
        assertEquals(0, Coverage41C.getCommandLine().execute("convert", "-P", project.toString(),
                "-s", "configuration", "-c", output.toString(), "-o", converted.toString()));
        assertEquals(Map.of(2, true, 10, false, 11, false),
                MultipleSourcesTest.coverage(converted).get(MODULE));
    }

    @Test
    void legacyStartUsesNestedSourceRelativeToProjectAndAppliesConfiguration() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.createDirectories(project.resolve("src_folder"));
        Files.move(project.resolve("configuration"), project.resolve("src_folder/main"));
        Files.writeString(project.resolve("src_folder/main/" + MultipleSourcesTest.MODULE), MultipleSourcesTest.TEXT);
        Path config = project.resolve("tools/JSON/coverage41c.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"exclude\":{\"paths\":[\"src_folder/main/"
                + MultipleSourcesTest.MODULE + "\"]},\"changeMarkers\":{}}");
        Future<Integer> start = submit(List.of("start", "-i", infobase, "-u", dbgs.url(),
                "-s", "src_folder/main", "-P", project.toString(), "-r", "NOT_EDITABLE",
                "--config", config.toString(), "-o", output.toString(), "-t", "10"));
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1, 4, 1));

        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(4, true), coverage("src_folder/main/" + MultipleSourcesTest.MODULE));
        assertFalse(MultipleSourcesTest.coverage(output).containsKey(MODULE));
    }

    @Test
    void directAndDeferredCoverageAgreeWithNamedSources() throws Exception {
        assertDirectAndDeferredCoverageAgree(List.of("--source", "=configuration",
                "--source", "A=extensions/A", "--source", "Б=extensions/Б"));
    }

    @Test
    void directAndDeferredCoverageAgreeWithExtensions() throws Exception {
        assertDirectAndDeferredCoverageAgree(List.of("-s", "configuration",
                "--extension", "A=extensions/A", "--extension", "Б=extensions/Б"));
    }

    private void assertDirectAndDeferredCoverageAgree(List<String> sources) throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        TestUtils.copyDirectory(TestUtils.CONFIGURATION, project.resolve("extensions/A"));
        TestUtils.copyDirectory(TestUtils.CONFIGURATION, project.resolve("extensions/Б"));
        Files.writeString(project.resolve(MODULE), MultipleSourcesTest.TEXT);
        Files.writeString(project.resolve("extensions/A/" + MultipleSourcesTest.MODULE), MultipleSourcesTest.TEXT);
        Files.writeString(project.resolve("extensions/Б/" + MultipleSourcesTest.MODULE), MultipleSourcesTest.TEXT);
        Path config = tempDir.resolve("coverage.json");
        Files.writeString(config, "{\"exclude\":{\"objects\":[\"CommonModule.ПростойОбщийМодуль\"]},\"changeMarkers\":{}}");
        List<String> startArgs = new ArrayList<>(List.of("start", "-i", infobase, "-u", dbgs.url(),
                "-P", project.toString(), "--config", config.toString(), "-o", output.toString(), "-t", "10"));
        startArgs.addAll(sources);
        Future<Integer> direct = submit(startArgs);
        deliverSelectedAndUnselectedSources();
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, direct.get(TIMEOUT, TimeUnit.SECONDS));
        Map<String, Map<Integer, Boolean>> expected = MultipleSourcesTest.coverage(output);
        assertEquals(Map.of(4, true), expected.get(MODULE));
        assertEquals(Map.of(4, false), expected.get("extensions/A/" + MultipleSourcesTest.MODULE));
        assertEquals(Map.of(4, true), expected.get("extensions/Б/" + MultipleSourcesTest.MODULE));

        Future<Integer> raw = startRaw();
        deliverSelectedAndUnselectedSources();
        assertEquals(0, send(PipeMessages.EXIT_COMMAND));
        assertEquals(0, raw.get(TIMEOUT, TimeUnit.SECONDS));
        Path converted = tempDir.resolve("converted.xml");
        List<String> convertArgs = new ArrayList<>(List.of("convert", "-P", project.toString(),
                "--config", config.toString(), "-c", output.toString(), "-o", converted.toString()));
        convertArgs.addAll(sources);
        assertEquals(0, Coverage41C.getCommandLine().execute(convertArgs.toArray(String[]::new)));
        assertEquals(expected, MultipleSourcesTest.coverage(converted));
    }

    private void deliverSelectedAndUnselectedSources() throws Exception {
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 4, 2),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "A", 2, 3),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "Б", 4, 1),
                FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, "Other", 4, 1));
    }

    @Test
    void brokenMeasureDoesNotStopMeasuring() throws Exception {
        Future<Integer> start = start();
        DBGUIExtCmdInfoMeasure broken = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1);
        broken.getMeasure().getModuleData().get(0).setModuleID(null);

        dbgs.deliver(broken);
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void lostDebugServerStillLeavesCoverage() throws Exception {
        Future<Integer> start = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));

        dbgs.close();

        assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void terminalPingFailureClosesCommandsBeforeSavingRawCoverage() throws Exception {
        Future<Integer> start = submit(List.of(PipeMessages.START_COMMAND, "-i", infobase, "-u", dbgs.url(),
                "-o", output.toString(), "-t", "3000"));
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        CountDownLatch pingFailure = new CountDownLatch(1);
        PrintStream stderr = System.err;
        int checkResult;
        int cleanResult;
        int stopResult;
        try (PrintStream log = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8) {
            @Override
            public void println(String line) {
                super.println(line);
                if (line.contains("Can't send ping to debug server")) {
                    pingFailure.countDown();
                }
            }
        }) {
            System.setErr(log);
            dbgs.failNextPing(503);
            assertTrue(pingFailure.await(TIMEOUT, TimeUnit.SECONDS), "Collector did not report the terminal ping failure");
            checkResult = send(PipeMessages.CHECK_COMMAND);
            cleanResult = send(PipeMessages.CLEAN_COMMAND);
            stopResult = send(PipeMessages.EXIT_COMMAND);
        } finally {
            System.setErr(stderr);
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.SOFTWARE, checkResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, cleanResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(Map.of(2, true), coverage("/" + OBJECT_ID + "/" + PROPERTY_ID));
    }

    @Test
    void secondStartForSameInfobaseFails() throws Exception {
        Future<Integer> first = start();
        StringWriter stderr = new StringWriter();
        CommandLine second = Coverage41C.getCommandLine();
        second.setErr(new PrintWriter(stderr, true));

        int exitCode = second.execute(PipeMessages.START_COMMAND, "-i", infobase, "-u", dbgs.url(),
                "-P", tempDir.resolve("project").toString(), "-s", "configuration", "-o", output.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        if (OS.current() != OS.WINDOWS) {
            assertTrue(stderr.toString().contains("Coverage41C is already running"), stderr.toString());
        }
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, first.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void brokenConfigReleasesSocket() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"exclude\":{\"lines\":[\"(\"]}}");
        StringWriter stderr = new StringWriter();
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));

        int exitCode = commandLine.execute(PipeMessages.START_COMMAND, "-i", infobase, "-u", dbgs.url(),
                "-P", project.toString(), "-s", "configuration", "-o", output.toString(), "--config", config.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("invalid regular expression"), stderr.toString());
        assertFalse(Files.exists(Path.of(Utils.getPipeName(connectionOptions()))));
        assertFalse(dbgs.commands().contains("attachDebugUI"));
    }

    @Test
    void verboseLogShowsExecutedLineThatIsNotToCover() throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            Future<Integer> start = start("--verbose");
            dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 1, 1));
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
            assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        } finally {
            System.setErr(originalErr);
        }

        String output = log.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Can't find line to cover 1 in module"), output);
        assertTrue(output.contains(">>> Процедура А() Экспорт"), output);
    }

    @Test
    void unknownCommandIsSkipped() throws Exception {
        Future<Integer> start = start();

        String pipe = Utils.getPipeName(connectionOptions());
        try (Socket socket = OS.current() == OS.WINDOWS ? new Win32NamedPipeSocket(pipe) : new UnixDomainSocket(pipe)) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out.println("hello");
            out.println(" STATS ");
            assertEquals(PipeMessages.OK_RESULT, in.readLine());
        }

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void startsAgainAfterStop() throws Exception {
        Set<Thread> previous = Thread.getAllStackTraces().keySet();
        Future<Integer> first = start();
        Thread listener = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("coverage41c-commands") && !previous.contains(thread))
                .findFirst().orElseThrow();
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, first.get(TIMEOUT, TimeUnit.SECONDS));

        if (OS.current() != OS.WINDOWS) {
            listener.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(listener.isAlive(), "Unix listener is still accepting commands after stop");
        }

        Future<Integer> second = start();

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, second.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void startsOverSocketLeftByKilledProcess() throws Exception {
        Path pipe = Path.of(Utils.getPipeName(connectionOptions()));
        new org.scalasbt.ipcsocket.UnixDomainServerSocket(pipe.toString()).close();
        assertTrue(Files.exists(pipe));

        Future<Integer> start = start();

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shutdownPreservesAFileThatReplacedItsSocket() throws Exception {
        Path pipe = Path.of(Utils.getPipeName(connectionOptions()));
        Path movedSocket = pipe.resolveSibling(pipe.getFileName() + ".moved");
        Future<Integer> start = start();
        Files.move(pipe, movedSocket);
        Files.writeString(pipe, "replacement");

        try {
            dbgs.close();
            assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
            assertEquals("replacement", Files.readString(pipe));
        } finally {
            Files.deleteIfExists(pipe);
            Files.deleteIfExists(movedSocket);
        }
    }

    @Test
    void stopFailsWhenCoverageFileCannotBeWritten() throws Exception {
        output = tempDir.resolve("missing").resolve("coverage.xml");
        Future<Integer> start = start();

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));

        assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void dumpFailsWhenCoverageFileCannotBeWritten() throws Exception {
        output = tempDir.resolve("missing").resolve("coverage.xml");
        Future<Integer> start = start();

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.DUMP_COMMAND));

        Files.createDirectories(output.getParent());
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void readsCompressedResponses() throws Exception {
        dbgs.setCompressResponses(true);
        Future<Integer> start = start();

        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void debugAreasLimitTargets() throws Exception {
        Future<Integer> start = start("-n", "Область1", "Область2");

        List<FakeDebugServer.Request> targets = dbgs.requests("getDbgTargets");
        assertEquals(2, targets.size());
        assertTrue(targets.get(0).body.contains("Область1"), targets.get(0).body);
        assertTrue(targets.get(1).body.contains("Область2"), targets.get(1).body);
        assertTrue(dbgs.requests("setAutoAttachSettings").get(0).body.contains("Область2"));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void oldPlatformDoesNotAutoconnectMobileClient() throws Exception {
        dbgs.setApiVersion("8.3.15");
        Future<Integer> start = start();

        String settings = dbgs.requests("setAutoAttachSettings").get(0).body;
        assertFalse(settings.contains(">" + DebugTargetType.MOBILE_MANAGED_CLIENT.getLiteral() + "<"), settings);
        assertTrue(settings.contains(">" + DebugTargetType.SERVER.getLiteral() + "<"), settings);
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
    }

    @Test
    void startFailsWhenDebugServerAnswersWithError() throws Exception {
        dbgs.fail("attachDebugUI", 503);

        assertStartFails("status: 503");
    }

    @Test
    void failedProfilingEnableAcknowledgementDisablesProfilingAndDetaches() throws Exception {
        assertFailedProfilingEnableAcknowledgementDisablesProfilingAndDetaches();
    }

    @Test
    void failedDeflateProfilingEnableAcknowledgementDisablesProfilingAndDetaches() throws Exception {
        dbgs.setCompressResponses(true);
        assertFailedProfilingEnableAcknowledgementDisablesProfilingAndDetaches();
    }

    private void assertFailedProfilingEnableAcknowledgementDisablesProfilingAndDetaches() throws Exception {
        dbgs.rejectProfilingEnableAcknowledgement();

        assertStartFails("Unexpected response body to setMeasureMode");

        List<FakeDebugServer.Request> measureModes = dbgs.requests("setMeasureMode");
        assertEquals(3, measureModes.size());
        assertFalse(measureModes.get(0).body.contains("measureModeSeanceID"));
        assertTrue(measureModes.get(1).body.contains("measureModeSeanceID"));
        assertFalse(measureModes.get(2).body.contains("measureModeSeanceID"));
        assertTrue(dbgs.isProfilingDisabled());
        List<String> commands = dbgs.commands();
        assertEquals(List.of("setMeasureMode", "detachDebugUI"), commands.subList(commands.size() - 2, commands.size()));
        assertFalse(Files.exists(output));
    }

    @Test
    void startFailsWhenInfobaseIsInDebug() throws Exception {
        dbgs.setAttachResult(AttachDebugUIResult.IB_IN_DEBUG);

        assertStartFails("IB is in debug");
    }

    @Test
    void startFailsWhenPasswordIsWrong() throws Exception {
        dbgs.setAttachResult(AttachDebugUIResult.CREDENTIALS_REQUIRED);

        assertStartFails("Use -p option to set correct password");
    }

    @Test
    void startFailsWhenDebugServerRejectsConnection() throws Exception {
        dbgs.setAttachResult(AttachDebugUIResult.UNKNOWN);

        assertStartFails("Connection result: " + AttachDebugUIResult.UNKNOWN);
    }

    // --- includeUsers: фильтр замеров по пользователю ИБ ---

    @Test
    void includeUserFailsWhenAllowedTargetCannotAttachAndNoMeasurementsArrive() throws Exception {
        dbgs.setTargets(user("demo_runner"));
        dbgs.fail("attachDetachDbgTargets", 503);
        Future<Integer> start = start("--include-user", "demo_runner");

        int stopResult = send(PipeMessages.EXIT_COMMAND);
        int startResult = start.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(1, dbgs.requests("attachDetachDbgTargets").size());
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, startResult);
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void proxyReplyDoesNotConfirmTargetAttachment() throws Exception {
        dbgs.setTargets(user("demo_runner"));
        dbgs.sendProxyReplyForTargetAttachment();
        Future<Integer> collector = start("--include-user", "demo_runner");

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1, dbgs.requests("attachDetachDbgTargets").size());
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void includeUserRecoversFromFailedAttachmentWhenMeasuresAreReceived() throws Exception {
        DebugTargetId target = user("demo_runner");
        dbgs.setTargets(target);
        dbgs.fail("attachDetachDbgTargets", 503);
        Future<Integer> start = start("--include-user", "demo_runner");
        dbgs.deliver(FakeDebugServer.measure(target, OBJECT_ID, PROPERTY_ID, 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void autoConnectedUserCanFinishWhenOnlyExcludedCodeRan() throws Exception {
        assertExcludedMeasurement(false, true);
    }

    @Test
    void autoConnectedUserCanFinishAfterManualAttachmentFailed() throws Exception {
        assertExcludedMeasurement(true, true);
    }

    @Test
    void autoConnectedTargetCanFinishWithoutUserFilterWhenOnlyExcludedCodeRan() throws Exception {
        assertExcludedMeasurement(false, false);
    }

    @Test
    void autoConnectedTargetCanFinishWithoutUserFilterAfterManualAttachmentFailed() throws Exception {
        assertExcludedMeasurement(true, false);
    }

    private void assertExcludedMeasurement(boolean attachmentFails, boolean filterByUser) throws Exception {
        DebugTargetId allowed = user("demo_runner");
        if (attachmentFails) {
            dbgs.setTargets(allowed);
            dbgs.fail("attachDetachDbgTargets", 503);
        } else {
            dbgs.setTargets();
        }
        Path config = tempDir.resolve("exclusions.json");
        Files.writeString(config, "{\"exclude\":{\"objects\":[\"CommonModule.ПростойОбщийМодуль\"]}}");
        Future<Integer> collector = filterByUser
                ? start("--include-user", "demo_runner", "--config", config.toString())
                : start("--config", config.toString());
        dbgs.deliver(FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(attachmentFails ? 1 : 0, dbgs.requests("attachDetachDbgTargets").size());
        assertEquals(null, coverage());
    }

    @Test
    void cleanPreservesConfirmationFromAcceptedMeasurement() throws Exception {
        dbgs.setTargets();
        DebugTargetId target = user("demo_runner");
        Future<Integer> collector = startRaw("--include-user", "demo_runner");
        dbgs.deliver(FakeDebugServer.measure(target, OBJECT_ID, PROPERTY_ID, 2, 1));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.DUMP_COMMAND));
        assertTrue(Files.readString(output).contains("covered=\"true\""));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.CLEAN_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(Files.readString(output).contains("lineToCover"));
    }

    @Test
    void failedAttachmentWithoutFilterOrMeasurementsFailsCollection() throws Exception {
        dbgs.setTargets(user("demo_runner"));
        dbgs.fail("attachDetachDbgTargets", 503);
        Future<Integer> collector = startRaw();

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1, dbgs.requests("attachDetachDbgTargets").size());
    }

    @Test
    void invalidExplicitIdCannotConfirmFailedAttachmentWithOnlyExcludedLines() throws Exception {
        dbgs.setTargets(user("demo_runner"));
        dbgs.fail("attachDetachDbgTargets", 503);
        Future<Integer> collector = start();
        DBGUIExtCmdInfoMeasure foreign = FakeDebugServer.measure(abbreviatedTarget("demo_runner"),
                OBJECT_ID, PROPERTY_ID, 3, 1);
        foreign.getMeasure().getModuleData().get(0).getModuleID().setExtensionName("OtherExtension");
        dbgs.deliver(foreign);

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void anonymousPacketWithoutLinesDoesNotConfirmFailedAttachment() throws Exception {
        dbgs.setTargets(user("demo_runner"));
        dbgs.fail("attachDetachDbgTargets", 503);
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void malformedListedTargetsWithoutFilterAllowEmptyCollection() throws Exception {
        dbgs.setTargets(targetWithId(null), targetWithId(""), abbreviatedTarget("synthetic_user"));
        Future<Integer> collector = startRaw();

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(dbgs.requests("attachDetachDbgTargets").isEmpty());
    }

    @Test
    void noTargetsWithoutFilterAllowsEmptyCollection() throws Exception {
        dbgs.setTargets();
        Future<Integer> collector = startRaw();

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(dbgs.requests("attachDetachDbgTargets").isEmpty());
    }

    @Test
    void rejectedPacketDoesNotUndoConfirmedTargetAttachment() throws Exception {
        DebugTargetId accepted = user("demo_runner");
        dbgs.setTargets(accepted);
        Future<Integer> collector = start("--include-user", "demo_runner");

        DebugTargetId rejected = FakeDebugServer.target(
                UUID.fromString(accepted.getId()), DebugTargetType.MANAGED_CLIENT, "other_runner");
        dbgs.deliver(FakeDebugServer.measure(rejected, OBJECT_ID, PROPERTY_ID, 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1, dbgs.requests("attachDetachDbgTargets").size());
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void includeUserSucceedsWithoutMeasurementsWhenAllowedTargetIsAttached() throws Exception {
        dbgs.setTargets(user("demo_runner"));
        Future<Integer> start = start("--include-user", "demo_runner");

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1, dbgs.requests("attachDetachDbgTargets").size());
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void includeUserCollectsOnlyMeasuresOfAllowedUsersAndAttachesOnlyTheirTargets() throws Exception {
        DebugTargetId unit = user("coverage_unit");
        DebugTargetId smoke = user("smoke");
        dbgs.setTargets(unit, smoke);
        Future<Integer> start = start("--include-user", "coverage_unit");
        dbgs.deliver(FakeDebugServer.measure(unit, OBJECT_ID, PROPERTY_ID, 2, 1),
                FakeDebugServer.measure(smoke, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
        List<FakeDebugServer.Request> attaches = dbgs.requests("attachDetachDbgTargets");
        assertEquals(1, attaches.size());
        assertTrue(attaches.get(0).body.contains(unit.getId()));
    }

    @Test
    void malformedTargetIdDoesNotInterruptListingOrCountAsAllowedUser() throws Exception {
        String runner = "suite";
        DebugTargetId allowed = FakeDebugServer.target(
                UUID.fromString("ab12cd34-01ef-2345-6789-abcdef012345"), DebugTargetType.MANAGED_CLIENT, runner);
        allowed.setId(allowed.getId().toUpperCase(Locale.ROOT));
        DebugTargetId rejected = user("other");
        dbgs.setTargets(abbreviatedTarget(runner), allowed, rejected);

        String log = captureLog(() -> {
            Future<Integer> collector = start("--include-user", runner);
            dbgs.deliver(FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 2, 1));
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
            assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        });

        List<FakeDebugServer.Request> attached = dbgs.requests("attachDetachDbgTargets");
        assertEquals(1, attached.size());
        assertTrue(attached.get(0).body.contains(allowed.getId().toLowerCase(Locale.ROOT)));
        assertTrue(log.contains("1 target(s) accepted, 1 rejected, 0 unknown"), log);
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void includeUserAcceptsTargetsStartedLaterAndRejectsOthers() throws Exception {
        DebugTargetId unit = user("coverage_unit");
        DebugTargetId smoke = user("smoke");
        Future<Integer> start = start("--include-user", "coverage_unit");
        dbgs.deliver(FakeDebugServer.targetStarted(abbreviatedTarget("coverage_unit")),
                FakeDebugServer.targetStarted(unit), FakeDebugServer.targetStarted(smoke));
        dbgs.deliver(FakeDebugServer.measure(unit, OBJECT_ID, PROPERTY_ID, 2, 1),
                FakeDebugServer.measure(smoke, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
        // стартовый предмет имитации без имени пользователя не подключён, подключён только unit
        List<FakeDebugServer.Request> attaches = dbgs.requests("attachDetachDbgTargets");
        assertEquals(1, attaches.size());
        assertTrue(attaches.get(0).body.contains(unit.getId()));
    }

    @Test
    void includeUserResolvesPacketWithOnlyTargetIdByRegistryEvenAfterTargetQuit() throws Exception {
        DebugTargetId unit = user("coverage_unit");
        dbgs.setTargets(unit);
        Future<Integer> start = start("--include-user", "coverage_unit");
        DebugTargetId idOnly = FakeDebugServer.target(UUID.fromString(unit.getId()), DebugTargetType.MANAGED_CLIENT, null);
        dbgs.deliver(FakeDebugServer.targetQuit(unit));
        dbgs.deliver(FakeDebugServer.measure(idOnly, OBJECT_ID, PROPERTY_ID, 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void includeUserReusesRefreshedTargetListWithinOnePing() throws Exception {
        Future<Integer> start = start("--include-user", "coverage_unit");
        int listed = dbgs.requests("getDbgTargets").size();
        DebugTargetId late = user("coverage_unit");
        dbgs.setTargets(abbreviatedTarget("coverage_unit"), late);
        DebugTargetId lateIdOnly = FakeDebugServer.target(UUID.fromString(late.getId()), DebugTargetType.MANAGED_CLIENT, null);
        DebugTargetId stranger = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, null);
        DebugTargetId another = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.CLIENT, null);
        String log = captureLog(() -> {
            dbgs.deliver(FakeDebugServer.measure(lateIdOnly, OBJECT_ID, PROPERTY_ID, 2, 1),
                    FakeDebugServer.measure(stranger, OBJECT_ID, PROPERTY_ID, 10, 1),
                    FakeDebugServer.measure(another, OBJECT_ID, PROPERTY_ID, 10, 1),
                    FakeDebugServer.measure(stranger, OBJECT_ID, PROPERTY_ID, 10, 1),
                    FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 10, 1));
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
            assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        });

        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
        assertEquals(listed + 1, dbgs.requests("getDbgTargets").size());
        List<FakeDebugServer.Request> attached = dbgs.requests("attachDetachDbgTargets");
        assertEquals(1, attached.size());
        assertTrue(attached.get(0).body.contains(late.getId()));
        // каждый пакет посчитан один раз; стартовый предмет без имени и два неизвестных UUID
        assertTrue(log.contains("Include users: 1 target(s) accepted, 0 rejected, 3 unknown; "
                + "1 measure packet(s) accepted, 0 rejected, 4 unknown"), log);
        assertFalse(log.contains("Measures of target " + late.getId() + " have no user"), log);
    }

    @Test
    void successfulLookupWithoutUserStillLeavesFinalCollectionIncomplete() throws Exception {
        assertNamelessFinalMeasureMakesRunIncomplete(false);
    }

    @Test
    void namelessFinalMeasureDuringLookupCooldownLeavesCollectionIncomplete() throws Exception {
        assertNamelessFinalMeasureMakesRunIncomplete(true);
    }

    private void assertNamelessFinalMeasureMakesRunIncomplete(boolean lookUpBeforeStop) throws Exception {
        DebugTargetId allowed = user("suite");
        DebugTargetId nameless = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, null);
        dbgs.setTargets(allowed, nameless);
        Future<Integer> collector = start(startCommandLine(() -> 0L), "--include-user", "suite");
        dbgs.deliver(FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 2, 1));
        int requestsBeforeLookup = dbgs.requests("getDbgTargets").size();

        if (lookUpBeforeStop) {
            dbgs.deliver(FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 11, 1));
            assertEquals(requestsBeforeLookup + 1, dbgs.requests("getDbgTargets").size());
        }
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 11, 1),
                FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(requestsBeforeLookup + 1, dbgs.requests("getDbgTargets").size());
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void failedLookupOfNamelessFinalMeasureFailsCollectionButKnownUserStillCounts() throws Exception {
        DebugTargetId known = user("suite");
        DebugTargetId late = user("suite");
        var lateId = FakeDebugServer.target(UUID.fromString(late.getId()), DebugTargetType.MANAGED_CLIENT, null);
        dbgs.setTargets(known);
        Future<Integer> collector = start("--include-user", "suite");
        dbgs.deliver(FakeDebugServer.measure(known, OBJECT_ID, PROPERTY_ID, 2, 1));

        dbgs.setTargets(known, late);
        dbgs.fail("getDbgTargets", 503);
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(lateId, OBJECT_ID, PROPERTY_ID, 11, 1),
                FakeDebugServer.measure(known, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void rejectingKnownUserInFinalMeasureRemainsSuccessful() throws Exception {
        DebugTargetId allowed = user("suite");
        DebugTargetId excluded = user("other");
        var excludedId = FakeDebugServer.target(UUID.fromString(excluded.getId()), DebugTargetType.MANAGED_CLIENT, null);
        dbgs.setTargets(allowed, excluded);
        Future<Integer> collector = start("--include-user", "suite");
        dbgs.fail("getDbgTargets", 503);
        dbgs.deliverOnMeasureOff(FakeDebugServer.measure(excludedId, OBJECT_ID, PROPERTY_ID, 11, 1),
                FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, true, 11, false), coverage());
    }

    @Test
    void malformedFinalTargetIdCannotContributeFilteredCoverage() throws Exception {
        DebugTargetId allowed = user("suite");
        dbgs.setTargets(allowed);
        Future<Integer> collector = start("--include-user", "suite");
        dbgs.deliver(FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 2, 1));

        var malformed = FakeDebugServer.measure(abbreviatedTarget("suite"), OBJECT_ID, PROPERTY_ID, 11, 1);
        malformed.setTargetID(allowed);
        dbgs.deliverOnMeasureOff(malformed, FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 10, 1));

        int stopResult = send(PipeMessages.EXIT_COMMAND);
        int collectorResult = collector.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
        assertEquals(CommandLine.ExitCode.SOFTWARE, stopResult);
        assertEquals(CommandLine.ExitCode.SOFTWARE, collectorResult);
    }

    @Test
    void stopUsesCommandUserForMatchingFinalTarget() throws Exception {
        DebugTargetId established = user("suite");
        DebugTargetId finalTarget = FakeDebugServer.target(
                UUID.fromString("ab12cd34-01ef-2345-6789-abcdef012345"), DebugTargetType.MANAGED_CLIENT, "suite");
        dbgs.setTargets(established);
        Future<Integer> collector = start("--include-user", "suite");
        dbgs.deliver(FakeDebugServer.measure(established, OBJECT_ID, PROPERTY_ID, 2, 1));
        int listRequests = dbgs.requests("getDbgTargets").size();

        var finalPacket = FakeDebugServer.measure(finalTarget, OBJECT_ID, PROPERTY_ID, 10, 1);
        finalPacket.getMeasure().getTargetID().unsetUserName();
        finalPacket.getTargetID().setId(finalTarget.getId().toUpperCase(Locale.ROOT));
        dbgs.deliverOnMeasureOff(FakeDebugServer.targetQuit(finalTarget), finalPacket);

        int stopResult = send(PipeMessages.EXIT_COMMAND);
        int collectorResult = collector.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
        assertEquals(CommandLine.ExitCode.OK, stopResult);
        assertEquals(CommandLine.ExitCode.OK, collectorResult);
        assertEquals(listRequests, dbgs.requests("getDbgTargets").size());
    }

    @Test
    void stopKeepsFinalTargetUnresolvedWhenCommandNamesDifferentTarget() throws Exception {
        DebugTargetId allowed = user("suite");
        dbgs.setTargets(allowed);
        Future<Integer> collector = start("--include-user", "suite");
        dbgs.deliver(FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 2, 1));

        var unknown = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, null);
        var mismatched = FakeDebugServer.measure(unknown, OBJECT_ID, PROPERTY_ID, 11, 1);
        mismatched.setTargetID(allowed);
        dbgs.deliverOnMeasureOff(mismatched, FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, true, 11, false), coverage());
    }

    @Test
    void includeUserRecoversLookupOfPreviouslyNamelessTarget() throws Exception {
        DebugTargetId unit = user("coverage_unit");
        DebugTargetId idOnly = FakeDebugServer.target(UUID.fromString(unit.getId()), DebugTargetType.MANAGED_CLIENT, null);
        dbgs.setTargets(idOnly);
        Future<Integer> start = start("-n", "Area", "--include-user", "coverage_unit");
        int listed = dbgs.requests("getDbgTargets").size();

        dbgs.fail("getDbgTargets", 503);
        dbgs.deliver(FakeDebugServer.measure(idOnly, OBJECT_ID, PROPERTY_ID, 10, 1),
                FakeDebugServer.measure(idOnly, OBJECT_ID, PROPERTY_ID, 10, 1));
        assertEquals(listed + 1, dbgs.requests("getDbgTargets").size());

        dbgs.recover("getDbgTargets");
        dbgs.setTargets(unit);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (dbgs.requests("getDbgTargets").size() == listed + 1 && System.nanoTime() < deadline) {
            dbgs.deliver(FakeDebugServer.measure(idOnly, OBJECT_ID, PROPERTY_ID, 2, 1));
        }

        assertEquals(listed + 2, dbgs.requests("getDbgTargets").size());
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
    }

    @Test
    void scopedTargetLookupMatchesTheWholeAreaName() throws Exception {
        DebugTargetId expected = user("suite");
        dbgs.setTargets();
        dbgs.setTargetsForArea("North", user("other"));
        dbgs.setTargetsForArea("NorthEast", expected);
        Future<Integer> collector = start("-n", "NorthEast", "--include-user", "suite");
        List<FakeDebugServer.Request> attachments = dbgs.requests("attachDetachDbgTargets");

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1, attachments.size());
        assertTrue(attachments.getFirst().body.contains(expected.getId()));
    }

    @Test
    void includeUserResolvesPacketsFromMultipleAreasInOneLookupWithoutAttachingOtherTargets() throws Exception {
        String runner = "demo_runner";
        DebugTargetId first = user(runner);
        DebugTargetId second = user(runner);
        DebugTargetId outside = user(runner);
        DebugTargetId firstUnnamed = FakeDebugServer.target(UUID.fromString(first.getId()),
                DebugTargetType.MANAGED_CLIENT, null);
        DebugTargetId secondUnnamed = FakeDebugServer.target(UUID.fromString(second.getId()),
                DebugTargetType.MANAGED_CLIENT, null);
        dbgs.setTargetsForArea("area-first", firstUnnamed);
        dbgs.setTargetsForArea("area-second", secondUnnamed);
        Future<Integer> start = start("-n", "area-first", "area-second", "--include-user", runner);

        dbgs.setTargetsForArea("area-first", first);
        dbgs.setTargetsForArea("area-second", second);
        dbgs.setTargets(first, second, outside);
        dbgs.deliver(FakeDebugServer.measure(secondUnnamed, OBJECT_ID, PROPERTY_ID, 10, 1),
                FakeDebugServer.measure(firstUnnamed, OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.deliver(FakeDebugServer.measure(secondUnnamed, OBJECT_ID, PROPERTY_ID, 11, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        List<FakeDebugServer.Request> lookups = dbgs.requests("getDbgTargets");
        assertEquals(3, lookups.size());
        assertTrue(lookups.get(0).body.contains("area-first"));
        assertTrue(lookups.get(1).body.contains("area-second"));
        assertFalse(lookups.get(2).body.contains("area-first"));
        assertFalse(lookups.get(2).body.contains("area-second"));
        assertTrue(dbgs.requests("attachDetachDbgTargets").isEmpty());
        assertEquals(Map.of(2, true, 10, true, 11, true), coverage());
    }

    @Test
    void includeUserFailsStartAndStopWhenNoTargetMatched() throws Exception {
        dbgs.setTargets(user("smoke"));
        String log = captureLog(() -> {
            Future<Integer> start = start("--include-user", "coverage_unit");
            dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
            assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
            assertEquals(CommandLine.ExitCode.SOFTWARE, start.get(TIMEOUT, TimeUnit.SECONDS));
        });

        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
        assertTrue(log.contains("No debug target matched includeUsers [coverage_unit]; users seen: smoke"), log);
        assertTrue(log.contains("Include users: 0 target(s) accepted, 1 rejected, 0 unknown; "
                + "0 measure packet(s) accepted, 0 rejected, 1 unknown"), log);
        assertTrue(log.contains("Measure without target id dropped"), log);
    }

    @Test
    void includeUserFromCommandLineReplacesConfigList() throws Exception {
        Path config = tempDir.resolve("users.json");
        Files.writeString(config, "{\"includeUsers\":[\"smoke\"]}");
        DebugTargetId unit = user("coverage_unit");
        DebugTargetId smoke = user("smoke");
        dbgs.setTargets(unit, smoke);
        String log = captureLog(() -> {
            Future<Integer> start = start("--config", config.toString(), "--include-user", "coverage_unit");
            dbgs.deliver(FakeDebugServer.measure(unit, OBJECT_ID, PROPERTY_ID, 2, 1),
                    FakeDebugServer.measure(smoke, OBJECT_ID, PROPERTY_ID, 10, 1));
            assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
            assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        });

        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
        assertTrue(log.contains("Include users: coverage_unit (--include-user; includeUsers of users.json ignored)"), log);
        assertFalse(log.contains("applies only to start"), log);
    }

    @Test
    void includeUsersOfConfigDirectoryAreMerged() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("conf"));
        Files.writeString(dir.resolve("a.json"), "{\"includeUsers\":[\"u1\"]}");
        Files.writeString(dir.resolve("b.json"), "{\"includeUsers\":[\"u2\"],\"exclude\":{\"objects\":[\"Catalog.Нет*\"]}}");
        DebugTargetId u1 = user("u1");
        DebugTargetId u2 = user("u2");
        DebugTargetId u3 = user("u3");
        dbgs.setTargets(u1, u2, u3);
        Future<Integer> start = start("--config", dir.toString());
        dbgs.deliver(FakeDebugServer.measure(u1, OBJECT_ID, PROPERTY_ID, 2, 1),
                FakeDebugServer.measure(u2, OBJECT_ID, PROPERTY_ID, 7, 1),
                FakeDebugServer.measure(u3, OBJECT_ID, PROPERTY_ID, 10, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 7, true, 10, false, 11, false), coverage());
    }

    @Test
    void cleanKeepsMatchedTargets() throws Exception {
        DebugTargetId unit = user("coverage_unit");
        dbgs.setTargets(unit);
        Future<Integer> start = start("--include-user", "coverage_unit");
        dbgs.deliver(FakeDebugServer.measure(unit, OBJECT_ID, PROPERTY_ID, 2, 1));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.CLEAN_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, false, 10, false, 11, false), coverage());
    }

    @Test
    void blankIncludeUserFailsBeforeConnecting() throws Exception {
        assertStartFails("--include-user: empty value", "--include-user", " ");
        assertTrue(dbgs.commands().isEmpty(), dbgs.commands().toString());
    }

    // Критерий приёмки 5: сырой сбор с общим конфигом (includeUsers + правило) после convert совпадает с прямым.
    @Test
    void rawStartWithIncludeUsersConfigAgreesWithDirectCollection() throws Exception {
        Path project = tempDir.resolve("project");
        Path config = tempDir.resolve("coverage41c.json");
        Files.writeString(config, "{\"includeUsers\":[\"coverage_unit\"],\"exclude\":{\"lines\":[\"^\\\\s*Е\\\\(\\\\);\"]}}");
        DebugTargetId unit = user("coverage_unit");
        DebugTargetId smoke = user("smoke");
        dbgs.setTargets(unit, smoke);
        Future<Integer> direct = start("--config", config.toString());
        deliverUnitAndSmoke(unit, smoke);
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, direct.get(TIMEOUT, TimeUnit.SECONDS));
        Map<Integer, Boolean> expected = coverage();
        assertEquals(Map.of(2, true, 11, false), expected);   // 4 — Cover:off, 7–8 — auto без попаданий, 10 — правило

        Path raw = tempDir.resolve("raw.xml");
        Future<Integer> rawStart = submit(List.of(PipeMessages.START_COMMAND, "-i", infobase, "-u", dbgs.url(),
                "--config", config.toString(), "-o", raw.toString(), "-t", "10"));
        deliverUnitAndSmoke(unit, smoke);
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, rawStart.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(CommandLine.ExitCode.OK, Coverage41C.getCommandLine().execute("convert",
                "-P", project.toString(), "-s", "configuration", "--config", config.toString(),
                "-c", raw.toString(), "-o", output.toString()));
        assertEquals(expected, coverage());
    }

    @Test
    void rawStartWithRulesUsesCliUserFilter() throws Exception {
        Path config = tempDir.resolve("rules.json");
        Files.writeString(config, "{\"exclude\":{\"objects\":[\"CommonModule.X\"]}}");
        DebugTargetId unit = user("coverage_unit");
        DebugTargetId smoke = user("smoke");
        dbgs.setTargets(unit, smoke);
        Future<Integer> start = submit(List.of(PipeMessages.START_COMMAND, "-i", infobase, "-u", dbgs.url(),
                "--include-user", "coverage_unit", "--config", config.toString(), "-o", output.toString(), "-t", "10"));
        deliverUnitAndSmoke(unit, smoke);

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
        assertTrue(Files.readString(output).contains("lineNumber=\"2\""));
        assertFalse(Files.readString(output).contains("lineNumber=\"7\""));
    }

    @Test
    void rawStartWithConfigWithoutIncludeUsersStillRequiresSources() throws Exception {
        Path config = tempDir.resolve("rules.json");
        Files.writeString(config, "{\"exclude\":{\"objects\":[\"CommonModule.X\"]}}");
        StringWriter err = new StringWriter();
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(err, true));
        int code = commandLine.execute(PipeMessages.START_COMMAND, "-i", infobase, "-u", dbgs.url(),
                "--config", config.toString(), "-o", output.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, code);
        assertTrue(err.toString().contains("Coverage configuration requires source files"), err.toString());
    }

    // unit исполняет строки 2 и 10 (10 исключена правилом), smoke — строку 7.
    private void deliverUnitAndSmoke(DebugTargetId unit, DebugTargetId smoke) throws Exception {
        dbgs.deliver(FakeDebugServer.measure(unit, OBJECT_ID, PROPERTY_ID, 2, 1, 10, 1),
                FakeDebugServer.measure(smoke, OBJECT_ID, PROPERTY_ID, 7, 1));
    }

    private static DebugTargetId targetWithId(String id) {
        DebugTargetId target = user("synthetic_user");
        target.setId(id);
        return target;
    }

    private static DebugTargetId abbreviatedTarget(String name) {
        DebugTargetId target = user(name);
        target.setId("A-B-C-D-E");
        return target;
    }

    private static DebugTargetId user(String name) {
        return FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, name);
    }


    @Test
    void statsOutputSurvivesDisabledInfoLogging() throws Exception {
        for (String level : List.of("warn", "error", "off")) {
            Path stderr = tempDir.resolve("stats-" + level + ".stderr");
            Path stdout = tempDir.resolve("stats-" + level + ".stdout");
            Path coverage = tempDir.resolve("stats-" + level + ".xml");
            Process child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dorg.slf4j.simpleLogger.defaultLogLevel=" + level,
                    "-cp", System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                    StatsOutputProbe.class.getName(), coverage.toString())
                    .redirectError(stderr.toFile()).redirectOutput(stdout.toFile()).start();
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "stats probe timed out at " + level);
                String output = Files.readString(stderr);
                assertEquals(0, child.exitValue(), output);
                assertTrue(output.contains("Lines to cover"), output);
                assertTrue(output.contains("Covered lines"), output);
                assertFalse(Files.readString(stdout).contains("Lines to cover"));
            } finally {
                child.destroyForcibly();
            }
        }
    }

    @Test
    void statsReportsStderrWriteAndFlushFailures() throws Exception {
        for (String failure : List.of("write", "flush", "closed")) {
            runFailingStatsProbe(failure, tempDir.resolve("stats-failure-" + failure + ".stderr"));
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(OS.LINUX)
    void statsReportsFullStderrDevice() throws Exception {
        runFailingStatsProbe("device", Path.of("/dev/full"));
    }

    private void runFailingStatsProbe(String failure, Path stderr) throws Exception {
        Path stdout = tempDir.resolve("stats-failure-" + failure + ".stdout");
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dorg.slf4j.simpleLogger.defaultLogLevel=off",
                "-cp", System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                StatsOutputProbe.class.getName(), tempDir.resolve("stats-" + failure + ".xml").toString(), failure)
                .redirectError(stderr.toFile()).redirectOutput(stdout.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "stats failure probe timed out: " + failure);
            String diagnostic = Files.readString(stdout);
            if (!failure.equals("device")) {
                diagnostic += Files.readString(stderr);
            }
            assertEquals(0, child.exitValue(), diagnostic);
            assertTrue(Files.readString(stdout).contains("Stats failure reported"), diagnostic);
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(OS.LINUX)
    void coverageStdoutRecoversOnTheSameDescriptor() throws Exception {
        Path diagnostics = tempDir.resolve("stdout-recovery.log");
        Process probe = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                StdoutRecoveryProbe.class.getName(), tempDir.toString())
                .redirectError(diagnostics.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(probe.waitFor(45, TimeUnit.SECONDS), () -> "See " + diagnostics);
            assertEquals(0, probe.exitValue(), Files.readString(diagnostics));
        } finally {
            probe.destroyForcibly();
        }
    }

    public static class StdoutRecoveryProbe {
        public interface NativeOutput extends com.sun.jna.Library {
            int open(String path, int flags);
            int dup2(int source, int destination);
            int close(int descriptor);
        }

        private static void redirect(Path destination) {
            NativeOutput libc = com.sun.jna.Native.load("c", NativeOutput.class);
            int fd = libc.open(destination.toString(), 1);
            assertTrue(fd >= 0, destination.toString());
            try {
                assertEquals(1, libc.dup2(fd, 1));
            } finally {
                libc.close(fd);
            }
        }

        private static int send(String command, String alias, String url) {
            return Coverage41C.getCommandLine().execute(command, "-i", alias, "-u", url);
        }

        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            for (int scenario = 0; scenario < 3; scenario++) {
                boolean explicitStdout = scenario == 1;
                boolean persistentFailure = scenario == 2;
                try (FakeDebugServer server = new FakeDebugServer()) {
                    String alias = "output-" + UUID.randomUUID();
                    List<String> options = new ArrayList<>(List.of("start", "-i", alias, "-u", server.url()));
                    if (explicitStdout) {
                        options.addAll(List.of("-o", "/dev/stdout"));
                    }
                    var start = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                            Coverage41C.getCommandLine(StandardOutputFile.PROCESS_OUTPUT)
                                    .execute(options.toArray(new String[0])));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (server.requests("pingDebugUIParams").isEmpty()) {
                        assertFalse(start.isDone(), "Collector exited before its first poll");
                        assertTrue(System.nanoTime() < deadline, "Collector did not start");
                        Thread.sleep(10);
                    }
                    assertEquals(0, send("check", alias, server.url()));
                    server.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
                    redirect(Path.of("/dev/full"));
                    assertEquals(1, send("dump", alias, server.url()));
                    assertEquals(1, send("dump", alias, server.url()));
                    if (persistentFailure) {
                        assertEquals(1, send("stop", alias, server.url()));
                        assertEquals(1, start.get(15, TimeUnit.SECONDS));
                        continue;
                    }
                    Path dump = Files.createFile(directory.resolve("dump-" + explicitStdout + ".xml"));
                    redirect(dump);
                    assertEquals(0, send("dump", alias, server.url()), "dump after restoring fd 1");
                    Path stop = Files.createFile(directory.resolve("stop-" + explicitStdout + ".xml"));
                    redirect(stop);
                    assertEquals(0, send("stop", alias, server.url()));
                    assertEquals(0, start.get(15, TimeUnit.SECONDS));
                    for (Path report : List.of(dump, stop)) {
                        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report.toFile());
                        Element line = (Element) xml.getElementsByTagName("lineToCover").item(0);
                        assertTrue(line != null, report.toString());
                        assertEquals("2", line.getAttribute("lineNumber"));
                        assertEquals("true", line.getAttribute("covered"));
                    }
                }
            }
        }
    }

    public static class StatsOutputProbe {
        public static void main(String[] args) throws Exception {
            try (FakeDebugServer server = new FakeDebugServer()) {
                String alias = "stats-" + UUID.randomUUID();
                Future<Integer> collector = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        startCommandLine().execute("start", "-i", alias, "-u", server.url(),
                                "-o", args[0], "-t", "10"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (server.requests("pingDebugUIParams").isEmpty()) {
                    if (collector.isDone() || System.nanoTime() >= deadline) {
                        throw new AssertionError("Stats collector did not start");
                    }
                    Thread.sleep(10);
                }
                boolean expectFailure = args.length > 1;
                boolean[] broken = {true};
                boolean[] closed = {false};
                ByteArrayOutputStream collected = new ByteArrayOutputStream();
                java.io.OutputStream sink = new java.io.OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        if (closed[0] || broken[0] && args[1].equals("write")) {
                            throw new IOException("synthetic write failure");
                        }
                        collected.write(value);
                    }

                    @Override
                    public void flush() throws IOException {
                        if (closed[0] || broken[0] && args[1].equals("flush")) {
                            throw new IOException("synthetic flush failure");
                        }
                    }

                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                };
                if (expectFailure && !args[1].equals("device")) {
                    if (args[1].equals("closed")) {
                        sink.close();
                    }
                    statisticsSink = sink;
                }
                int stats = Coverage41C.getCommandLine().execute("stats", "-i", alias, "-u", server.url());
                int repeated = expectFailure
                        ? Coverage41C.getCommandLine().execute("stats", "-i", alias, "-u", server.url()) : 0;
                int recovered = 0;
                if (expectFailure && !args[1].equals("device")) {
                    broken[0] = false;
                    collected.reset();
                    if (closed[0]) {
                        statisticsSink = collected;
                    }
                    recovered = Coverage41C.getCommandLine().execute("stats", "-i", alias, "-u", server.url());
                    if (!collected.toString().contains("Covered lines")) {
                        throw new AssertionError("Recovery did not write statistics");
                    }
                    if (!closed[0]) {
                        broken[0] = true;
                        if (Coverage41C.getCommandLine().execute("stats", "-i", alias, "-u", server.url()) == 0) {
                            throw new AssertionError("Persistent failure returned success after recovery");
                        }
                    }
                    statisticsSink = null;
                }
                int stop = Coverage41C.getCommandLine().execute("stop", "-i", alias, "-u", server.url());
                int start = collector.get(20, TimeUnit.SECONDS);
                if ((expectFailure ? stats == 0 || repeated == 0 : stats != 0) || recovered != 0 || stop != 0 || start != 0) {
                    throw new AssertionError("Unexpected status: stats=" + stats + ", stop=" + stop + ", start=" + start);
                }
                if (expectFailure) {
                    System.out.println("Stats failure reported");
                }
            }
        }
    }

    interface Scenario {
        void run() throws Exception;
    }

    // Лог start идёт в stderr; на время сценария он перехватывается целиком.
    private static String captureLog(Scenario scenario) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            scenario.run();
        } finally {
            System.setErr(originalErr);
        }
        return log.toString(StandardCharsets.UTF_8);
    }

    // start завершается с ошибкой, объясняет причину и не оставляет за собой сокет для команд.
    private void assertStartFails(String reason, String... options) throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            List<String> arguments = new ArrayList<>(List.of(PipeMessages.START_COMMAND,
                    "-i", infobase, "-u", dbgs.url(), "-P", project.toString(), "-s", "configuration",
                    "-o", output.toString()));
            arguments.addAll(Arrays.asList(options));
            exitCode = Coverage41C.getCommandLine().execute(arguments.toArray(new String[0]));
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(log.toString(StandardCharsets.UTF_8).contains(reason), log.toString(StandardCharsets.UTF_8));
        assertFalse(Files.exists(Path.of(Utils.getPipeName(connectionOptions()))));
    }

    private static volatile java.io.OutputStream statisticsSink;

    private static String captureStatistics(Scenario scenario) throws Exception {
        ByteArrayOutputStream report = new ByteArrayOutputStream();
        statisticsSink = report;
        try {
            scenario.run();
            return report.toString(StandardCharsets.UTF_8);
        } finally {
            statisticsSink = null;
        }
    }

    private static CommandLine startCommandLine() {
        return startCommandLine(System::nanoTime);
    }

    private static CommandLine startCommandLine(LongSupplier targetLookupClock) {
        return startCommandLine(targetLookupClock, System::nanoTime);
    }

    private static CommandLine startCommandLine(LongSupplier targetLookupClock, LongSupplier finalPollClock) {
        CommandLine.IFactory defaultFactory = CommandLine.defaultFactory();
        return new CommandLine(new Coverage41C(), new CommandLine.IFactory() {
            @Override
            public <T> T create(Class<T> type) throws Exception {
                if (type == CoverageCommand.class) {
                    return type.cast(new CoverageCommand(targetLookupClock, TEST_FINAL_MEASURE_WINDOW,
                            TEST_FINAL_CONFIRMATION_TIMEOUT, finalPollClock) {
                        @Override
                        protected java.io.OutputStream statisticsOutput() {
                            java.io.OutputStream sink = statisticsSink;
                            return sink == null ? super.statisticsOutput() : sink;
                        }
                    });
                }
                return defaultFactory.create(type);
            }
        });
    }

    private Future<Integer> startRaw(String... options) throws Exception {
        return startRaw(startCommandLine(), options);
    }

    private Future<Integer> startRaw(CommandLine command, String... options) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.START_COMMAND,
                "-i", infobase,
                "-u", dbgs.url(),
                "-o", output.toString(),
                "-t", "10"));
        arguments.addAll(Arrays.asList(options));
        return submit(command, arguments);
    }

    private static final class FinalPollClock implements LongSupplier {
        private volatile LongSupplier time = () -> 0;

        @Override
        public long getAsLong() {
            return time.getAsLong();
        }

        void advance(Duration duration) {
            long next = getAsLong() + duration.toNanos();
            time = () -> next;
        }

        void resumeRealTime() {
            long start = System.nanoTime();
            long current = getAsLong();
            time = () -> current + (System.nanoTime() - start);
        }
    }

    private Future<Integer> start(String... options) throws Exception {
        return start(startCommandLine(), options);
    }

    private Future<Integer> start(CommandLine command, String... options) throws Exception {
        return startWithPingTimeout(command, "10", options);
    }

    private Future<Integer> startWithPingTimeout(CommandLine command, String pingTimeout, String... options) throws Exception {
        Path project = tempDir.resolve("project");
        if (!Files.exists(project)) {
            TestUtils.copyConfiguration(tempDir);
            Files.writeString(project.resolve(MODULE), String.join("\n", MARKED_MODULE) + "\n");
        }
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.START_COMMAND,
                "-i", infobase,
                "-u", dbgs.url(),
                "-P", project.toString(),
                "-s", "configuration",
                "-o", output.toString(),
                "-t", pingTimeout));
        arguments.addAll(Arrays.asList(options));
        return submit(command, arguments);
    }

    private Future<Integer> submit(List<String> arguments) throws Exception {
        return submit(startCommandLine(), arguments);
    }

    private Future<Integer> submit(CommandLine command, List<String> arguments) throws Exception {
        Future<Integer> start = executor.submit(() -> command.execute(arguments.toArray(new String[0])));
        awaitStarted(start);
        return start;
    }

    // check отвечает OK, когда start подключился к серверу отладки; пока сокета нет, команда сразу падает.
    private void awaitStarted(Future<Integer> start) throws Exception {
        while (send(PipeMessages.CHECK_COMMAND) != CommandLine.ExitCode.OK) {
            if (start.isDone()) {
                throw new IllegalStateException("start finished with exit code " + start.get(TIMEOUT, TimeUnit.SECONDS));
            }
            Thread.sleep(50);
        }
    }

    private ConnectionOptions connectionOptions() {
        return CommandLine.populateCommand(new ConnectionOptions(), "-i", infobase, "-u", dbgs.url());
    }

    private int send(String command) {
        return send(command, new StringWriter());
    }

    private int send(String command, StringWriter stderr) {
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        try {
            return executor.submit(() -> commandLine.execute(command, "-i", infobase, "-u", dbgs.url()))
                    .get(TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("No answer to " + command, e);
        }
    }

    // номер строки → покрыта ли; null, если модуля нет в отчёте
    private Map<Integer, Boolean> coverage() throws Exception {
        return coverage(MODULE);
    }

    private Map<Integer, Boolean> coverage(String path) throws Exception {
        return coverage(path, "");
    }

    private Map<Integer, Boolean> coverage(String path, String extension) throws Exception {
        NodeList files = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
                .getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            if (!file.getAttribute("path").equals(path) || !file.getAttribute("extension").equals(extension)) {
                continue;
            }
            Map<Integer, Boolean> lines = new TreeMap<>();
            NodeList lineNodes = file.getElementsByTagName("lineToCover");
            for (int j = 0; j < lineNodes.getLength(); j++) {
                Element line = (Element) lineNodes.item(j);
                lines.put(Integer.parseInt(line.getAttribute("lineNumber")),
                        Boolean.parseBoolean(line.getAttribute("covered")));
            }
            return lines;
        }
        return null;
    }
}
