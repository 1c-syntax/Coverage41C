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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
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
    void movingThePublishedReportDoesNotExposeEitherInodeToAnEmbeddedDump() throws Exception {
        Path named = directory.resolve("report.json");
        Path moved = directory.resolve("moved.json");
        try (ConversionOutput.PublishedReport published = new ConversionOutput.PublishedReport()) {
            published.write(named, stage -> Files.writeString(stage, "first"));
            Files.move(named, moved);
            // The reader has moved the report, leaving its original pathname vacant.
            try (var stream = Files.newOutputStream(moved, StandardOpenOption.APPEND)) {
                assertThrows(IOException.class, () -> ConversionOutput.writeSeparate(stream, named.toFile(), published,
                        out -> out.write(42)));
            }
            assertFalse(Files.exists(named));
            assertEquals("first", Files.readString(moved));
            // Another producer may reuse the name between dumps without waiting for stop.
            ConversionOutput.writeFile(named, stage -> Files.writeString(stage, "second"));
            for (Path target : List.of(named, moved)) {
                try (var stream = Files.newOutputStream(target, StandardOpenOption.APPEND)) {
                    assertThrows(IOException.class, () -> ConversionOutput.writeSeparate(stream, named.toFile(), published,
                            out -> out.write(42)));
                }
                assertEquals("first", Files.readString(moved));
                assertEquals("second", Files.readString(named));
            }
        }
    }

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
    void updatesWriteOnlyReportInPlaceAndDelaysItUntilOtherOutputsSucceed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path report = Files.writeString(directory.resolve("report"), "original");
        Path alias = Files.createLink(directory.resolve("alias"), report);
        var writeOnly = PosixFilePermissions.fromString("-w-------");
        Files.setPosixFilePermissions(report, writeOnly);
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(!Files.isReadable(report));
            Path second = directory.resolve("second");
            assertThrows(IOException.class, () -> ConversionOutput.publish(second, report,
                    out -> { throw new IOException("coverage failed"); }, out -> out.write('j'), null));
            assertThrows(IOException.class, () -> ConversionOutput.write(null, report,
                    file -> Files.writeString(file, "coverage"), file -> Files.writeString(file, "json"), OutputStream.nullOutputStream()));
            Files.setPosixFilePermissions(report, PosixFilePermissions.fromString("rw-------"));
            assertEquals("original", Files.readString(alias));
            Files.setPosixFilePermissions(report, writeOnly);
            assertFalse(Files.exists(second));
            assertThrows(IOException.class, () -> ConversionOutput.publish(report, null, out -> {
                out.write('x');
                throw new IOException("interrupted write");
            }, null, null));
            var capture = new java.io.ByteArrayOutputStream();
            ConversionOutput.write(null, report, stage -> Files.writeString(stage, "coverage"),
                    stage -> Files.writeString(stage, "replacement"), capture);
            assertEquals("coverage", capture.toString(StandardCharsets.UTF_8));
            assertEquals(writeOnly, Files.getPosixFilePermissions(report));
            assertTrue(Files.isSameFile(report, alias));
        } finally {
            Files.setPosixFilePermissions(report, PosixFilePermissions.fromString("rw-------"));
        }
        assertEquals("replacement", Files.readString(alias));
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
    void markerRollbackRemovesUnmodifiedOutputWhenBasicFileKeyIsMissing() throws Exception {
        Path coverage = directory.resolve("coverage.xml");
        Path report = Files.writeString(directory.resolve("report.json"), "{}");
        ConversionOutput.FileKeys keys = markerKeysWithoutBasicIdentity(ConversionOutputTest::readUnixIdentity);
        try (FileChannel holder = FileChannel.open(report, StandardOpenOption.WRITE);
             FileLock lock = holder.lock()) {
            IOException error = assertThrows(IOException.class, () -> ConversionOutput.publish(coverage, report,
                    output -> output.write(1), output -> output.write(2), OutputStream.nullOutputStream(), keys));
            assertTrue(error.getMessage().contains("Another conversion is writing"), error.toString());
            assertFalse(Files.exists(coverage));
            assertEquals("{}", Files.readString(report));
            assertTrue(lock.isValid());
            assertDirectorySize(1);
        }

        ConversionOutput.publish(coverage, report, output -> output.write('x'), output -> output.write('j'),
                OutputStream.nullOutputStream(), keys);
        assertEquals("x", Files.readString(coverage));
        assertEquals("j", Files.readString(report));
        try (FileChannel opened = FileChannel.open(coverage, StandardOpenOption.WRITE);
             FileLock lock = opened.tryLock()) {
            assertNotNull(lock);
        }
        assertDirectorySize(2);
    }

    @Test
    void markerRollbackRetainsFileAndSymlinkReplacementsWhenBasicKeyIsMissing() throws Exception {
        ConversionOutput.FileKeys keys = markerKeysWithoutBasicIdentity(ConversionOutputTest::readUnixIdentity);
        for (String replacement : List.of("file", "symlink")) {
            Path report = directory.resolve(replacement + ".json");
            Path moved = directory.resolve(replacement + "-moved.json");
            IOException error = assertThrows(IOException.class, () -> ConversionOutput.publish(null, report, output -> {
                assertFalse(Files.exists(report));
                if (replacement.equals("symlink")) {
                    Files.writeString(moved, "independent");
                    Files.createSymbolicLink(report, moved.getFileName());
                } else {
                    Files.writeString(report, "replacement");
                }
                throw new IOException("coverage stream failed");
            }, output -> output.write('j'), OutputStream.nullOutputStream(), keys));
            assertEquals("coverage stream failed", error.getMessage());
            if (replacement.equals("symlink")) {
                assertTrue(Files.isSymbolicLink(report));
                assertEquals("independent", Files.readString(report));
            } else {
                assertEquals("replacement", Files.readString(report));
            }
        }
        assertDirectorySize(3);
    }

    @Test
    void rejectsExistingDestinationReplacedBeforeLockWhenBasicKeyIsMissing() throws Exception {
        Path coverage = directory.resolve("coverage.xml");
        Path report = Files.writeString(directory.resolve("report.json"), "{}");
        Path moved = directory.resolve("moved.json");
        ConversionOutput.FileKeys keys = markerKeysWithoutBasicIdentity((path, options) -> {
            Object key = readUnixIdentity(path, options);
            if (path.equals(report) && !Files.exists(moved)) {
                Files.move(report, moved);
                Files.writeString(report, "replacement");
            }
            return key;
        });
        IOException error = assertThrows(IOException.class, () -> ConversionOutput.publish(coverage, report,
                output -> output.write('x'), output -> output.write('j'), OutputStream.nullOutputStream(), keys));
        assertTrue(error.getMessage().contains("Report destination changed"), error.toString());
        assertFalse(Files.exists(coverage));
        assertEquals("{}", Files.readString(moved));
        assertEquals("replacement", Files.readString(report));
        try (FileChannel opened = FileChannel.open(report, StandardOpenOption.WRITE);
             FileLock lock = opened.tryLock()) {
            assertNotNull(lock);
        }
        assertDirectorySize(2);
    }

    @Test
    void markerRejectsFilesystemsWithoutAnyIdentityBeforeCreatingOutput() throws Exception {
        Path coverage = directory.resolve("coverage.xml");
        Path report = Files.writeString(directory.resolve("report.json"), "old");
        ConversionOutput.FileKeys keys = new ConversionOutput.FileKeys(false, attributes -> null, (path, options) -> null);

        IOException error = assertThrows(IOException.class, () -> ConversionOutput.publish(coverage, report,
                output -> output.write('x'), output -> output.write('j'), OutputStream.nullOutputStream(), keys));
        assertTrue(error.getMessage().contains("Cannot identify new report destination"), error.toString());
        assertFalse(Files.exists(coverage));
        assertEquals("old", Files.readString(report));
        assertDirectorySize(1);
    }

    @Test
    void independentJvmCanPublishOtherReportsWhileFallbackCreationIsPending() throws Exception {
        ConversionOutput.FileKeys keys = markerKeysWithBasicIdentity();
        Path coverage = directory.resolve("покрытие+%.xml");
        Path unrelated = directory.resolve("unrelated.json");
        Path existing = Files.writeString(directory.resolve("existing.json"), "old");
        Path coordination = Files.createDirectory(directory.resolve("coordination"));
        Path alias = Files.createSymbolicLink(coordination.resolve("alias.xml"), coverage);
        Path ready = coordination.resolve("ready");
        Path release = coordination.resolve("release");
        String classpath = System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path"));
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(javaExecutable.toString(), "-cp", classpath, ExternalCreationHolder.class.getName(),
                coverage.toString(), ready.toString(), release.toString()).redirectErrorStream(true).start();
        try {
            awaitMarkerSignal(ready);
            assertAll(
                    () -> assertThrows(IOException.class, () -> publishMarkerOutput(coverage, keys)),
                    () -> assertThrows(IOException.class, () -> publishMarkerOutput(alias, keys)),
                    () -> {
                        publishMarkerOutput(unrelated, keys);
                        assertEquals("x", Files.readString(unrelated));
                    },
                    () -> {
                        publishMarkerOutput(existing, keys);
                        assertEquals("x", Files.readString(existing));
                    });
            assertFalse(Files.exists(coverage));
            Files.createFile(release);
            assertTrue(holder.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, holder.exitValue(), new String(holder.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("x", Files.readString(coverage));
            assertDirectorySize(4);
        } finally {
            holder.destroyForcibly();
            assertTrue(holder.waitFor(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void standaloneOutputKeepsTheCreationNamespaceName() throws IOException {
        Path coverage = directory.resolve(".coverage41c-creating");

        ConversionOutput.writeFile(coverage, staged -> Files.writeString(staged, "<coverage/>"));

        assertEquals("<coverage/>", Files.readString(coverage));
        assertDirectorySize(1);
    }

    @Test
    void newPairCanUseBothPreferredCreationNamespaceNames() throws IOException {
        Path coverage = directory.resolve(".coverage41c-creating");
        Path report = directory.resolve(".coverage41c-creating-1");

        ConversionOutput.publish(coverage, report, output -> output.write('x'), output -> output.write('j'), null);

        assertEquals("x", Files.readString(coverage));
        assertEquals("j", Files.readString(report));
        assertDirectorySize(2);
    }

    @Test
    void posixCanPublishWithEveryMarkerNamespaceOccupiedByAReport() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        List<String> names = List.of(".coverage41c-creating", ".coverage41c-creating-1", ".coverage41c-creating-2", "coverage.xml");

        for (String name : names) {
            ConversionOutput.writeNewFile(directory.resolve(name), staged -> Files.writeString(staged, name));
        }

        for (String name : names) {
            assertEquals(name, Files.readString(directory.resolve(name)));
        }
        assertDirectorySize(names.size());
    }

    @Test
    void pairFailsBeforeWritingWhenOneNewNameCannotBeReserved() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        for (String name : List.of(".coverage41c-creating", ".coverage41c-creating-1", ".coverage41c-creating-2")) {
            Files.writeString(second.resolve(name), name);
        }
        Path coverage = first.resolve("coverage.xml");
        Path report = second.resolve("exclusions.json");
        IOException failure = assertThrows(IOException.class, () -> ConversionOutput.publish(coverage, report,
                output -> fail("Coverage was prepared without both reservations"),
                output -> fail("Exclusions were prepared without both reservations"), OutputStream.nullOutputStream()));
        assertTrue(failure.getMessage().contains("reserve both new reports"), failure.toString());
        assertFalse(Files.exists(coverage));
        assertFalse(Files.exists(report));
        try (Stream<Path> entries = Files.list(first)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void lastPublisherRemovesNamespaceCreatedByAnotherJvm() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix"));
        Path namespace = directory.resolve(".coverage41c-creating");
        Path childOutput = directory.resolve("child.xml");
        Path ready = directory.resolve("ready");
        Path release = directory.resolve("release");
        String classpath = System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path"));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process holder = new ProcessBuilder(java.toString(), "-cp", classpath, ExternalCreationHolder.class.getName(),
                childOutput.toString(), ready.toString(), release.toString()).redirectErrorStream(true).start();
        try {
            awaitMarkerSignal(ready);
            assertTrue(Files.isDirectory(namespace));
            ConversionOutput.publish(directory.resolve("coverage.xml"), directory.resolve("exclusions.json"), output -> {
                Files.createFile(release);
                assertEquals(0, holder.onExit().orTimeout(15, TimeUnit.SECONDS).join().exitValue());
                output.write('x');
            }, output -> output.write('j'), OutputStream.nullOutputStream());

            assertEquals("x", Files.readString(childOutput));
            assertFalse(Files.exists(namespace), "The last publisher must remove its empty service directory");
            ConversionOutput.writeNewFile(namespace, staged -> Files.writeString(staged, "<coverage/>"));
            assertEquals("<coverage/>", Files.readString(namespace));
        } finally {
            holder.destroyForcibly();
            assertTrue(holder.waitFor(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void pairClaimInAlternateNamespaceExcludesOtherPublishers() throws IOException {
        Path coverage = directory.resolve(".coverage41c-creating");
        Path report = directory.resolve("report.json");
        Path unrelated = directory.resolve("unrelated.xml");
        Path existingNamespace = Files.createDirectory(directory.resolve(".coverage41c-creating-1"));
        ConversionOutput.FileKeys keys = markerKeysWithBasicIdentity();

        ConversionOutput.publish(coverage, report, output -> output.write('x'), output -> {
            assertFalse(Files.exists(coverage));
            IOException busy = assertThrows(IOException.class, () -> publishMarkerOutput(report, keys));
            assertTrue(busy.getMessage().contains("Report creation is in progress"), busy.toString());
            output.write('j');
        }, null);
        publishMarkerOutput(unrelated, keys);

        assertEquals("x", Files.readString(coverage));
        assertEquals("j", Files.readString(report));
        assertEquals("x", Files.readString(unrelated));
        assertTrue(Files.isDirectory(existingNamespace));
        assertDirectorySize(4);
    }

    @Test
    void orphanedLegacyDirectoryMarkerDoesNotBlockOtherReports() throws IOException {
        ConversionOutput.FileKeys keys = markerKeysWithBasicIdentity();
        Path orphan = Files.createDirectory(directory.resolve(".coverage41c-creating.lock"));
        Path coverage = directory.resolve("coverage.xml");
        Path existing = Files.writeString(directory.resolve("existing.json"), "old");

        assertAll(
                () -> {
                    publishMarkerOutput(coverage, keys);
                    assertEquals("x", Files.readString(coverage));
                },
                () -> {
                    publishMarkerOutput(existing, keys);
                    assertEquals("x", Files.readString(existing));
                });
        assertTrue(Files.isDirectory(orphan));
        assertDirectorySize(3);
    }

    @Test
    void orphanedDestinationClaimProtectsOnlyThatReportAndItsAliases() throws IOException {
        ConversionOutput.FileKeys keys = markerKeysWithBasicIdentity();
        Path coverage = Files.writeString(directory.resolve("coverage.xml"), "old");
        Path aliasParent = Files.createDirectory(directory.resolve("links"));
        Path alias = Files.createSymbolicLink(aliasParent.resolve("alias.xml"), coverage);
        Path claim = Files.createDirectories(directory.resolve(".coverage41c-creating").resolve("coverage.xml"));
        Path alternateSpelling = directory.resolve("COVERAGE.XML");
        boolean caseAlias = Files.exists(alternateSpelling) && Files.isSameFile(coverage, alternateSpelling);

        assertAll(
                () -> assertThrows(IOException.class, () -> publishMarkerOutput(coverage, keys)),
                () -> assertThrows(IOException.class, () -> publishMarkerOutput(alias, keys)),
                () -> {
                    if (caseAlias) {
                        assertThrows(IOException.class, () -> publishMarkerOutput(alternateSpelling, keys));
                    } else {
                        publishMarkerOutput(alternateSpelling, keys);
                        assertEquals("x", Files.readString(alternateSpelling));
                    }
                });
        assertEquals("old", Files.readString(coverage));
        assertTrue(Files.isDirectory(claim));
        Files.delete(coverage);
        assertThrows(IOException.class, () -> publishMarkerOutput(coverage, keys));
        assertThrows(IOException.class, () -> ConversionOutput.writeFile(coverage, file -> Files.writeString(file, "x")));
        assertFalse(Files.exists(coverage));
        assertTrue(Files.isDirectory(claim));
    }

    @Test
    void fallbackCreationDoesNotFollowASymlinkAtTheCreationNamespace() throws IOException {
        ConversionOutput.FileKeys keys = markerKeysWithBasicIdentity();
        Path foreign = Files.createDirectory(directory.resolve("foreign"));
        Path namespace = Files.createSymbolicLink(directory.resolve(".coverage41c-creating"), foreign);
        Path coverage = directory.resolve("coverage.xml");

        IOException failure = assertThrows(IOException.class, () -> publishMarkerOutput(coverage, keys));
        assertTrue(failure.getMessage().contains("Report creation marker path is not a directory"));
        assertTrue(Files.isSymbolicLink(namespace));
        assertFalse(Files.exists(coverage));
        try (Stream<Path> contents = Files.list(foreign)) {
            assertEquals(0, contents.count());
        }
    }

    private ConversionOutput.FileKeys markerKeysWithBasicIdentity() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix")
                && Files.readAttributes(directory, BasicFileAttributes.class).fileKey() != null);
        return new ConversionOutput.FileKeys(false, BasicFileAttributes::fileKey, (path, options) -> null);
    }

    private static void publishMarkerOutput(Path path, ConversionOutput.FileKeys keys) throws IOException {
        ConversionOutput.publish(path, null, output -> output.write('x'), null, OutputStream.nullOutputStream(), keys);
    }

    private static void awaitMarkerSignal(Path signal) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(signal)) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("Timed out waiting for " + signal);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }
    }

    public static final class ExternalCreationHolder {
        public static void main(String[] args) throws IOException {
            Path output = Path.of(args[0]);
            Path ready = Path.of(args[1]);
            Path release = Path.of(args[2]);
            ConversionOutput.FileKeys keys = new ConversionOutput.FileKeys(false, attributes -> null, (file, options) -> {
                Object key = Files.readAttributes(file, BasicFileAttributes.class, options).fileKey();
                if (file.getFileName().toString().equals("output")) {
                    Files.createFile(ready);
                    awaitMarkerSignal(release);
                }
                return key;
            });
            publishMarkerOutput(output, keys);
        }
    }

    private ConversionOutput.FileKeys markerKeysWithoutBasicIdentity(ConversionOutput.NativeFileKey fallback) {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix"));
        return new ConversionOutput.FileKeys(false, attributes -> null, fallback);
    }

    private static Object readUnixIdentity(Path path, LinkOption... options) throws IOException {
        return Files.readAttributes(path, "unix:dev,ino", options);
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
    void createOnlyReportRejectsFileAppearingAfterStaging() throws Exception {
        Path report = directory.resolve("report.json");
        assertThrows(FileAlreadyExistsException.class, () ->
                ConversionOutput.writeNewFile(report, stage -> {
                    Files.writeString(stage, "replacement");
                    Files.writeString(report, "previous");
                }));
        assertEquals("previous", Files.readString(report));
        assertDirectorySize(1);
    }

    @Test
    void rollbackLeavesAFileCreatedByAnotherWriter() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path report = directory.resolve("report.json");
        IOException error = assertThrows(IOException.class, () -> ConversionOutput.publish(null, report,
                stream -> {
                    assertFalse(Files.exists(report));
                    Files.writeString(report, "replacement");
                    throw new IOException("coverage stream failed");
                }, stream -> stream.write('1'), OutputStream.nullOutputStream()));

        assertEquals("coverage stream failed", error.getMessage());
        assertEquals("replacement", Files.readString(report));
        assertDirectorySize(1);
    }

    @Test
    void createOnlyPairRollsBackWhenSecondDestinationAppearsAfterStaging() throws Exception {
        Path coverage = directory.resolve("coverage.xml");
        Path report = directory.resolve("report.json");

        assertThrows(FileAlreadyExistsException.class, () -> ConversionOutput.write(coverage, report,
                stage -> Files.writeString(stage, "<coverage/>"), stage -> {
                    Files.writeString(stage, "[]");
                    Files.writeString(report, "replacement");
                }, OutputStream.nullOutputStream(), true));

        assertFalse(Files.exists(coverage));
        assertEquals("replacement", Files.readString(report));
        assertDirectorySize(1);
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
    void temporaryNamesArePrivateDuringPublication() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path xml = directory.resolve("coverage.xml");
        ConversionOutput.write(xml, null, stage -> {
            assertEquals(PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(stage));
            Files.writeString(stage, "<new/>");
        }, null, OutputStream.nullOutputStream());
        Path json = directory.resolve("report.json");
        ConversionOutput.publish(json, null, out -> {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path entry : (Iterable<Path>) files::iterator) {
                    if (entry.getFileName().toString().startsWith(".coverage41c-")) {
                        assertTrue(Files.isDirectory(entry));
                        assertEquals(PosixFilePermissions.fromString("rwx------"),
                                Files.getPosixFilePermissions(entry));
                    }
                }
            }
            out.write(1);
        }, null, OutputStream.nullOutputStream());
        assertDirectorySize(2);
    }

    @Test
    void localContentionDoesNotReleaseTheLockSeenByAnotherJvm() throws Exception {
        Path xml = Files.writeString(directory.resolve("coverage.xml"), "old");
        Path json = Files.writeString(directory.resolve("report.json"), "{}");
        ProcessBuilder checker = lockChecker(xml);
        ConversionOutput.publish(xml, json, out -> out.write(1), out -> {
            assertThrows(IOException.class, () -> ConversionOutput.publish(xml, json,
                    bytes -> bytes.write(2), bytes -> bytes.write(3), OutputStream.nullOutputStream()));
            assertLocked(checker);
        }, OutputStream.nullOutputStream());
    }

    @Test
    void rejectedPosixChannelDoesNotBlockIndependentPublications() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path active = Files.writeString(directory.resolve("active.xml"), "old");
        Path busy = Files.writeString(directory.resolve("busy.xml"), "unchanged");
        Path existing = Files.writeString(directory.resolve("independent.xml"), "old");
        Path fresh = directory.resolve("fresh.xml");

        ConversionOutput.publish(active, null, stream -> {
            try (FileChannel channel = FileChannel.open(busy, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                IOException conflict = assertThrows(IOException.class,
                        () -> ConversionOutput.writeFile(busy, file -> Files.writeString(file, "unexpected")));
                assertInstanceOf(java.nio.channels.OverlappingFileLockException.class, conflict.getCause());
            }
            IOException pending = assertThrows(IOException.class,
                    () -> ConversionOutput.writeFile(busy, file -> Files.writeString(file, "unexpected")));
            assertTrue(pending.getMessage().contains("releasing a report lock"), pending.toString());
            for (Path unrelated : List.of(existing, fresh)) {
                ConversionOutput.writeFile(unrelated, file -> Files.writeString(file, "published"));
                assertEquals("published", Files.readString(unrelated));
            }
            stream.write('x');
        }, null, OutputStream.nullOutputStream());

        assertEquals("unchanged", Files.readString(busy));
        assertEquals("x", Files.readString(active));
        ConversionOutput.writeFile(busy, file -> Files.writeString(file, "retry"));
        assertEquals("retry", Files.readString(busy));
    }

    @Test
    void closingCollectorDuringAnotherPublicationKeepsThatPublishersLock() throws Exception {
        Path json = directory.resolve("report.json");
        ProcessBuilder checker = lockChecker(json);
        try (ConversionOutput.PublishedReport published = new ConversionOutput.PublishedReport()) {
            published.write(json, file -> Files.writeString(file, "original"));
            ConversionOutput.publish(json, null, stream -> {
                published.close();
                assertLocked(checker);
                stream.write(42);
            }, null, OutputStream.nullOutputStream());
        }
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(json));
    }

    private static ProcessBuilder lockChecker(Path path) throws Exception {
        String classes = Path.of(ExternalLockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classes,
                ExternalLockProbe.class.getName(), path.toString()).inheritIO();
    }

    private static void assertLocked(ProcessBuilder checker) throws IOException {
        Process probe = checker.start();
        try {
            assertTrue(probe.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, probe.exitValue(), "An independent JVM acquired the active output");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        } finally {
            probe.destroyForcibly();
        }
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
