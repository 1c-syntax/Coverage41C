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

import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetId;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com.clouds42.Commands.CoverageCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.clouds42.ConfiguredCoverageTest.MODULE;
import static com.clouds42.ConfiguredCoverageTest.OBJECT_ID;
import static com.clouds42.ConfiguredCoverageTest.PROPERTY_ID;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class ConfiguredStartTest {
    @TempDir Path directory;

    @Test
    void rawCollectionAppliesCliUserOverrideAndKeepsTheLegacyFormat() throws Exception {
        Path config = Files.writeString(directory.resolve("coverage41c.json"), "{\"includeUsers\":[\"other\"]}");
        DebugTargetId allowed = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, "chosen");
        DebugTargetId rejected = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, "other");
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            debugger.setTargets(allowed, rejected);
            debugger.setCompressResponses(true);
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            Future<Integer> start = launch(executor, debugger, connection, output, "--config", config.toString(),
                    "--include-user", "chosen");
            try {
                debugger.deliver(FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 2, 1),
                        FakeDebugServer.measure(rejected, OBJECT_ID, PROPERTY_ID, 4, 1));
            } finally {
                assertEquals(0, send(connection, "stop"));
            }
            assertEquals(0, start.get(10, TimeUnit.SECONDS));
            assertEquals(Map.of(2, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
            var attaches = debugger.requests("attachDetachDbgTargets");
            assertEquals(1, attaches.size());
            assertTrue(attaches.getFirst().body.contains(allowed.getId()));
        }
    }

    @Test
    void attachesAnAnnouncedTargetWhenItsUserNameBecomesAvailableWithoutAnotherEvent() throws Exception {
        DebugTargetId target = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, null);
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            debugger.setTargets(target);
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            Future<Integer> start = launch(executor, debugger, connection, output,
                    "--include-user", "chosen", "-n", "test-area");
            try {
                target.setUserName("chosen");
                debugger.setTargets(target);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (debugger.requests("attachDetachDbgTargets").isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, debugger.requests("attachDetachDbgTargets").size());
                assertTrue(debugger.requests("getDbgTargets").stream().allMatch(request -> request.body.contains("test-area")));
                debugger.deliver(FakeDebugServer.measure(target, OBJECT_ID, PROPERTY_ID, 2, 1));
            } finally {
                send(connection, "stop");
            }
            assertEquals(0, start.get(10, TimeUnit.SECONDS));
            assertEquals(Map.of(2, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
        }
    }

    @Test
    void stopCollectsLateMeasuresAfterAnEmptyFinalPollAndAppliesTheUserFilter() throws Exception {
        DebugTargetId allowed = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, "chosen");
        DebugTargetId rejected = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, "other");
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            debugger.setTargets();
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            Future<Integer> start = launch(executor, debugger, connection, output, "--include-user", "chosen");
            debugger.finalPolls(List.of(), List.of(FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 6, 1),
                    FakeDebugServer.measure(rejected, OBJECT_ID, PROPERTY_ID, 4, 1)));
            assertEquals(0, send(connection, "stop"));
            assertEquals(0, start.get(10, TimeUnit.SECONDS));
            assertEquals(Map.of(6, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
        }
    }

    @Test
    void stopWaitsForTheInFlightPacketBeforeDecidingSuccessAndWritingTheReport() throws Exception {
        DebugTargetId allowed = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.MANAGED_CLIENT, "chosen");
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            debugger.setTargets();
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            CountDownLatch stopEntered = new CountDownLatch(1);
            CoverageCommand collector = new CoverageCommand() {
                @Override protected void gracefulShutdown(PrintWriter out) {
                    stopEntered.countDown();
                    super.gracefulShutdown(out);
                }
            };
            Future<Integer> start = launch(new CommandLine(collector), executor, debugger, connection, output,
                    "--include-user", "chosen");
            Future<Integer> stop;
            try (var pause = debugger.pauseNextPing(FakeDebugServer.measure(allowed, OBJECT_ID, PROPERTY_ID, 2, 1))) {
                assertTrue(pause.arrived.await(5, TimeUnit.SECONDS));
                stop = executor.submit(() -> send(connection, "stop"));
                assertTrue(stopEntered.await(5, TimeUnit.SECONDS), "stop reached the collector");
                assertThrows(TimeoutException.class, () -> stop.get(300, TimeUnit.MILLISECONDS));
            } finally {
                // Also finishes the collector if the synchronization assertion fails.
                send(connection, "stop");
            }
            assertEquals(0, stop.get(10, TimeUnit.SECONDS));
            assertEquals(0, start.get(10, TimeUnit.SECONDS));
            assertEquals(Map.of(2, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
        }
    }

    @Test
    void failedPrimaryPingMakesStartAndStopFailEvenWhenFinalPollsSucceed() throws Exception {
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            List<String> arguments = new ArrayList<>(List.of("start", "-t", "3000", "-o", output.toString()));
            arguments.addAll(connection);
            Future<Integer> start = executor.submit(() -> Coverage41C.getCommandLine().execute(arguments.toArray(String[]::new)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (debugger.requests("pingDebugUIParams").isEmpty() && !start.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            try {
                debugger.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
                debugger.finalPolls(List.of(), List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 6, 1)));
                debugger.failNextPing();
                assertTrue(debugger.awaitFailedPing());
            } finally {
                int stop = send(connection, "stop");
                assertAll(() -> assertEquals(1, stop), () -> assertEquals(1, start.get(10, TimeUnit.SECONDS)));
            }
            assertEquals(Map.of(2, true, 6, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
        }
    }

    @Test
    void failedFinalPollReturnsFailureButPreservesAlreadyCollectedHits() throws Exception {
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            Future<Integer> start = launch(executor, debugger, connection, output);
            debugger.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
            debugger.failFinalPolls();
            assertEquals(1, send(connection, "stop"));
            assertEquals(1, start.get(10, TimeUnit.SECONDS));
            assertEquals(Map.of(2, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
        }
    }

    @Test
    void failedUserLookupIsThrottledAcrossFinalPackets() throws Exception {
        DebugTargetId nameless = FakeDebugServer.target(UUID.randomUUID(), DebugTargetType.SERVER, null);
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            debugger.setTargets();
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            Future<Integer> start = launch(executor, debugger, connection, output, "--include-user", "chosen");
            debugger.failTargetLookups();
            debugger.finalPolls(List.of(FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 2, 1)),
                    List.of(FakeDebugServer.measure(nameless, OBJECT_ID, PROPERTY_ID, 4, 1)));
            assertEquals(1, send(connection, "stop"));
            assertEquals(1, start.get(10, TimeUnit.SECONDS));
            assertEquals(2, debugger.requests("getDbgTargets").size(), "initial listing and one failed lookup");
            assertEquals(0, Utils.newInputXmlBuilder().parse(output.toFile()).getElementsByTagName("file").getLength());
        }
    }

    @Test
    void aFailedFirstFinalPollDoesNotDiscardTheSecondPoll() throws Exception {
        failedFinalPollDoesNotDiscardTheOtherPoll(1);
    }

    @Test
    void aFailedSecondFinalPollPreservesTheFirstPoll() throws Exception {
        failedFinalPollDoesNotDiscardTheOtherPoll(2);
    }

    private void failedFinalPollDoesNotDiscardTheOtherPoll(int failedPoll) throws Exception {
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            Future<Integer> start = launch(executor, debugger, connection, output);
            debugger.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
            debugger.finalPolls(List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 6, 1)));
            debugger.failFinalPoll(failedPoll);
            assertEquals(1, send(connection, "stop"));
            assertEquals(1, start.get(10, TimeUnit.SECONDS));
            assertEquals(Map.of(2, true, 6, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
        }
    }

    @Test
    void malformedFinalPacketStillAllowsTheSecondPollAndTheFinalDump() throws Exception {
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            class Collector extends CoverageCommand {
                String stopDirectly() {
                    StringWriter reply = new StringWriter();
                    gracefulShutdown(new PrintWriter(reply, true));
                    return reply.toString().trim();
                }
            }
            Collector collector = new Collector();
            Future<Integer> start = launch(new CommandLine(collector), executor, debugger, connection, output);
            debugger.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
            var malformed = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 4, 1);
            malformed.setMeasure(null);
            debugger.finalPolls(List.of(malformed), List.of(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 6, 1)));
            // Use the existing hook to observe exceptions without leaving a socket client blocked.
            try {
                assertEquals(PipeMessages.FAIL_RESULT, collector.stopDirectly());
            } finally {
                collector.stopDirectly();
            }
            assertEquals(1, start.get(10, TimeUnit.SECONDS));
            assertEquals(PipeMessages.FAIL_RESULT, collector.stopDirectly(), "repeat stop retains failure");
            assertEquals(Map.of(2, true, 6, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
        }
    }

    @Test
    void malformedPrimaryPacketFailsAndStillSavesCollectedHits() throws Exception {
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            class Collector extends CoverageCommand {
                String stopDirectly() {
                    StringWriter reply = new StringWriter();
                    gracefulShutdown(new PrintWriter(reply, true));
                    return reply.toString().trim();
                }
            }
            Collector collector = new Collector();
            Future<Integer> start = launch(new CommandLine(collector), executor, debugger, connection, output);
            debugger.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
            var malformed = FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 4, 1);
            malformed.setMeasure(null);
            try (var pause = debugger.pauseNextPing(malformed)) {
                assertTrue(pause.arrived.await(5, TimeUnit.SECONDS));
            }
            try {
                assertEquals(1, start.get(10, TimeUnit.SECONDS));
                assertEquals(Map.of(2, true), ConfiguredCoverageTest.coverage(output, "/" + OBJECT_ID + "/" + PROPERTY_ID));
                assertEquals(PipeMessages.FAIL_RESULT, collector.stopDirectly());
            } finally {
                collector.stopDirectly();
            }
        }
    }

    @Test
    void failedFinalDumpCannotBecomeSuccessOnRepeatedStop() throws Exception {
        failedFinalDumpCannotBecomeSuccessOnRepeatedStop(false);
    }

    @Test
    void uncheckedFinalDumpFailureCannotBecomeSuccessOnRepeatedStop() throws Exception {
        failedFinalDumpCannotBecomeSuccessOnRepeatedStop(true);
    }

    private void failedFinalDumpCannotBecomeSuccessOnRepeatedStop(boolean throwsException) throws Exception {
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            AtomicInteger dumps = new AtomicInteger();
            class Collector extends CoverageCommand {
                @Override protected boolean writeCoverageDump() {
                    dumps.incrementAndGet();
                    if (throwsException) throw new IllegalStateException("Output failed");
                    return false;
                }
                String stopDirectly() {
                    StringWriter reply = new StringWriter();
                    gracefulShutdown(new PrintWriter(reply, true));
                    return reply.toString().trim();
                }
            }
            Collector collector = new Collector();
            Future<Integer> start = launch(new CommandLine(collector), executor, debugger, connection,
                    directory.resolve("raw.xml"));
            try {
                assertEquals(PipeMessages.FAIL_RESULT, collector.stopDirectly());
            } finally {
                collector.stopDirectly();
            }
            assertEquals(1, start.get(10, TimeUnit.SECONDS));
            assertEquals(PipeMessages.FAIL_RESULT, collector.stopDirectly());
            assertEquals(1, dumps.get(), "repeat stop does not repeat output");
        }
    }

    @Test
    void rawCleanRemovesPreviouslyObservedLines() throws Exception {
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("raw.xml");
            Future<Integer> start = launch(executor, debugger, connection, output);
            try {
                debugger.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1));
                assertEquals(0, send(connection, "clean"));
            } finally {
                assertEquals(0, send(connection, "stop"));
            }
            assertEquals(0, start.get(10, TimeUnit.SECONDS));
            var xml = Utils.newInputXmlBuilder().parse(output.toFile());
            assertEquals(0, xml.getElementsByTagName("lineToCover").getLength());
        }
    }

    @Test
    void configuredAutoLinesRemainOptionalAfterClean() throws Exception {
        Path project = TestUtils.copyConfiguration(directory);
        Files.writeString(project.resolve("configuration").resolve(MODULE), ConfiguredCoverageTest.SOURCE);
        Path config = Files.writeString(directory.resolve("coverage41c.json"), """
                {"auto":{"lines":["Второй"]}}
                """);
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Path output = directory.resolve("coverage.xml");
            Future<Integer> start = launch(executor, debugger, connection, output, "-P", project.toString(),
                    "-s", "configuration", "--config", config.toString());
            try {
                debugger.deliver(FakeDebugServer.measure(OBJECT_ID, PROPERTY_ID, 2, 1, 4, 1));
                assertEquals(0, send(connection, "dump"));
                assertEquals(Map.of(2, true, 4, true, 6, false),
                        ConfiguredCoverageTest.coverage(output, "configuration/" + MODULE));
                assertEquals(0, send(connection, "clean"));
            } finally {
                assertEquals(0, send(connection, "stop"));
            }
            assertEquals(0, start.get(10, TimeUnit.SECONDS));
            assertEquals(Map.of(2, false, 6, false),
                    ConfiguredCoverageTest.coverage(output, "configuration/" + MODULE));
        }
    }

    @Test
    void dumpAndStopReportWriteFailureAndDumpCanRetry() throws Exception {
        Path project = TestUtils.copyConfiguration(directory);
        Path output = directory.resolve("coverage.xml");
        try (FakeDebugServer debugger = new FakeDebugServer();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            List<String> connection = List.of("-u", debugger.url(), "-i", "test_" + UUID.randomUUID());
            Future<Integer> start = launch(executor, debugger, connection, output,
                    "-s", project.resolve("configuration").toString());
            int failedDump;
            int retriedDump;
            int stop;
            try {
                Files.createDirectory(output);
                failedDump = send(connection, "dump");
                Files.delete(output);
                retriedDump = send(connection, "dump");
                assertTrue(Files.isRegularFile(output));
                Files.delete(output);
                Files.createDirectory(output);
            } finally {
                stop = send(connection, "stop");
            }
            int result = start.get(10, TimeUnit.SECONDS);
            assertAll(() -> assertEquals(1, failedDump, "dump with unwritable destination"),
                    () -> assertEquals(0, retriedDump, "dump after restoring the destination"),
                    () -> assertEquals(1, stop, "stop with unwritable destination"),
                    () -> assertEquals(1, result, "start after a failed final write"));
        }
    }

    @Test
    void earlyCleanWaitsForConfiguredAutoLines() throws Exception {
        assertEarlyCleanWaitsForPreparation(true);
    }

    @Test
    void earlyCleanWaitsForLegacyPreparation() throws Exception {
        assertEarlyCleanWaitsForPreparation(false);
    }

    @Test
    void earlyDumpCannotOverwriteASourceRejectedByPreparation() throws Exception {
        Path project = TestUtils.copyConfiguration(directory);
        Path source = Files.writeString(project.resolve("configuration").resolve(MODULE), ConfiguredCoverageTest.SOURCE);
        Path config = Files.writeString(directory.resolve("coverage41c.json"), "{\"changeMarkers\":{}}");
        List<String> connection = List.of("-u", "http://127.0.0.1:0", "-i", "test_" + UUID.randomUUID());
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        CoverageCommand command = new CoverageCommand() {
            @Override
            protected ServerSocket getServerSocket() throws IOException {
                ServerSocket socket = super.getServerSocket();
                listening.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for the test to resume preparation");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return socket;
            }
        };
        List<String> arguments = new ArrayList<>(connection);
        arguments.addAll(List.of("-s", project.resolve("configuration").toString(), "--config", config.toString(),
                "-o", source.toString()));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> start = executor.submit(() -> new CommandLine(command).execute(arguments.toArray(String[]::new)));
            try {
                assertTrue(listening.await(5, TimeUnit.SECONDS));
                Future<Integer> dump = executor.submit(() -> send(connection, "dump"));
                assertThrows(TimeoutException.class, () -> dump.get(1, TimeUnit.SECONDS));
                resume.countDown();
                assertNotEquals(0, start.get(10, TimeUnit.SECONDS));
                assertNotEquals(0, dump.get(10, TimeUnit.SECONDS));
                assertEquals(ConfiguredCoverageTest.SOURCE, Files.readString(source));
            } finally {
                resume.countDown();
            }
        }
    }

    private void assertEarlyCleanWaitsForPreparation(boolean configured) throws Exception {
        Path project = TestUtils.copyConfiguration(directory);
        Files.writeString(project.resolve("configuration").resolve(MODULE), ConfiguredCoverageTest.SOURCE);
        Path config = Files.writeString(directory.resolve("coverage41c.json"), "{\"auto\":{\"lines\":[\"Второй\"]}}");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean waitedForPreparation = new AtomicBoolean();
        AtomicBoolean autoLinePreserved = new AtomicBoolean();
        CoverageCommand command = new CoverageCommand() {
            @Override
            protected ServerSocket getServerSocket() {
                Thread thread = Thread.ofPlatform().daemon().start(() -> {
                    try {
                        cleanCoverageData();
                        autoLinePreserved.set(getCoverageData().values().stream()
                                .anyMatch(lines -> lines.containsValue(-1)));
                    } finally {
                        done.countDown();
                    }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (done.getCount() != 0 && System.nanoTime() < deadline) {
                    if (thread.getState() == Thread.State.BLOCKED) {
                        waitedForPreparation.set(true);
                        break;
                    }
                    Thread.yield();
                }
                return null;
            }
        };

        List<String> arguments = new ArrayList<>(List.of("-P", project.toString(), "-s", "configuration",
                "-o", directory.resolve("coverage.xml").toString(), "-u", "http://127.0.0.1:0"));
        if (configured) {
            arguments.addAll(List.of("--config", config.toString()));
        }
        int result = new CommandLine(command).execute(arguments.toArray(String[]::new));
        assertNotEquals(0, result);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(waitedForPreparation.get());
        assertEquals(configured, autoLinePreserved.get());
    }

    private static Future<Integer> launch(ExecutorService executor, FakeDebugServer debugger,
                                          List<String> connection, Path output, String... options) throws Exception {
        return launch(new CommandLine(new CoverageCommand()), executor, debugger, connection, output, options);
    }

    private static Future<Integer> launch(CommandLine cli, ExecutorService executor, FakeDebugServer debugger,
                                          List<String> connection, Path output, String... options) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("-o", output.toString(), "-t", "10"));
        arguments.addAll(connection);
        arguments.addAll(Arrays.asList(options));
        Future<Integer> start = executor.submit(() -> cli.execute(arguments.toArray(String[]::new)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (debugger.requests("pingDebugUIParams").isEmpty()) {
            if (start.isDone() || System.nanoTime() > deadline) {
                start.cancel(true);
                throw new IllegalStateException("Coverage41C did not start polling the debugger");
            }
            Thread.sleep(10);
        }
        return start;
    }

    private static int send(List<String> connection, String command) {
        List<String> arguments = new ArrayList<>(List.of(command));
        arguments.addAll(connection);
        return Coverage41C.getCommandLine().execute(arguments.toArray(String[]::new));
    }
}
