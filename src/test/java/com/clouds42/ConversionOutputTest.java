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
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConversionOutputTest {
    @TempDir Path directory;

    @Test
    void failureAfterWritingPartOfTemporaryJsonPreservesBothReports() throws Exception {
        Path xml = Files.writeString(directory.resolve("coverage.xml"), "<old/>");
        Path json = Files.writeString(directory.resolve("report.json"), "{}");
        assertThrows(IOException.class, () -> ConversionOutput.write(xml, json,
                file -> Files.writeString(file, "<new/>"), file -> {
                    Files.writeString(file, "{");
                    throw new IOException("Disk full");
                }, OutputStream.nullOutputStream()));
        assertEquals("<old/>", Files.readString(xml));
        assertEquals("{}", Files.readString(json));
        assertDirectorySize(2);
    }

    @Test
    void failedCoverageSerializationPreservesAnExistingStandaloneReport() throws Exception {
        Path xml = Files.writeString(directory.resolve("coverage.xml"), "<old/>");
        assertThrows(IOException.class, () -> ConversionOutput.write(xml, null, file -> {
            Files.writeString(file, "<");
            throw new IOException("Disk full");
        }, null, OutputStream.nullOutputStream()));
        assertEquals("<old/>", Files.readString(xml));
        assertDirectorySize(1);
    }

    @Test
    void failedStreamPublicationRestoresPreviousJsonAndRemovesNewJson() throws Exception {
        Path json = directory.resolve("report.json");
        OutputStream failure = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Broken pipe"); }
        };
        for (boolean exists : new boolean[]{false, true}) {
            if (exists) {
                Files.writeString(json, "{\"old\":true}");
            }
            assertThrows(IOException.class, () -> ConversionOutput.write(null, json,
                    file -> Files.writeString(file, "<new/>"), file -> Files.writeString(file, "{}"), failure));
            assertEquals(exists, Files.exists(json));
            if (exists) {
                assertEquals("{\"old\":true}", Files.readString(json));
            }
            assertDirectorySize(exists ? 1 : 0);
        }
    }

    @Test
    void lockedSecondOutputPreservesBothExistingAndNewDestinations() throws Exception {
        Path xml = directory.resolve("coverage.xml");
        Path json = Files.writeString(directory.resolve("report.json"), "{}");
        try (FileChannel channel = FileChannel.open(json, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertTrue(lock.isValid());
            assertThrows(IOException.class, () -> ConversionOutput.write(xml, json,
                    file -> Files.writeString(file, "<new/>"), file -> Files.writeString(file, "[]"),
                    OutputStream.nullOutputStream()));
            assertFalse(Files.exists(xml));
            assertEquals("{}", Files.readString(json));
        }
        assertDirectorySize(1);
    }

    @Test
    void partialFailureOnSecondRegularFileRollsBackBothOutputs() throws Exception {
        Path xml = Files.writeString(directory.resolve("coverage.xml"), "<old/>");
        Path json = Files.writeString(directory.resolve("report.json"), "{}");
        assertThrows(IOException.class, () -> ConversionOutput.publish(xml, json, output -> {
            output.write(123);
            throw new IOException("Interrupted write");
        }, output -> output.write(42), OutputStream.nullOutputStream()));
        assertEquals("<old/>", Files.readString(xml));
        assertEquals("{}", Files.readString(json));
        assertDirectorySize(2);
    }

    @Test
    void specialCoverageOutputKeepsItsStreamSemantics() throws Exception {
        Path sink = Path.of("/dev/null");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(sink) && !Files.isRegularFile(sink));
        Path json = Files.writeString(directory.resolve("report.json"), "{}");
        ConversionOutput.write(sink, json, file -> Files.writeString(file, "<new/>"),
                file -> Files.writeString(file, "[]"), OutputStream.nullOutputStream());
        assertFalse(Files.isRegularFile(sink));
        assertEquals("[]", Files.readString(json));
    }

    @Test
    void twoFailedPublishersOfNewReportsLeaveNoEmptyOutput() throws Exception {
        Path xml = directory.resolve("coverage.xml");
        Path json = directory.resolve("report.json");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 64; attempt++) {
                CountDownLatch start = new CountDownLatch(1);
                Callable<Void> publication = () -> {
                    start.await();
                    assertThrows(IOException.class, () -> ConversionOutput.publish(xml, json, output -> {
                        output.write(1);
                        throw new IOException("Injected failure");
                    }, output -> output.write(2), OutputStream.nullOutputStream()));
                    return null;
                };
                Future<Void> first = workers.submit(publication);
                Future<Void> second = workers.submit(publication);
                start.countDown();
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
                assertDirectorySize(0);
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void newReportsInheritNormalOutputPermissions() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path reference = Files.createFile(directory.resolve("reference"));
        Path xml = directory.resolve("coverage.xml");
        Path json = directory.resolve("report.json");
        ConversionOutput.write(xml, json, file -> Files.writeString(file, "<new/>"),
                file -> Files.writeString(file, "[]"), OutputStream.nullOutputStream());
        assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(xml));
        assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(json));
        assertDirectorySize(3);
    }

    @Test
    void localContentionDoesNotReleaseTheLockSeenByAnotherJvm() throws Exception {
        Path xml = Files.writeString(directory.resolve("coverage.xml"), "old");
        Path json = Files.writeString(directory.resolve("report.json"), "{}");
        String classes = Path.of(ExternalLockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ConversionOutput.publish(xml, json, out -> out.write(1), out -> {
            assertThrows(IOException.class, () -> ConversionOutput.publish(xml, json,
                    bytes -> bytes.write(2), bytes -> bytes.write(3), OutputStream.nullOutputStream()));
            Process probe = new ProcessBuilder(java.toString(), "-cp", classes,
                    ExternalLockProbe.class.getName(), xml.toString()).inheritIO().start();
            try {
                assertTrue(probe.waitFor(15, TimeUnit.SECONDS));
                assertEquals(0, probe.exitValue(), "An independent JVM acquired the active output");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            } finally {
                probe.destroyForcibly();
            }
        }, OutputStream.nullOutputStream());
    }

    public static final class ExternalLockProbe {
        public static void main(String[] args) throws IOException {
            try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock != null) {
                    throw new IOException("Acquired a file locked by another publisher");
                }
            }
        }
    }

    private void assertDirectorySize(long expected) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(expected, files.count());
        }
    }
}
