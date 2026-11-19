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
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoMeasure;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmds;
import com.clouds42.CommandLineOptions.ConnectionOptions;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    void exclusionReportIsWrittenBeforeMeasuring() throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        Future<Integer> start = start("--exclusion-report", report.toString());

        assertTrue(Files.exists(report));
        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
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
    void stopRejectsProxyReplyToProfilingDisableAndSavesPartialCoverage() throws Exception {
        Future<Integer> collector = start();
        dbgs.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
        dbgs.sendProxyReplyWhenProfilingIsDisabled();

        assertEquals(CommandLine.ExitCode.SOFTWARE, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.SOFTWARE, collector.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(Map.of(2, true, 10, false, 11, false), coverage());
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() - deadline < 0) {
            for (var entry : Thread.getAllStackTraces().entrySet()) {
                boolean waiting = Arrays.stream(entry.getValue())
                        .anyMatch(frame -> frame.getClassName().equals("com.clouds42.Commands.CoverageCommand")
                                && frame.getMethodName().equals("awaitFinalMeasures"));
                if (waiting) {
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
        Future<Integer> start = executor.submit(() -> Coverage41C.getCommandLine().execute(PipeMessages.START_COMMAND,
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
        String report = captureLog(() -> assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.STATS_COMMAND)));
        assertTrue(report.contains("Lines to cover"));
        assertTrue(report.contains("Module.bsl"));

        assertEquals(CommandLine.ExitCode.OK, send(PipeMessages.EXIT_COMMAND));
        assertEquals(CommandLine.ExitCode.OK, start.get(TIMEOUT, TimeUnit.SECONDS));
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
    void includeUserAcceptsTargetsStartedLaterAndRejectsOthers() throws Exception {
        DebugTargetId unit = user("coverage_unit");
        DebugTargetId smoke = user("smoke");
        Future<Integer> start = start("--include-user", "coverage_unit");
        dbgs.deliver(FakeDebugServer.targetStarted(unit), FakeDebugServer.targetStarted(smoke));
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
        dbgs.setTargets(late);
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
        assertTrue(dbgs.requests("attachDetachDbgTargets").get(0).body.contains(late.getId()));
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
        Future<Integer> collector = start("--include-user", "suite");
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

    private static DebugTargetId user(String name) {
        return FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, name);
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

    private Future<Integer> startRaw() throws Exception {
        return submit(List.of(
                PipeMessages.START_COMMAND,
                "-i", infobase,
                "-u", dbgs.url(),
                "-o", output.toString(),
                "-t", "10"));
    }

    private Future<Integer> start(String... options) throws Exception {
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
                "-t", "10"));
        arguments.addAll(Arrays.asList(options));
        return submit(arguments);
    }

    private Future<Integer> submit(List<String> arguments) throws Exception {
        Future<Integer> start = executor.submit(() -> Coverage41C.getCommandLine()
                .execute(arguments.toArray(new String[0])));
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
