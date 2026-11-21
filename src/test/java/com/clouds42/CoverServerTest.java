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
import com.clouds42.Commands.CoverageCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void startUsesTheOverriddenServerSocket() {
        IOException failure = new IOException("listener unavailable");
        CoverageCommand command = new CoverageCommand() {
            @Override
            protected ServerSocket getServerSocket() throws IOException {
                throw failure;
            }
        };
        CommandLine.populateCommand(command, "-i", "socket-test-" + UUID.randomUUID(),
                "-u", "http://127.0.0.1:1/");
        assertSame(failure, assertThrows(IOException.class, command::call));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void existingServerSubclassHandlesCleanAndStop() throws Exception {
        String alias = "socket-test-" + UUID.randomUUID();
        Path path = socketPath(alias);
        var hits = new HashMap<>(Map.of(BigDecimal.ONE, 5));
        var server = new Server(Map.of(tempDir.resolve("Module.bsl").toUri(), hits),
                tempDir.resolve("coverage.xml"));
        server.connectionOptions = CommandLine.populateCommand(new ConnectionOptions(), "-i", alias);
        try {
            server.start();
            assertEquals(CommandLine.ExitCode.OK, Coverage41C.getCommandLine().execute("clean", "-i", alias));
            assertEquals(Map.of(BigDecimal.ONE, 0), hits);
            assertEquals(CommandLine.ExitCode.OK, Coverage41C.getCommandLine().execute("stop", "-i", alias));
        } finally {
            server.stop();
            Files.deleteIfExists(lockPath(path));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void closingTheReturnedSocketReleasesTheListenerAndAllowsRestart() throws Exception {
        String alias = "socket-test-" + UUID.randomUUID();
        Path path = socketPath(alias);
        Server server = socketServer(alias);
        try {
            ServerSocket first = server.socket();
            assertSame(first, server.socket());
            assertTrue(first.isBound());
            assertEquals("OK", check(path));

            first.close();
            assertTrue(first.isClosed());
            assertFalse(Files.exists(path));

            ServerSocket second = server.socket();
            assertNotSame(first, second);
            first.close();
            assertEquals("OK", check(path));
        } finally {
            server.stop();
            Files.deleteIfExists(lockPath(path));
        }
    }

    private static String check(Path path) throws Exception {
        try (var socket = new org.scalasbt.ipcsocket.UnixDomainSocket(path.toString())) {
            new PrintWriter(socket.getOutputStream(), true).println("check");
            return reply(new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unixCommandsUseTheSameNormalizedAliasAsTheListener() throws Exception {
        String alias = "socket-test-" + UUID.randomUUID();
        String nestedAlias = "unused/../" + alias;
        Path path = socketPath(alias);
        Server server = socketServer(nestedAlias);
        try {
            server.start();
            assertEquals(CommandLine.ExitCode.OK, Coverage41C.getCommandLine().execute("check",
                    "-i", nestedAlias));
        } finally {
            server.stop();
            Files.deleteIfExists(lockPath(path));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unixLockAllowsOnlyOwnerAccessEvenWithPermissiveUmask() throws Exception {
        String alias = "socket-test-" + UUID.randomUUID();
        Path socket = socketPath(alias);
        Path lock = lockPath(socket);
        Process child = new ProcessBuilder("/bin/sh", "-c", "umask 000 && exec \"$@\"", "socket-test",
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                SocketProbe.class.getName(), alias).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes()));
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(lock));
            assertFalse(Files.exists(socket));
        } finally {
            child.destroyForcibly();
            Files.deleteIfExists(socket);
            Files.deleteIfExists(lock);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsAnotherSocketNameForTheSameLockInode() throws Exception {
        String alias = "socket-test-" + UUID.randomUUID();
        String otherAlias = "socket-test-" + UUID.randomUUID();
        Path firstLock = lockPath(socketPath(alias));
        Path otherLock = lockPath(socketPath(otherAlias));
        Server first = socketServer(alias);
        Server other = socketServer(otherAlias);
        try {
            first.start();
            Files.createLink(otherLock, firstLock);
            var error = assertThrows(IOException.class, other::start);
            assertTrue(error.getMessage().contains("already running"), error.getMessage());
        } finally {
            first.stop();
            other.stop();
            Files.deleteIfExists(firstLock);
            Files.deleteIfExists(otherLock);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void nativeStderrRecoversAfterBrokenPipeDespiteStickyPrintStreamError() throws Exception {
        Path fifo = tempDir.resolve("statistics.fifo");
        Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
        try {
            assertTrue(mkfifo.waitFor(10, TimeUnit.SECONDS), "mkfifo did not finish");
            assertEquals(0, mkfifo.exitValue());
        } finally {
            mkfifo.destroyForcibly();
        }
        String alias = "statistics-" + UUID.randomUUID();
        Path socket = socketPath(alias);
        Process child = null;
        InputStream errors = null;
        try {
            // Linux permits a read/write FIFO open without a peer. The temporary descriptor
            // makes both the child redirection and the reader open safe even if startup fails.
            try (var startup = FileChannel.open(fifo, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dorg.slf4j.simpleLogger.defaultLogLevel=off", "-cp",
                        System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path")),
                        NativeStatisticsProbe.class.getName(), alias).redirectError(fifo.toFile()).start();
                errors = Files.newInputStream(fifo);
            }
            var replies = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));
            var commands = new PrintWriter(child.getOutputStream(), true);
            assertEquals("READY", reply(replies));
            commands.println("stats");
            assertEquals("OK", reply(replies));
            assertTrue(readReport(errors).contains("Covered lines"));
            errors.close();
            errors = null;
            commands.println("stats");
            assertEquals("ER", reply(replies));
            commands.println("stats");
            assertEquals("ER", reply(replies));
            commands.println("taint");
            assertEquals("true", reply(replies));
            try (var reopening = FileChannel.open(fifo, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                errors = Files.newInputStream(fifo);
            }
            commands.println("descriptors");
            String descriptorsBefore = reply(replies);
            for (int i = 0; i < 50; i++) {
                commands.println("stats");
                assertEquals("OK", reply(replies));
                assertTrue(readReport(errors).contains("Covered lines"));
            }
            commands.println("descriptors");
            assertEquals(descriptorsBefore, reply(replies), "statistics must not leak file descriptors");
            commands.println("sticky");
            assertEquals("true", reply(replies), "native output must not clear or replace System.err");
            commands.println("quit");
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            try {
                // Closing the writer unblocks any report read left pending after a timeout.
                if (child != null) {
                    child.destroyForcibly();
                    child.waitFor(10, TimeUnit.SECONDS);
                }
            } finally {
                try (InputStream reader = errors) {
                    Files.deleteIfExists(socket);
                    Files.deleteIfExists(lockPath(socket));
                }
            }
        }
    }

    private static String readReport(InputStream errors) throws Exception {
        var report = CompletableFuture.supplyAsync(() -> {
            try {
                byte[] buffer = new byte[8192];
                int count = errors.read(buffer);
                return count < 0 ? "" : new String(buffer, 0, count, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            return report.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return fail("stats returned OK without writing a report to stderr", e);
        }
    }

    private static String reply(java.io.BufferedReader replies) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return replies.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    public static final class NativeStatisticsProbe {
        public static void main(String[] args) throws Exception {
            Server server = socketServer(args[0]);
            server.start();
            try {
                var commands = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                System.out.println("READY");
                String command;
                while ((command = commands.readLine()) != null && !command.equals("quit")) {
                    if (command.equals("taint")) {
                        System.err.println("broken pipe");
                        System.out.println(System.err.checkError());
                    } else if (command.equals("sticky")) {
                        System.out.println(System.err.checkError());
                    } else if (command.equals("descriptors")) {
                        // Count after accepted sockets have closed, independent of worker scheduling.
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (Thread.getAllStackTraces().keySet().stream()
                                .anyMatch(thread -> thread.getName().equals("coverage41c-command"))) {
                            if (System.nanoTime() >= deadline) {
                                throw new AssertionError("Command workers did not finish");
                            }
                            Thread.sleep(5);
                        }
                        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
                            System.out.println(descriptors.count());
                        }
                    } else {
                        try (var socket = new org.scalasbt.ipcsocket.UnixDomainSocket(
                                Utils.getPipeName(server.getConnectionOptions()))) {
                            new PrintWriter(socket.getOutputStream(), true).println("stats");
                            System.out.println(new java.io.BufferedReader(new java.io.InputStreamReader(
                                    socket.getInputStream())).readLine());
                        }
                    }
                }
            } finally {
                server.stop();
            }
        }
    }

    private static Server socketServer(String alias) {
        var server = new Server(Map.of(), Path.of("/tmp", "unused-coverage.xml"));
        server.connectionOptions = CommandLine.populateCommand(new ConnectionOptions(), "-i", alias);
        return server;
    }

    private static Path socketPath(String alias) throws IOException {
        return Path.of(Utils.getPipeName(CommandLine.populateCommand(new ConnectionOptions(), "-i", alias)));
    }

    private static Path lockPath(Path socket) {
        return socket.resolveSibling(socket.getFileName() + ".lock");
    }

    public static final class SocketProbe {
        public static void main(String[] args) throws IOException {
            Server server = socketServer(args[0]);
            server.start();
            server.stop();
        }
    }

    private static final class Server extends CoverServer {
        private final Map<URI, Map<BigDecimal, Integer>> coverageData;
        private final MetadataOptions metadataOptions;
        private final OutputOptions outputOptions;
        private ConnectionOptions connectionOptions;

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
            return connectionOptions;
        }

        void start() throws IOException {
            startListening();
        }

        ServerSocket socket() throws IOException {
            return getServerSocket();
        }

        void stop() throws IOException {
            closeSocket();
        }

        @Override
        protected boolean getSystemStarted() {
            return true;
        }

        @Override
        protected void gracefulShutdown(PrintWriter out) throws IOException {
            out.println(PipeMessages.OK_RESULT);
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
