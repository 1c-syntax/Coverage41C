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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConversionOutputTest {
    @TempDir Path directory;

    @Test
    void measurementOutputKeepsOriginalInputsWhenItsNameBecomesAHardLink() throws IOException {
        Path report = Files.writeString(directory.resolve("coverage.xml"), "existing report");
        Path alternate = directory.resolve("old-coverage.xml");
        Path input = Files.writeString(directory.resolve("rules.json"), "rules");
        Object checkedIdentity = StandardOutputFile.regularFileKey(report);
        Files.move(report, alternate);
        Files.createLink(report, input);

        assertThrows(IOException.class, () -> ConversionOutput.writeMeasurementOutput(report, checkedIdentity, false,
                output -> output.write('x')));

        assertEquals("rules", Files.readString(input));
        assertEquals("existing report", Files.readString(alternate));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void measurementOutputRejectsASymlinkInsertedAfterValidation() throws IOException {
        Path report = directory.resolve("coverage.xml");
        Path input = Files.writeString(directory.resolve("rules.json"), "rules");
        Object checkedIdentity = StandardOutputFile.regularFileKey(report);
        Files.createSymbolicLink(report, input);

        assertThrows(IOException.class, () -> ConversionOutput.writeMeasurementOutput(report, checkedIdentity, true,
                output -> output.write('x')));

        assertTrue(Files.isSymbolicLink(report));
        assertEquals("rules", Files.readString(input));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void measurementOutputRejectsLateSymlinkEvenIfItPointsToTheExpectedFile() throws IOException {
        Path report = Files.writeString(directory.resolve("coverage.xml"), "existing report");
        Object checkedIdentity = StandardOutputFile.regularFileKey(report);
        Path original = Files.move(report, directory.resolve("original.xml"));
        Files.createSymbolicLink(report, original);

        assertThrows(IOException.class, () -> ConversionOutput.writeMeasurementOutput(report, checkedIdentity, false,
                output -> output.write('x')));

        assertTrue(Files.isSymbolicLink(report));
        assertEquals("existing report", Files.readString(original));
    }

    @Test
    void measurementOutputPreservesAnExistingHardLinkAndTruncatesTheOldBytes() throws IOException {
        Path report = Files.writeString(directory.resolve("coverage.xml"), "existing report");
        Path peer = Files.createLink(directory.resolve("linked-report.xml"), report);
        Object identity = StandardOutputFile.regularFileKey(report);

        ConversionOutput.writeMeasurementOutput(report, identity, false, output -> output.write('x'));

        assertTrue(Files.isSameFile(report, peer));
        assertEquals("x", Files.readString(peer));
    }

    @Test
    void measurementOutputCanUpdateAnExistingWriteOnlyReport() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path report = Files.writeString(directory.resolve("coverage.xml"), "existing report");
        var permissions = Files.getPosixFilePermissions(report);
        var directoryPermissions = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(report, PosixFilePermissions.fromString("-w-------"));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(report) || Files.isWritable(directory));
            ConversionOutput.writeMeasurementOutput(report, StandardOutputFile.regularFileKey(report), false,
                    output -> output.write('x'));
        } finally {
            Files.setPosixFilePermissions(directory, directoryPermissions);
            Files.setPosixFilePermissions(report, permissions);
        }
        assertEquals("x", Files.readString(report));
    }

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
    void publicationCanUseAnotherNamespaceWhenTheFirstIsSearchableButNotWritable() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path namespace = Files.createDirectory(directory.resolve(".coverage41c-creating"));
        Path reserved = Files.createDirectory(namespace.resolve("reserved.xml"));
        Path coverage = directory.resolve("coverage.xml");
        Path report = directory.resolve("exclusions.json");
        var original = Files.getPosixFilePermissions(namespace);
        try {
            Files.setPosixFilePermissions(namespace, PosixFilePermissions.fromString("r-xr-xr-x"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(namespace));
            IOException busy = assertThrows(IOException.class, () -> ConversionOutput.publish(
                    directory.resolve("reserved.xml"), report, output -> output.write('x'), output -> output.write('j'), null));
            assertTrue(busy.getMessage().contains("Report creation is in progress"), busy.toString());

            ConversionOutput.publish(coverage, report, output -> output.write('c'), output -> output.write('j'), null);

            assertEquals("c", Files.readString(coverage));
            assertEquals("j", Files.readString(report));
            assertTrue(Files.isDirectory(reserved));
            assertFalse(Files.exists(directory.resolve(".coverage41c-creating-1")));
        } finally {
            Files.setPosixFilePermissions(namespace, original);
        }
    }

    @Test
    void anotherPublicationDoesNotUseAReservedReportNameAsItsMarkerDirectory() throws IOException {
        Path first = directory.resolve(".coverage41c-creating");
        Path firstJson = directory.resolve("first.json");
        Path second = directory.resolve("second.xml");
        Path secondJson = directory.resolve("second.json");

        ConversionOutput.publish(first, firstJson, output -> {
            ConversionOutput.publish(second, secondJson, other -> {
                assertFalse(Files.isDirectory(first), "The first publication already reserved this report name");
                other.write('b');
            }, json -> json.write('j'), null);
            output.write('a');
        }, json -> json.write('j'), null);

        assertEquals("a", Files.readString(first));
        assertEquals("b", Files.readString(second));
        assertDirectorySize(4);
    }

    @Test
    void creationStampAllowsPeerSearchAndReadWithoutWorldWrite() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(PosixFiles.supportsNamespaceLeases(directory));
        String namespaceName = ".coverage41c-creating";
        Path namespace = directory.resolve(namespaceName);
        Path stamp = namespace.resolve(namespaceName);
        ConversionOutput.publish(directory.resolve("coverage.xml"), directory.resolve("exclusions.json"), xml -> {
            assertTrue(Files.getPosixFilePermissions(namespace).contains(PosixFilePermission.OTHERS_EXECUTE));
            var stampAccess = Files.getPosixFilePermissions(stamp);
            assertTrue(stampAccess.contains(PosixFilePermission.OTHERS_READ));
            assertFalse(stampAccess.contains(PosixFilePermission.OTHERS_WRITE));
            xml.write('x');
        }, json -> json.write('j'), null);
        assertFalse(Files.exists(namespace));
    }

    @Test
    void publicationRemovesNamespaceWithAReadOnlyStamp() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(PosixFiles.supportsNamespaceLeases(directory));
        String namespaceName = ".coverage41c-creating";
        Path namespace = directory.resolve(namespaceName);
        Path stamp = namespace.resolve(namespaceName);
        ConversionOutput.publish(directory.resolve("coverage.xml"), directory.resolve("exclusions.json"), xml -> {
            Files.setPosixFilePermissions(stamp, PosixFilePermissions.fromString("r--r--r--"));
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(stamp));
            xml.write('x');
        }, json -> json.write('j'), null);
        assertFalse(Files.exists(namespace));
    }

    @Test
    void suspendedNamespaceCreatorCannotSplitAReservedPair() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PosixFiles.supportsNamespaceLeases(directory));
        Path coordination = Files.createDirectory(directory.resolve("coordination"));
        Path outputs = Files.createDirectory(directory.resolve("outputs"));
        var original = Files.getPosixFilePermissions(outputs);
        Files.setPosixFilePermissions(outputs, PosixFilePermissions.fromString("-wx------"));
        Path namespace = outputs.resolve(".coverage41c-creating");
        Path report = outputs.resolve(".coverage41c-creating-1");
        Path expose = coordination.resolve("expose");
        Path ready = coordination.resolve("ready");
        Path release = coordination.resolve("release");
        String classpath = System.getProperty("coverage41c.testClasspath", System.getProperty("java.class.path"));
        Path executable = Path.of(System.getProperty("java.home"), "bin", "java");
        Process creator = new ProcessBuilder(executable.toString(), "-cp", classpath, SuspendedNamespaceCreator.class.getName(),
                namespace.toString(), coordination.toString()).redirectErrorStream(true).start();
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var publication = workers.submit(() -> {
                ConversionOutput.publish(namespace, report, xml -> {
                    Files.createFile(expose);
                    awaitMarkerSignal(ready);
                    xml.write('x');
                }, json -> json.write('j'), null);
                return null;
            });
            awaitMarkerSignal(ready);
            assertThrows(java.util.concurrent.TimeoutException.class, () -> publication.get(2500, TimeUnit.MILLISECONDS));
            Files.createFile(release);
            publication.get(15, TimeUnit.SECONDS);
            assertEquals(0, creator.onExit().orTimeout(15, TimeUnit.SECONDS).join().exitValue(),
                    new String(creator.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("x", Files.readString(namespace));
            assertEquals("j", Files.readString(report));
        } finally {
            creator.destroyForcibly();
            assertTrue(creator.waitFor(15, TimeUnit.SECONDS));
            workers.shutdownNow();
            Files.setPosixFilePermissions(outputs, original);
        }
    }

    @Test
    void waitingPairDoesNotDelayAnIndependentPublication() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PosixFiles.supportsNamespaceLeases(directory));
        var leases = new java.util.ArrayList<PosixFiles.Pin>();
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (String name : List.of(".coverage41c-creating", ".coverage41c-creating-1", ".coverage41c-creating-2")) {
                Path namespace = Files.createDirectory(directory.resolve(name));
                Path stamp = Files.writeString(namespace.resolve(name), "Coverage41C creation claims\n");
                var lease = PosixFiles.tryNamespaceStampLock(stamp, true);
                assertNotNull(lease);
                leases.add(lease);
            }
            var started = new java.util.concurrent.CountDownLatch(1);
            Path coverage = directory.resolve("coverage.xml");
            Path report = directory.resolve("exclusions.json");
            var publication = workers.submit(() -> {
                started.countDown();
                ConversionOutput.publish(coverage, report, xml -> xml.write('x'), json -> json.write('j'), null);
                return null;
            });
            assertTrue(started.await(15, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> publication.get(200, TimeUnit.MILLISECONDS));
            Path unrelated = Files.writeString(Files.createDirectory(directory.resolve("unrelated"))
                    .resolve("coverage.xml"), "old report");
            var independent = workers.submit(() -> {
                ConversionOutput.publish(unrelated, null, xml -> xml.write('y'), null, null);
                return null;
            });
            independent.get(5, TimeUnit.SECONDS);
            assertEquals("y", Files.readString(unrelated));

            leases.remove(0).close();
            publication.get(15, TimeUnit.SECONDS);
            assertEquals("x", Files.readString(coverage));
            assertEquals("j", Files.readString(report));
        } finally {
            for (var lease : leases) {
                lease.close();
            }
            workers.shutdownNow();
        }
    }

    public static final class SuspendedNamespaceCreator {
        public static void main(String[] args) throws IOException {
            Path namespace = Path.of(args[0]);
            Path signals = Path.of(args[1]);
            Path staged = Files.createDirectory(signals.resolve("staged"));
            Path stamp = Files.writeString(staged.resolve(namespace.getFileName()), "Coverage41C creation claims\n");
            try (var lease = PosixFiles.tryNamespaceStampLock(stamp, true)) {
                if (lease == null) {
                    throw new IOException("Could not reserve private namespace");
                }
                awaitMarkerSignal(signals.resolve("expose"));
                PosixFiles.moveNewFile(staged, namespace);
                Files.createFile(signals.resolve("ready"));
                awaitMarkerSignal(signals.resolve("release"));
                Files.createDirectory(staged);
                Files.move(namespace, staged, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.delete(stamp);
                Files.delete(staged);
            }
        }
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
    void newReportSkipsSymlinksAtCreationNamespaces() throws IOException {
        ConversionOutput.FileKeys keys = markerKeysWithBasicIdentity();
        Path foreign = Files.createDirectory(directory.resolve("foreign"));
        Path namespace = Files.createSymbolicLink(directory.resolve(".coverage41c-creating"), foreign);
        Path alternate = Files.createSymbolicLink(directory.resolve(".coverage41c-creating-1"), foreign);
        Path coverage = directory.resolve("coverage.xml");
        Path fallback = directory.resolve("fallback.xml");

        ConversionOutput.writeNewFile(coverage, staged -> Files.writeString(staged, "coverage"));
        publishMarkerOutput(fallback, keys);

        assertEquals("coverage", Files.readString(coverage));
        assertEquals("x", Files.readString(fallback));
        assertTrue(Files.isSymbolicLink(namespace));
        assertTrue(Files.isSymbolicLink(alternate));
        assertFalse(Files.exists(directory.resolve(".coverage41c-creating-2")));
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

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void conversionProtectsRawInputWhenCoverageLinkChangesAfterPreparation() throws Exception {
        Path raw = Files.copy(Path.of("src/test/resources/coverage/start-raw.xml"), directory.resolve("raw.xml"));
        Path coveragePath = Files.createSymbolicLink(directory.resolve("coverage.xml"), directory.resolve("safe.xml"));
        Path exclusions = directory.resolve("exclusions.json");
        byte[] original = Files.readAllBytes(raw);
        var source = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.MetadataOptions(),
                "-s", "src/test/resources/configuration", "--exclusion-report", exclusions.toString());
        var options = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.OutputOptions(),
                "-o", coveragePath.toString());
        var coverage = new java.util.HashMap<java.net.URI, java.util.Map<java.math.BigDecimal, Integer>>();
        var conversion = Utils.prepareConversion(source, "", "", coverage, raw.toFile(), coveragePath.toFile());
        Files.delete(coveragePath);
        Files.createSymbolicLink(coveragePath, raw);

        assertThrows(IllegalArgumentException.class, () -> conversion.writeConversion(coverage, source, options));
        assertArrayEquals(original, Files.readAllBytes(raw));
        assertFalse(Files.exists(exclusions));
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void conversionRejectsJsonLinkRetargetedIntoTheConfigDirectory() throws Exception {
        Path config = Files.createDirectory(directory.resolve("rules"));
        Files.writeString(config.resolve("rules.json"),
                "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}");
        Path reportLink = Files.createSymbolicLink(directory.resolve("exclusions.json"), directory.resolve("safe.json"));
        Path coveragePath = directory.resolve("coverage.xml");
        var source = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.MetadataOptions(),
                "-s", "src/test/resources/configuration", "--config", config.toString(), "--exclusion-report", reportLink.toString());
        var output = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.OutputOptions(),
                "-o", coveragePath.toString());
        var coverage = new java.util.HashMap<java.net.URI, java.util.Map<java.math.BigDecimal, Integer>>();
        var conversion = Utils.prepareConversion(source, "", "", coverage, null, coveragePath.toFile());
        Path generated = config.resolve("generated.json");
        Files.delete(reportLink);
        Files.createSymbolicLink(reportLink, generated);

        assertThrows(IllegalArgumentException.class, () -> conversion.writeConversion(coverage, source, output));
        assertFalse(Files.exists(generated));
        assertFalse(Files.exists(coveragePath));
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void conversionRejectsTwoStdoutOutputsAfterReportLinkChanges() throws Exception {
        Path report = directory.resolve("exclusions.json");
        var source = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.MetadataOptions(),
                "-s", "src/test/resources/configuration", "--exclusion-report", report.toString());
        var output = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.OutputOptions(), "-f", "LCOV");
        var coverage = new java.util.HashMap<java.net.URI, java.util.Map<java.math.BigDecimal, Integer>>();
        var stdout = new ByteArrayOutputStream();
        var conversion = Utils.prepareConversion(source, "", "", coverage, null, null, stdout);
        Files.createSymbolicLink(report, Path.of("/dev/stdout"));

        assertThrows(IllegalArgumentException.class, () -> conversion.writeConversion(coverage, source, output));
        assertEquals(0, stdout.size());
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void collectorRejectsOpaqueStdoutAfterPublishedJsonIsMoved() throws Exception {
        Path coverage = directory.resolve("coverage.xml");
        Path report = directory.resolve("exclusions.json");
        Path archive = directory.resolve("archive.json");
        var metadata = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.MetadataOptions(),
                "-s", "src/test/resources/configuration", "--exclusion-report", report.toString());
        var output = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.OutputOptions(),
                "-o", coverage.toString());
        var paths = new java.util.HashMap<java.net.URI, java.util.Map<java.math.BigDecimal, Integer>>();
        try (var collector = new Utils.MeasurementReports()) {
            collector.readMetadata(metadata, "", "", paths, false, coverage.toFile(), () -> null);
            Files.move(report, archive);
            byte[] original = Files.readAllBytes(archive);
            Files.writeString(report, "{}");
            Files.createSymbolicLink(coverage, Path.of("/dev/stdout"));
            try (var sink = Files.newOutputStream(archive, StandardOpenOption.APPEND)) {
                assertThrows(IOException.class, () -> collector.dump(paths, metadata, output, () -> sink));
            }
            assertArrayEquals(original, Files.readAllBytes(archive));
            assertEquals("{}", Files.readString(report));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void collectorDoesNotPublishJsonOverTheRulesItLoaded() throws Exception {
        assertInitialJsonProtectsLoadedRules(false);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void directMeasurementMetadataCallProtectsLoadedRules() throws Exception {
        assertInitialJsonProtectsLoadedRules(true);
    }

    private void assertInitialJsonProtectsLoadedRules(boolean usePublicMetadataMethod) throws Exception {
        String config = "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}";
        Path rules = Files.writeString(directory.resolve("rules.json"), config);
        Path archive = directory.resolve("archive.json");
        Path report = Files.createSymbolicLink(directory.resolve("exclusions.json"), directory.resolve("safe.json"));
        var options = CommandLine.populateCommand(new MetadataOptions(), "-s", "src/test/resources/configuration",
                "--config", rules.toString(), "--exclusion-report", report.toString());
        Supplier<OutputStream> stdout = () -> {
            try {
                Files.move(rules, archive);
                Files.writeString(rules, config);
                Files.delete(report);
                Files.createSymbolicLink(report, archive);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            return new ByteArrayOutputStream();
        };
        var coverage = new HashMap<URI, Map<BigDecimal, Integer>>();
        if (usePublicMetadataMethod) {
            assertThrows(IOException.class, () -> Utils.readMeasurementMetadata(options, coverage, false, null, stdout));
        } else {
            try (var collector = new Utils.MeasurementReports()) {
                assertThrows(IOException.class, () -> collector.readMetadata(options, "", "", coverage, false, null, stdout));
            }
        }
        assertEquals(config, Files.readString(archive));
        assertEquals(config, Files.readString(rules));
    }

    @Test
    void collectorProtectsRulesReadForTheUserFilter() throws Exception {
        String config = "{\"includeUsers\":[\"test-user\"]}";
        Path rules = Files.writeString(directory.resolve("rules.json"), config);
        Path archive = directory.resolve("archive.json");
        Path report = directory.resolve("exclusions.json");
        var options = CommandLine.populateCommand(new MetadataOptions(), "-s", "src/test/resources/configuration",
                "--config", rules.toString(), "--exclusion-report", report.toString());
        try (var collector = new Utils.MeasurementReports()) {
            assertTrue(collector.loadIncludeUsers(rules.toFile(), List.of()).isActive());
            Files.move(rules, archive);
            Files.writeString(rules, config);
            Files.createLink(report, archive);

            var coverage = new HashMap<URI, Map<BigDecimal, Integer>>();
            assertThrows(IOException.class, () -> collector.readMetadata(options, "", "", coverage, true,
                    directory.resolve("coverage.xml").toFile(), () -> null));
        }
        assertEquals(config, Files.readString(archive));
        assertEquals(config, Files.readString(rules));
    }

    @Test
    void conversionDoesNotPublishCoverageOverTheRulesItLoaded() throws Exception {
        assertConversionProtectsLoadedRules(false);
    }

    @Test
    void conversionProtectsLoadedRulesWhenOutputChangesDuringStaging() throws Exception {
        assertConversionProtectsLoadedRules(true);
    }

    private void assertConversionProtectsLoadedRules(boolean retargetWhilePreparingXml) throws Exception {
        String config = "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}";
        Path rules = Files.writeString(directory.resolve("rules.json"), config);
        Path archive = directory.resolve("archive.json");
        Path report = directory.resolve("exclusions.json");
        Path coveragePath = directory.resolve("coverage.xml");
        var metadata = CommandLine.populateCommand(new MetadataOptions(), "-s", "src/test/resources/configuration",
                "--config", rules.toString(), "--exclusion-report", report.toString());
        var output = CommandLine.populateCommand(new OutputOptions(), "-o", coveragePath.toString());
        var coverage = new HashMap<URI, Map<BigDecimal, Integer>>();
        try (var conversion = Utils.prepareConversion(metadata, "", "", coverage, null, coveragePath.toFile())) {
            Files.move(rules, archive);
            Files.writeString(rules, config);
            if (retargetWhilePreparingXml) {
                var stagingCoverage = new HashMap<URI, Map<BigDecimal, Integer>>(coverage) {
                    @Override
                    public void forEach(BiConsumer<? super URI, ? super Map<BigDecimal, Integer>> action) {
                        try {
                            Files.createLink(coveragePath, archive);
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                        super.forEach(action);
                    }
                };
                assertThrows(IOException.class, () -> conversion.writeConversion(stagingCoverage, metadata, output));
            } else {
                Files.createLink(coveragePath, archive);
                assertThrows(IOException.class, () -> conversion.writeConversion(coverage, metadata, output));
            }
        }

        assertEquals(config, Files.readString(archive));
        assertEquals(config, Files.readString(rules));
        assertFalse(Files.exists(report));
    }

    @Test
    void conversionProtectsRawAndExportFilesWhenAnOutputAppearsDuringSerialization() throws Exception {
        Path project = TestUtils.copyConfiguration(directory).resolve("configuration");
        Path sourceModule = project.resolve("CommonModules/ПростойОбщийМодуль/Ext/Module.bsl");
        Path raw = Files.writeString(directory.resolve("input.xml"), "<coverage version=\"1\"/>");
        for (Path input : List.of(raw, sourceModule)) {
            byte[] original = Files.readAllBytes(input);
            Path xml = directory.resolve("coverage.xml");
            Path json = directory.resolve("exclusions.json");
            Path changed = input.equals(raw) ? xml : json;
            var metadata = CommandLine.populateCommand(new MetadataOptions(), "-s", project.toString(),
                    "--exclusion-report", json.toString());
            var output = CommandLine.populateCommand(new OutputOptions(), "-o", xml.toString());
            var coverage = new HashMap<URI, Map<BigDecimal, Integer>>();
            try (var conversion = Utils.prepareConversion(metadata, "", "", coverage, raw.toFile(), xml.toFile())) {
                var stagingCoverage = new HashMap<URI, Map<BigDecimal, Integer>>(coverage) {
                    @Override
                    public void forEach(BiConsumer<? super URI, ? super Map<BigDecimal, Integer>> action) {
                        try {
                            Files.createLink(changed, input);
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                        super.forEach(action);
                    }
                };
                IOException failure = assertThrows(IOException.class,
                        () -> conversion.writeConversion(stagingCoverage, metadata, output));
                assertTrue(failure.getMessage().contains("would overwrite input file"), failure.toString());
            }
            assertArrayEquals(original, Files.readAllBytes(input));
            assertTrue(Files.isSameFile(changed, input));
            assertFalse(Files.exists(input.equals(raw) ? json : xml));
            Files.delete(changed);
        }
    }

    @Test
    void collectorProtectsRulesLoadedBeforeTheirPathWasReplaced() throws Exception {
        Path rules = directory.resolve("rules.json");
        Path archive = directory.resolve("archive.json");
        Path output = directory.resolve("coverage.xml");
        String config = "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}";
        Files.writeString(rules, config);
        var metadata = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.MetadataOptions(),
                "-s", "src/test/resources/configuration", "--config", rules.toString());
        var outputOptions = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.OutputOptions(),
                "-o", output.toString());
        var coverage = new java.util.HashMap<java.net.URI, java.util.Map<java.math.BigDecimal, Integer>>();
        try (var collector = new Utils.MeasurementReports()) {
            collector.readMetadata(metadata, "", "", coverage, false, null, () -> {
                // The rules have already been parsed when the startup output is selected.
                try {
                    Files.move(rules, archive);
                    Files.writeString(rules, config);
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
                return new ByteArrayOutputStream();
            });
            Files.createLink(output, archive);
            assertThrows(IOException.class, () -> collector.dump(coverage, metadata, outputOptions, () -> null));
            assertEquals(config, Files.readString(archive));
        }
    }

    @Test
    void namedCollectorClosePreservesHostLocksOnJsonAndRules() throws Exception {
        Path coverage = directory.resolve("coverage.xml");
        Path report = directory.resolve("exclusions.json");
        Path rules = Files.writeString(directory.resolve("rules.json"),
                "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}");
        var options = picocli.CommandLine.populateCommand(new com.clouds42.CommandLineOptions.MetadataOptions(),
                "-s", "src/test/resources/configuration", "--config", rules.toString(),
                "--exclusion-report", report.toString());
        try (var collector = new Utils.MeasurementReports()) {
            collector.readMetadata(options, "", "", new java.util.HashMap<>(), false, coverage.toFile(), () -> null);
            try (FileChannel reportWriter = FileChannel.open(report, StandardOpenOption.WRITE);
                 FileChannel rulesWriter = FileChannel.open(rules, StandardOpenOption.WRITE);
                 FileLock reportLock = reportWriter.lock();
                 FileLock rulesLock = rulesWriter.lock()) {
                ProcessBuilder reportCheck = lockChecker(report);
                ProcessBuilder rulesCheck = lockChecker(rules);
                assertLocked(reportCheck);
                assertLocked(rulesCheck);
                collector.close();
                assertLocked(reportCheck);
                assertLocked(rulesCheck);
            }
        }
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
