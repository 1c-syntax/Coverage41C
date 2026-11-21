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

import com.sun.nio.file.ExtendedOpenOption;


import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Serialize both reports before publishing either. Keep the original inodes, links and access rights. */
final class ConversionOutput {
    private ConversionOutput() { }

    private static final FileKeys SYSTEM_FILE_KEYS =
            new FileKeys(true, BasicFileAttributes::fileKey, ConversionOutput::windowsFileKey);

    @FunctionalInterface
    interface NativeFileKey {
        Object read(Path path, LinkOption... options) throws IOException;
    }

    static final class FileKeys {
        private final boolean usePrivateLinks;
        private final Function<BasicFileAttributes, Object> basic;
        private final NativeFileKey fallback;

        FileKeys(boolean usePrivateLinks, Function<BasicFileAttributes, Object> basic, NativeFileKey fallback) {
            this.usePrivateLinks = usePrivateLinks;
            this.basic = basic;
            this.fallback = fallback;
        }

        private Object read(Path path, BasicFileAttributes attributes, LinkOption... options) throws IOException {
            Object key = basic.apply(attributes);
            return key == null ? fallback.read(path, options) : key;
        }
    }

    @FunctionalInterface
    interface Writer {
        void write(Path file) throws IOException;
    }

    @FunctionalInterface
    interface DestinationCheck {
        void verify(Path path, Object fileIdentity) throws IOException;
    }

    @FunctionalInterface
    interface PublicationCheck {
        void verify() throws IOException;
    }

    static void writeFile(Path destination, Writer writer) throws IOException {
        writeFile(destination, writer, null);
    }

    static void writeFile(Path destination, Writer writer, DestinationCheck check) throws IOException {
        write(destination, null, writer, null, null, false, check);
    }

    static void writeMeasurementOutput(Path path, Object identity, boolean createOnly, Content writer) throws IOException {
        if (createOnly) {
            try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.copyTo(output);
            }
        } else if (File.separatorChar == '\\') {
            WindowsFiles.writeCheckedReport(path, identity, writer);
        } else {
            PosixFiles.writeCheckedReport(path, identity, writer);
        }
    }

    static void writeNewFile(Path file, Writer writer) throws IOException {
        write(file, null, writer, null, null, true);
    }

    static void write(Path coverage, Path report, Writer writeCoverage, Writer writeReport, OutputStream stdout)
            throws IOException {
        write(coverage, report, writeCoverage, writeReport, stdout, false);
    }

    static void write(Path coverage, Path report, Writer writeCoverage, Writer writeReport, OutputStream stdout,
                      boolean createOnly) throws IOException {
        write(coverage, report, writeCoverage, writeReport, stdout, createOnly, null);
    }

    static void write(Path coverage, Path report, Writer writeCoverage, Writer writeReport, OutputStream stdout,
                      boolean createOnly, DestinationCheck check) throws IOException {
        write(coverage, report, writeCoverage, writeReport, stdout, createOnly, check, null);
    }

    static void write(Path coverage, Path report, Writer writeCoverage, Writer writeReport, OutputStream stdout,
                      boolean createOnly, DestinationCheck check, PublicationCheck prepared) throws IOException {
        OutputStream stream = coverage == null ? Utils.reportStdout(stdout) : null;
        // A prepared output directory can move during serialization. Keep the private staged
        // files independent of its name so failure leaves nothing behind in the moved directory.
        try (Temporary coverageStage = Temporary.create(prepared == null ? coverage : null);
             Temporary reportStage = report == null ? null : Temporary.create(prepared == null ? report : null)) {
            writeCoverage.write(coverageStage.path);
            if (reportStage != null) {
                writeReport.write(reportStage.path);
            }

            if (coverage == null && check != null && (stdout == null || StandardOutputFile.isProcessOutput(stdout))) {
                check.verify(null, StandardOutputFile.regularFileKey());
            }
            // Java cannot identify the destination of a caller's PrintStream or arbitrary OutputStream.
            Path verifyReport = stream != null && reportStage != null && needsFileVerification(stdout)
                    ? reportStage.path : null;
            publish(coverage, report, output -> Files.copy(coverageStage.path, output),
                    reportStage == null ? null : output -> Files.copy(reportStage.path, output),
                    stream, createOnly, SYSTEM_FILE_KEYS, verifyReport, check, prepared);
        }
    }

    @FunctionalInterface
    interface Content {
        void copyTo(OutputStream output) throws IOException;
    }

    static boolean needsFileVerification(OutputStream stream) throws IOException {
        return (stream == null || stream.getClass() != ByteArrayOutputStream.class)
                && !StandardOutputFile.isProcessOutput(stream);
    }

    static void checkMeasurementReport(OutputStream stream, File report) throws IOException {
        if (report != null && needsFileVerification(stream)) {
            Path path = report.toPath();
            if (Files.exists(path) && (!isRegularReport(path, Files.readAttributes(path, BasicFileAttributes.class))
                    || !Files.isReadable(path))) {
                throw unverifiableReport(path);
            }
        }
    }

    // A collector publishes JSON before measuring. Verify it after a dump without publishing it again.
    static void writeSeparate(OutputStream stream, File peer, PublishedReport published, Content writer) throws IOException {
        if (peer == null) {
            writer.copyTo(stream);
            return;
        }
        Path path = peer.toPath();
        if (!needsFileVerification(stream)) {
            if (StandardOutputFile.isProcessOutput(stream) && StandardOutputFile.matches(path)) {
                throw conflictingReports(path);
            }
            writer.copyTo(stream);
            return;
        }
        if (published != null && published.channel != null) {
            if (!published.readable) {
                throw unverifiableReport(path);
            }
            try (Destination original = Destination.reservePublished(published, path).withBackup();
                 Closeable verify = original::verifyUnchanged) {
                if (published.hasReplacement(path)) {
                    writeWithNamedPeer(stream, path, writer);
                } else {
                    writer.copyTo(stream);
                }
            }
        } else {
            if (published != null && published.outputIdentity != null) {
                Object current = StandardOutputFile.isAlias(peer) ? StandardOutputFile.regularFileKey()
                        : StandardOutputFile.regularFileKey(path);
                if (!published.matches(current)) {
                    throw unverifiableReport(path);
                }
            }
            writeWithNamedPeer(stream, path, writer);
        }
    }

    private static void writeWithNamedPeer(OutputStream stream, Path path, Content writer) throws IOException {
        if (!Files.exists(path)) {
            throw unverifiableReport(path);
        }
        try (Destination other = Destination.open(path, false, SYSTEM_FILE_KEYS)) {
            if (other == null || other.access != Access.READ_WRITE) {
                throw unverifiableReport(path);
            }
            try (Closeable verify = other::verifyUnchanged) {
                writer.copyTo(stream);
            }
        }
    }

    // Keep the original descriptor for rollback if the owner makes the JSON read-only after start.
    // The publication lock is released before collection begins. Each dump takes its own lock.
    static final class PublishedReport implements Closeable {
        private Object fileKey;
        private Object outputIdentity;
        private Closeable identityPin;
        private FileChannel channel;
        private boolean readable;

        boolean matches(Object identity) {
            return identity != null && identity.equals(outputIdentity);
        }

        void rememberProcessOutput() throws IOException {
            Object identity = StandardOutputFile.regularFileKey();
            if (identity != null) {
                identityPin = PosixFiles.pinOnLinux(StandardOutputFile.unixDescriptor());
                outputIdentity = identity;
            }
        }

        private boolean hasReplacement(Path path) throws IOException {
            try {
                BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class);
                return !fileKey.equals(SYSTEM_FILE_KEYS.read(path, current));
            } catch (NoSuchFileException movedOrRemoved) {
                return false;
            }
        }

        void write(Path path, Writer writer) throws IOException {
            write(path, writer, true, null);
        }

        void write(Path path, Writer writer, boolean guardChanges, DestinationCheck check) throws IOException {
            try (Temporary staged = Temporary.create(path)) {
                writer.write(staged.path);
                try (Destination output = Destination.open(path, false, SYSTEM_FILE_KEYS);
                     ReportIdentity identity = ReportIdentity.retain(output)) {
                    if (check != null && identity != null && output.access != Access.CREATED) {
                        check.verify(path, identity.key);
                    }
                    writeResult(path, output, stream -> Files.copy(staged.path, stream), null);
                    if (output != null) {
                        output.installNewOutput(null);
                        output.committed = true;
                        if (identity != null) {
                            outputIdentity = identity.key;
                            if (guardChanges || File.separatorChar == '\\') {
                                fileKey = output.fileKey;
                                channel = output.channel;
                                readable = output.access != Access.WRITE_ONLY;
                                output.keepChannel = true;
                            } else {
                                identityPin = identity.takePin();
                            }
                        }
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            outputIdentity = null;
            try (Closeable pin = identityPin) {
                identityPin = null;
                if (channel != null) {
                    FileChannel retained = channel;
                    channel = null;
                    Destination.closeAfterUse(fileKey, retained);
                }
            }
        }
    }

    private static IOException conflictingReports(Path path) {
        return new IOException("Exclusion report and coverage output must use different files: " + path);
    }

    private static final class ReportIdentity implements Closeable {
        private final Object key;
        private PosixFiles.Pin pin;

        private ReportIdentity(Object key, PosixFiles.Pin pin) {
            this.key = key;
            this.pin = pin;
        }

        private static ReportIdentity retain(Destination output) throws IOException {
            if (output == null || output.fileKey == null) {
                return null;
            }
            // Use the reserved file before its public name can be replaced.
            Path path = output.privateName == null ? output.path : output.privateName.path;
            PosixFiles.Pin pin = PosixFiles.pinOnLinux(path);
            Path descriptor = pin == null ? null : pin.descriptorPath();
            Path source = descriptor == null ? path : descriptor;
            try {
                Object key = StandardOutputFile.regularFileKey(source);
                var attributes = Files.readAttributes(source, BasicFileAttributes.class);
                if (key == null || !output.fileKey.equals(SYSTEM_FILE_KEYS.read(source, attributes))) {
                    throw new IOException("Report destination changed during publication: " + output.path);
                }
                return new ReportIdentity(key, pin);
            } catch (IOException | RuntimeException error) {
                if (pin != null) {
                    try {
                        pin.close();
                    } catch (IOException cleanup) {
                        error.addSuppressed(cleanup);
                    }
                }
                throw error;
            }
        }

        private Closeable takePin() {
            PosixFiles.Pin retained = pin;
            pin = null;
            return retained;
        }

        @Override
        public void close() throws IOException {
            if (pin != null) {
                pin.close();
            }
        }
    }

    private static IOException unverifiableReport(Path path) {
        return new IOException("Cannot verify separate outputs for " + path
                + " and an unknown stream. Use separate file paths or pass a ByteArrayOutputStream.");
    }

    static void publish(Path coverage, Path report, Content coverageContent, Content reportContent,
                        OutputStream stdout) throws IOException {
        publish(coverage, report, coverageContent, reportContent, stdout, SYSTEM_FILE_KEYS);
    }

    static void publish(Path coverage, Path report, Content coverageContent, Content reportContent,
                        OutputStream stdout, FileKeys keys) throws IOException {
        publish(coverage, report, coverageContent, reportContent, stdout, false, keys, null, null, null);
    }

    private static void publish(Path coverage, Path report, Content coverageContent, Content reportContent,
                                OutputStream stdout, boolean createOnly, FileKeys keys, Path verifyReport,
                                DestinationCheck check, PublicationCheck prepared) throws IOException {
        if (prepared != null) {
            prepared.verify();
        }
        boolean newPair = coverage != null && report != null && Files.notExists(coverage, LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(report, LinkOption.NOFOLLOW_LINKS);
        try (Destination coverageFile = Destination.open(coverage, createOnly, keys, newPair, report);
             Destination reportFile = Destination.open(report, createOnly, keys, newPair, coverage)) {
            if (check != null) {
                checkExistingDestination(coverageFile, check);
                checkExistingDestination(reportFile, check);
            }
            if (coverageFile != null && reportFile != null
                    && coverageFile.access == Access.CREATED && reportFile.access == Access.CREATED
                    && (coverageFile.claim == null || reportFile.claim == null)) {
                throw new IOException("Cannot reserve both new reports: " + coverage + " and " + report);
            }
            if (reportFile != null && reportFile.access == Access.WRITE_ONLY) {
                if (verifyReport != null) {
                    throw new IOException("Cannot verify separate outputs for a write-only report and an unknown stream: "
                            + report + ". Use separate file paths or pass a ByteArrayOutputStream.");
                }
                // Publish the output with no possible backup last.
                writeResult(coverage, coverageFile, coverageContent, stdout);
                writeResult(report, reportFile, reportContent, null);
            } else {
                if (report != null) {
                    writeResult(report, reportFile, reportContent, null);
                }
                writeResult(coverage, coverageFile, coverageContent, stdout);
                if (reportFile != null && verifyReport != null) {
                    reportFile.verifyContents(verifyReport);
                }
            }
            if (coverageFile != null) {
                coverageFile.checkNewName();
            }
            if (reportFile != null) {
                reportFile.checkNewName();
            }
            if (prepared != null) {
                prepared.verify();
            }
            if (reportFile != null) {
                reportFile.installNewOutput(coverage);
            }
            if (coverageFile != null) {
                try {
                    coverageFile.installNewOutput(report);
                    coverageFile.committed = true;
                } catch (IOException failure) {
                    if (reportFile != null && reportFile.privateName != null) {
                        throw new IOException("Cannot publish " + coverage + "; new exclusion report was saved to " + report,
                                failure);
                    }
                    throw failure;
                }
            }
            if (reportFile != null) {
                reportFile.committed = true;
            }
        }
    }

    private static void checkExistingDestination(Destination destination, DestinationCheck check) throws IOException {
        if (destination != null && destination.access != Access.CREATED) {
            try (ReportIdentity identity = ReportIdentity.retain(destination)) {
                if (identity != null) {
                    check.verify(destination.path, identity.key);
                }
            }
        }
    }

    private static void writeResult(Path path, Destination destination, Content content, OutputStream stdout)
            throws IOException {
        if (destination != null) {
            destination.modified = true;
            destination.replace(content);
        } else {
            // stdout and explicitly selected device/pipe paths are streams and must never be replaced.
            try (OutputStream output = path == null ? stdout : Files.newOutputStream(path, StandardOpenOption.WRITE)) {
                content.copyTo(output);
            }
        }
    }

    static boolean isSpecialFile(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.isOther() && !isRegularReport(path, attributes);
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static boolean isRegularReport(Path path, BasicFileAttributes attributes) throws IOException {
        return attributes.isRegularFile() || (attributes.isOther()
                && File.separatorChar == '\\' && path.getFileSystem() == FileSystems.getDefault()
                && WindowsFiles.reparseReportKey(path) != null);
    }

    private static Object windowsFileKey(Path path, LinkOption... options) throws IOException {
        return File.separatorChar == '\\' && path.getFileSystem() == FileSystems.getDefault()
                ? WindowsFiles.key(path, options.length > 0) : null;
    }

    private static final class Temporary implements Closeable {
        private final Path path;

        private final boolean inPrivateDirectory;

        private Temporary(Path path) {
            this(path, false);
        }

        private Temporary(Path path, boolean inPrivateDirectory) {
            this.path = path;
            this.inPrivateDirectory = inPrivateDirectory;
        }

        private static Path createPrivateFile(Path parent) throws IOException {
            if (parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                return Files.createTempFile(parent, ".coverage41c-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            if (File.separatorChar == '\\' && parent.getFileSystem() == FileSystems.getDefault()
                    && Files.getFileStore(parent).supportsFileAttributeView("acl")) {
                Path path = parent.resolve(".coverage41c-" + UUID.randomUUID() + ".tmp");
                WindowsFiles.createPrivateFile(path);
                return path;
            }
            throw new IOException("Temporary reports require a filesystem with private file permissions");
        }

        static Temporary forNewOutput(Path parent) throws IOException {
            Path directory = Files.createTempDirectory(parent, ".coverage41c-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return new Temporary(directory.resolve("output"), true);
        }

        static Temporary create(Path near) throws IOException {
            IOException besideFailure = null;
            Path parent = near == null ? null : near.toAbsolutePath().getParent();
            if (parent != null && !isSpecialFile(near)) {
                try {
                    return new Temporary(createPrivateFile(parent));
                } catch (IOException failure) {
                    if (File.separatorChar != '\\' && (!Files.isRegularFile(near) || !Files.isWritable(near))) {
                        throw new IOException("Cannot prepare report output: " + near, failure);
                    }
                    // A named output may be writable even where staging is denied or private ACLs are unavailable.
                    besideFailure = failure;
                }
            }
            try {
                return new Temporary(createPrivateFile(Path.of(System.getProperty("java.io.tmpdir"))));
            } catch (IOException failure) {
                if (besideFailure != null) {
                    failure.addSuppressed(besideFailure);
                    throw new IOException("Cannot prepare report output: " + near, failure);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
            if (inPrivateDirectory) {
                Files.delete(path.getParent());
            }
        }
    }

    // Directory entries give claims the same case and Unicode matching as the output names.
    private static final class CreationClaim implements Closeable {
        // A publication can name up to two outputs in the same directory.
        private static final List<String> NAMESPACES =
                List.of(".coverage41c-creating", ".coverage41c-creating-1", ".coverage41c-creating-2");
        private static final byte[] OWNERSHIP_STAMP =
                "Coverage41C creation claims\n".getBytes(StandardCharsets.UTF_8);
        private final Path marker;
        private final boolean namespaceDestination;

        private CreationClaim(Path marker, boolean namespaceDestination) {
            this.marker = marker;
            this.namespaceDestination = namespaceDestination;
        }

        private static Path destinationFor(Path path) throws IOException {
            Path destination = Utils.resolveOutputPath(path.toAbsolutePath());
            if (destination.getFileName() == null) {
                throw new IOException("Report destination is not a regular file: " + path);
            }
            return destination;
        }

        private static IOException inProgress(Path path, Path marker) {
            return new IOException("Report creation is in progress for " + path
                    + ". After an interrupted conversion, check " + marker);
        }

        static void rejectIfHeld(Path path) throws IOException {
            rejectIfHeld(path, destinationFor(path), null);
        }

        private static void rejectIfHeld(Path path, Path destination, Path ownMarker) throws IOException {
            Path marker = findClaim(destination, ownMarker);
            if (marker != null) {
                throw inProgress(path, marker);
            }
        }

        private static Path findClaim(Path destination, Path ownMarker) {
            for (String name : NAMESPACES) {
                Path namespace = destination.resolveSibling(name);
                Path marker = namespace.resolve(destination.getFileName());
                if (!marker.equals(ownMarker) && Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)) {
                    return marker;
                }
            }
            return null;
        }

        private static final class BusyNamespace extends IOException { }

        static CreationClaim acquire(Path path, Path peer) throws IOException {
            while (true) {
                try {
                    return selectNamespace(path, peer);
                } catch (BusyNamespace busy) {
                    waitForCreator(path);
                }
            }
        }

        private static CreationClaim selectNamespace(Path path, Path peer) throws IOException {
            Path destination = destinationFor(path);
            rejectIfHeld(path, destination, null);
            Path peerDestination = peer == null ? null : Utils.resolveOutputPath(peer.toAbsolutePath());
            boolean busy = false;
            for (String name : NAMESPACES) {
                Path namespace = destination.resolveSibling(name);
                if (Files.isSymbolicLink(namespace) || Files.isRegularFile(namespace, LinkOption.NOFOLLOW_LINKS)
                        || findClaim(namespace, null) != null
                        || Utils.sameOutputFile(namespace, destination)
                        || (peerDestination != null && Utils.sameOutputFile(namespace, peerDestination))) {
                    continue;
                }
                Path marker = namespace.resolve(destination.getFileName());
                CreationClaim claim;
                try {
                    claim = acquireInNamespace(path, marker);
                } catch (BusyNamespace contender) {
                    busy = true;
                    continue;
                }
                if (claim == null) {
                    continue;
                }
                try {
                    // Another publication can select a different namespace for the same destination.
                    rejectIfHeld(path, destination, marker);
                    return claim;
                } catch (IOException | RuntimeException error) {
                    try {
                        claim.close();
                    } catch (IOException cleanup) {
                        error.addSuppressed(cleanup);
                    }
                    throw error;
                }
            }
            if (busy) {
                throw new BusyNamespace();
            }
            // A single new POSIX output can be installed atomically without a claim.
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                return null;
            }
            throw new IOException("No report creation marker directory is available for " + path);
        }

        private static CreationClaim acquireInNamespace(Path path, Path marker) throws IOException {
            Path namespace = marker.getParent();
            while (findClaim(namespace, null) == null) {
                Closeable lock;
                try {
                    lock = enterNamespace(namespace);
                } catch (FileAlreadyExistsException | NoSuchFileException changed) {
                    if (!Files.isDirectory(namespace.getParent())) {
                        throw changed;
                    }
                    continue;
                } catch (NotDirectoryException | AccessDeniedException | UnsupportedOperationException unavailable) {
                    return null;
                }
                if (lock == null) {
                    return null;
                }
                boolean reserved = false;
                try (lock) {
                    if (findClaim(namespace, null) != null) {
                        return null;
                    }
                    CreationClaim claim = new CreationClaim(marker, isNamespaceName(destinationFor(path)));
                    try {
                        Files.createDirectory(marker);
                    } catch (FileAlreadyExistsException busy) {
                        throw inProgress(path, marker);
                    } catch (AccessDeniedException unavailable) {
                        if (Files.isExecutable(namespace)) {
                            return null;
                        }
                        throw unavailable;
                    } catch (IOException changed) {
                        if (Files.isSymbolicLink(namespace) || Files.isRegularFile(namespace, LinkOption.NOFOLLOW_LINKS)) {
                            return null;
                        }
                        if (!(changed instanceof NoSuchFileException) || !Files.isDirectory(namespace.getParent())) {
                            throw changed;
                        }
                        continue;
                    }
                    if (!PosixFiles.supportsNamespaceLeases(namespace) && findClaim(namespace, null) != null) {
                        Files.delete(marker);
                        return null;
                    }
                    reserved = true;
                    return claim;
                } finally {
                    if (!reserved) {
                        cleanupNamespace(namespace);
                    }
                }
            }
            return null;
        }

        private static PosixFiles.Pin createLeasedNamespace(Path namespace) throws IOException {
            Path scratch = Files.createTempDirectory(namespace.getParent(), ".coverage41c-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path staged = scratch.resolve("claims");
            boolean installed = false;
            try {
                Files.createDirectory(staged);
                var permissions = Files.getPosixFilePermissions(staged);
                permissions.add(PosixFilePermission.GROUP_EXECUTE);
                permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(staged, permissions);
                Path stamp = staged.resolve(namespace.getFileName());
                writeOwnershipStamp(stamp);
                var stampAccess = Files.getPosixFilePermissions(stamp);
                stampAccess.add(PosixFilePermission.GROUP_READ);
                stampAccess.add(PosixFilePermission.OTHERS_READ);
                if (permissions.contains(PosixFilePermission.GROUP_WRITE)) {
                    stampAccess.add(PosixFilePermission.GROUP_WRITE);
                }
                if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    stampAccess.add(PosixFilePermission.OTHERS_WRITE);
                }
                Files.setPosixFilePermissions(stamp, stampAccess);
                PosixFiles.Pin lease = PosixFiles.tryNamespaceStampLock(stamp, true);
                if (lease == null) {
                    return null;
                }
                boolean retained = false;
                try {
                    PosixFiles.moveNewFile(staged, namespace);
                    installed = true;
                    if (findClaim(namespace, null) != null) {
                        // POSIX refuses to rename a regular file over a directory. Keep the stamp
                        // visible until the still-locked new namespace has left the public name.
                        withdrawNamespace(namespace, staged, lease);
                        installed = false;
                        return null;
                    }
                    retained = true;
                    return lease;
                } catch (FileAlreadyExistsException raced) {
                    throw raced;
                } catch (IOException unsupported) {
                    if (installed) {
                        throw unsupported;
                    }
                    // POSIX standalone publication still has its exclusive hard-link operation.
                    return null;
                } finally {
                    if (!retained) {
                        lease.close();
                    }
                }
            } finally {
                if (!installed) {
                    Files.deleteIfExists(staged.resolve(namespace.getFileName()));
                    Files.deleteIfExists(staged);
                }
                try {
                    Files.delete(scratch);
                } catch (DirectoryNotEmptyException retained) {
                    // A concurrently replaced directory is retained for the owner at the path in the error.
                }
            }
        }

        private static void withdrawNamespace(Path namespace, Path staged, PosixFiles.Pin lease) throws IOException {
            if (!PosixFiles.namespaceStampStillNamed(lease, ownershipPath(namespace))) {
                throw new IOException("Report creation directory changed: " + namespace);
            }
            Files.createDirectory(staged);
            try {
                Files.move(namespace, staged, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                Files.delete(staged);
                throw failure;
            }
            if (!PosixFiles.namespaceStampStillNamed(lease, staged.resolve(namespace.getFileName()))) {
                IOException changed = new IOException("Report creation directory changed: " + namespace);
                try {
                    PosixFiles.moveNewFile(staged, namespace);
                } catch (IOException occupied) {
                    changed.addSuppressed(new IOException("Replacement directory retained at " + staged, occupied));
                }
                throw changed;
            }
        }

        private static Closeable enterNamespace(Path namespace) throws IOException {
            if (PosixFiles.supportsNamespaceLeases(namespace)) {
                if (Files.notExists(namespace, LinkOption.NOFOLLOW_LINKS)) {
                    return createLeasedNamespace(namespace);
                }
                if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)) {
                    return null;
                }
                Path stamp = ownershipPath(namespace);
                if (!Files.isRegularFile(stamp, LinkOption.NOFOLLOW_LINKS)) {
                    // Existing user directories are never removed by the ownership-stamp cleanup.
                    return () -> { };
                }
                PosixFiles.Pin lease = PosixFiles.tryNamespaceStampLock(stamp, false);
                if (lease == null) {
                    throw new BusyNamespace();
                }
                try {
                    if (!PosixFiles.namespaceStampStillNamed(lease, stamp)) {
                        throw new NoSuchFileException(namespace.toString());
                    }
                    return lease;
                } catch (IOException | RuntimeException error) {
                    lease.close();
                    throw error;
                }
            }
            try {
                createNamespace(namespace);
            } catch (FileAlreadyExistsException occupied) {
                var attributes = Files.readAttributes(namespace, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    return null;
                }
                if (!attributes.isDirectory()) {
                    throw new IOException("Report creation marker path is not a directory: " + namespace, occupied);
                }
            }
            return () -> { };
        }

        private static boolean isNamespaceName(Path path) throws IOException {
            if (NAMESPACES.contains(path.getFileName().toString())) {
                return true;
            }
            for (String name : NAMESPACES) {
                if (Utils.sameOutputFile(path, path.resolveSibling(name))) {
                    return true;
                }
            }
            return false;
        }

        // The creator locked its directory before making it visible and keeps that lock through
        // the filename-claim check and withdrawal. An unrelated or abandoned directory is unlocked.
        private static void awaitNamespaceRelease(Path path) throws IOException {
            while (true) {
                try {
                    var previous = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!previous.isDirectory() || !PosixFiles.supportsNamespaceLeases(path)) {
                        throw new FileAlreadyExistsException(path.toString());
                    }
                    Path stamp = ownershipPath(path);
                    if (!Files.isRegularFile(stamp, LinkOption.NOFOLLOW_LINKS)) {
                        var current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (java.util.Objects.equals(previous.fileKey(), current.fileKey())
                                && !Files.isRegularFile(stamp, LinkOption.NOFOLLOW_LINKS)) {
                            throw new FileAlreadyExistsException(path.toString());
                        }
                        continue;
                    }
                    try (PosixFiles.Pin idle = PosixFiles.tryNamespaceStampLock(stamp, false)) {
                        if (idle != null && PosixFiles.namespaceStampStillNamed(idle, stamp)) {
                            throw new FileAlreadyExistsException(path.toString());
                        }
                    }
                } catch (NoSuchFileException removed) {
                    return;
                } catch (NotDirectoryException changed) {
                    continue;
                } catch (AccessDeniedException | UnsupportedOperationException unrelated) {
                    throw new FileAlreadyExistsException(path.toString());
                }
                waitForCreator(path);
            }
        }

        private static void waitForCreator(Path path) throws IOException {
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to publish " + path, interrupted);
            }
        }

        private static void createNamespace(Path namespace) throws IOException {
            Files.createDirectory(namespace);
            try {
                writeOwnershipStamp(ownershipPath(namespace));
            } catch (IOException failure) {
                try {
                    Files.delete(namespace);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private static Path ownershipPath(Path namespace) {
            // acquire excludes the namespace's own name from the report names it can reserve.
            return namespace.resolve(namespace.getFileName());
        }

        private static void writeOwnershipStamp(Path owner) throws IOException {
            Files.write(owner, OWNERSHIP_STAMP, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        private static boolean isOwned(Path owner) throws IOException {
            if (!Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            // Windows must not leave the stamp delete-pending while another closer is reading it.
            OpenOption[] options = File.separatorChar == '\\'
                    ? new OpenOption[]{LinkOption.NOFOLLOW_LINKS, ExtendedOpenOption.NOSHARE_DELETE}
                    : new OpenOption[]{LinkOption.NOFOLLOW_LINKS};
            try (InputStream input = Files.newInputStream(owner, options)) {
                return Arrays.equals(OWNERSHIP_STAMP, input.readNBytes(OWNERSHIP_STAMP.length + 1));
            }
        }

        private static void removeEmptyNamespace(Path namespace) throws IOException {
            Path owner = ownershipPath(namespace);
            while (Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS) && isOwned(owner)) {
                try (DirectoryStream<Path> claims = Files.newDirectoryStream(namespace, entry -> !entry.equals(owner))) {
                    if (claims.iterator().hasNext()) {
                        return;
                    }
                }
                if (!Files.deleteIfExists(owner)) {
                    return;
                }
                try {
                    if (PosixFiles.supportsNamespaceLeases(namespace)) {
                        PosixFiles.removeNamespaceDirectory(namespace);
                    } else {
                        Files.deleteIfExists(namespace);
                    }
                    return;
                } catch (IOException failure) {
                    // A publisher arrived during cleanup. Restore ownership and check again in case it already left.
                    try {
                        writeOwnershipStamp(owner);
                    } catch (IOException restore) {
                        failure.addSuppressed(restore);
                        throw failure;
                    }
                    if (!(failure instanceof DirectoryNotEmptyException)) {
                        throw failure;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            Files.delete(marker);
            cleanupNamespace(marker.getParent());
        }

        private static void cleanupNamespace(Path namespace) {
            try {
                if (!PosixFiles.supportsNamespaceLeases(namespace)) {
                    removeEmptyNamespace(namespace);
                    return;
                }
                Path stamp = ownershipPath(namespace);
                if (!isOwned(stamp)) {
                    return;
                }
                try (PosixFiles.Pin lease = PosixFiles.tryNamespaceStampLock(stamp, true)) {
                    if (lease == null || !PosixFiles.namespaceStampStillNamed(lease, stamp)) {
                        return;
                    }
                    try (DirectoryStream<Path> claims = Files.newDirectoryStream(namespace, entry -> !entry.equals(stamp))) {
                        if (claims.iterator().hasNext()) {
                            return;
                        }
                    }
                    Path scratch = Files.createTempDirectory(namespace.getParent(), ".coverage41c-",
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    Path staged = scratch.resolve("claims");
                    try {
                        // A retiring namespace must also remain stamped until its public name is gone.
                        withdrawNamespace(namespace, staged, lease);
                        Files.delete(staged.resolve(namespace.getFileName()));
                        PosixFiles.removeNamespaceDirectory(staged);
                    } finally {
                        Files.delete(scratch);
                    }
                }
            } catch (IOException | DirectoryIteratorException | UnsupportedOperationException cleanup) {
                // Cleanup is best effort. An existing marker directory can be reused.
            }
        }
    }

    private enum Access { CREATED, READ_WRITE, WRITE_ONLY }

    private static final class Destination implements Closeable {
        // On POSIX, closing another channel to the same inode can drop this JVM's OS lock.
        // Reject local contenders before open; unrelated files and output streams remain independent.
        private static final Set<Destination> ACTIVE = new HashSet<>();
        private static final List<RejectedChannel> REJECTED_CHANNELS = new ArrayList<>();

        private record RejectedChannel(FileChannel channel, Path path, Set<Object> observedKeys,
                                       Set<Destination> possibleOwners) {}

        private final Object fileKey;
        private final Path path;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean keepChannel;
        private final List<FileChannel> deferredClose = new ArrayList<>();
        private final Access access;
        private final Temporary privateName;
        private final WindowsFiles.PendingOutput pendingOutput;
        private final CreationClaim claim;
        private final boolean useHardLink;
        private Temporary backup;
        private boolean modified;
        private boolean committed;

        private Destination(Path path, FileChannel channel, FileLock lock, Access access, Object fileKey,
                            Temporary privateName, WindowsFiles.PendingOutput pendingOutput, CreationClaim claim,
                            boolean useHardLink) {
            this.path = path;
            this.channel = channel;
            this.lock = lock;
            this.access = access;
            this.privateName = privateName;
            this.fileKey = fileKey;
            this.pendingOutput = pendingOutput;
            this.claim = claim;
            this.useHardLink = useHardLink;
        }

        static Destination open(Path path, boolean createOnly, FileKeys keys) throws IOException {
            return open(path, createOnly, keys, false, null);
        }

        static Destination open(Path path, boolean createOnly, FileKeys keys, boolean claimNewOutput, Path peer)
                throws IOException {
            if (path == null || (!createOnly && isSpecialFile(path))) {
                return null;
            }
            return reserve(path, createOnly, keys, claimNewOutput, peer).withBackup();
        }

        private Destination withBackup() throws IOException {
            try {
                if (access == Access.READ_WRITE) {
                    backup = Temporary.create(path);
                    channel.position(0);
                    try (OutputStream output = Files.newOutputStream(backup.path)) {
                        Channels.newInputStream(channel).transferTo(output);
                    }
                }
                return this;
            } catch (IOException | RuntimeException error) {
                try {
                    close();
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        private static synchronized Destination reserve(Path path, boolean createOnly, FileKeys keys,
                                                        boolean claimNewOutput, Path peer) throws IOException {
            Destination destination = !createOnly && Files.exists(path)
                    ? openExisting(path, keys) : create(path, createOnly, keys, claimNewOutput, peer);
            ACTIVE.add(destination);
            return destination;
        }

        private static synchronized Destination reservePublished(PublishedReport published, Path name) throws IOException {
            rejectActive(name, published.fileKey);
            FileLock acquired = lock(published.channel, name);
            Destination original = new Destination(name, published.channel, acquired, Access.READ_WRITE,
                    published.fileKey, null, null, null, false);
            original.keepChannel = true;
            ACTIVE.add(original);
            return original;
        }

        private static void rejectActive(Path path, Object fileKey) throws IOException {
            for (Destination active : ACTIVE) {
                if (active.matches(path, fileKey)) {
                    throw new IOException("Another conversion is writing " + path);
                }
            }
            for (RejectedChannel rejected : REJECTED_CHANNELS) {
                if (path.equals(rejected.path) || (fileKey != null && rejected.observedKeys.contains(fileKey))) {
                    throw new IOException("Another conversion is releasing a report lock; retry writing " + path);
                }
                for (Destination possibleOwner : rejected.possibleOwners) {
                    if (possibleOwner.matches(path, fileKey)) {
                        // Keep the candidate reserved even after its original lock has been released.
                        throw new IOException("Another conversion is releasing a report lock; retry writing " + path);
                    }
                }
            }
        }

        private boolean matches(Path candidate, Object candidateKey) throws IOException {
            if (fileKey != null && candidateKey != null) {
                return fileKey.equals(candidateKey);
            }
            try {
                Path currentName = privateName != null && Files.exists(privateName.path) ? privateName.path : path;
                return Files.isSameFile(candidate, currentName);
            } catch (NoSuchFileException removed) {
                return false;
            }
        }

        private static Destination openExisting(Path path, FileKeys keys) throws IOException {
            BasicFileAttributes previous = Files.readAttributes(path, BasicFileAttributes.class);
            if (!isRegularReport(path, previous)) {
                throw new IOException("Report destination is not a regular file: " + path);
            }
            Object previousKey = keys.read(path, previous);
            rejectActive(path, previousKey);
            CreationClaim.rejectIfHeld(path);
            FileChannel channel;
            Access access = Access.READ_WRITE;
            try {
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            } catch (AccessDeniedException denied) {
                channel = FileChannel.open(path, StandardOpenOption.WRITE);
                access = Access.WRITE_ONLY;
            }
            try {
                FileLock acquired = lock(channel, path);
                // Recheck after open and lock in case another creator claimed the name meanwhile.
                CreationClaim.rejectIfHeld(path);
                // An earlier publisher may have rolled back and unlinked this file after open.
                BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class);
                Object currentKey = keys.read(path, current);
                if (previousKey != null && !previousKey.equals(currentKey)) {
                    throw new IOException("Report destination changed during conversion: " + path);
                }
                if (previousKey == null && currentKey != null) {
                    rejectActive(path, currentKey);
                }
                return new Destination(path, channel, acquired, access, currentKey, null, null, null, false);
            } catch (IOException | RuntimeException error) {
                // A key read by name does not identify the open channel if the entry changed.
                if (error.getCause() instanceof OverlappingFileLockException
                        && File.separatorChar != '\\' && !ACTIVE.isEmpty()) {
                    Set<Object> observedKeys = new HashSet<>();
                    if (previousKey != null) {
                        observedKeys.add(previousKey);
                    }
                    try {
                        Object currentKey = keys.read(path, Files.readAttributes(path, BasicFileAttributes.class));
                        if (currentKey != null) {
                            observedKeys.add(currentKey);
                        }
                    } catch (IOException | RuntimeException unavailable) {
                        error.addSuppressed(unavailable);
                    }
                    REJECTED_CHANNELS.add(new RejectedChannel(channel, path, observedKeys, Set.copyOf(ACTIVE)));
                } else {
                    try {
                        channel.close();
                    } catch (IOException closeError) {
                        error.addSuppressed(closeError);
                    }
                }
                throw error;
            }
        }

        // Closing a second descriptor can release this JVM's POSIX lock on the same inode.
        private static synchronized void closeAfterUse(Object fileKey, FileChannel channel) throws IOException {
            for (Destination active : ACTIVE) {
                if (fileKey.equals(active.fileKey)) {
                    active.deferredClose.add(channel);
                    return;
                }
            }
            channel.close();
        }

        private static Destination create(Path path, boolean createOnly, FileKeys keys, boolean claimNewOutput, Path peer)
                throws IOException {
            boolean posix = path.getFileSystem().supportedFileAttributeViews().contains("posix");
            CreationClaim claim = claimNewOutput || !keys.usePrivateLinks || !posix ? CreationClaim.acquire(path, peer) : null;
            try {
                if (claim == null) {
                    CreationClaim.rejectIfHeld(path);
                }
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(path.toString());
                }
                if (posix) {
                    return createPrivate(path, keys, claim);
                }
                if (File.separatorChar == '\\' && path.getFileSystem() == FileSystems.getDefault()) {
                    return createWindows(path, keys, claim);
                }
                throw new IOException("Cannot publish a new report safely on this filesystem: " + path);
            } catch (IOException | RuntimeException error) {
                if (claim != null) {
                    try {
                        claim.close();
                    } catch (IOException closeError) {
                        error.addSuppressed(closeError);
                    }
                }
                if (!createOnly && error instanceof FileAlreadyExistsException) {
                    return openExisting(path, keys);
                }
                throw error;
            }
        }

        private static Destination createPrivate(Path path, FileKeys keys, CreationClaim claim) throws IOException {
            Temporary scratch = Temporary.forNewOutput(path.toAbsolutePath().getParent());
            try {
                return lockNewOutput(path, scratch.path, keys, scratch, null, claim, StandardOpenOption.CREATE_NEW);
            } catch (IOException | RuntimeException failure) {
                try {
                    scratch.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private static Destination createWindows(Path path, FileKeys keys, CreationClaim claim) throws IOException {
            WindowsFiles.PendingOutput pending = WindowsFiles.createPendingOutput(path);
            try {
                return lockNewOutput(path, path, keys, null, pending, claim, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException | RuntimeException failure) {
                try (pending) {
                    pending.rollback();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private static Destination lockNewOutput(Path destination, Path file, FileKeys keys, Temporary scratch,
                                                 WindowsFiles.PendingOutput pending, CreationClaim claim,
                                                 OpenOption option) throws IOException {
            FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, option);
            try {
                BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Object key = isRegularReport(file, attrs) ? keys.read(file, attrs, LinkOption.NOFOLLOW_LINKS) : null;
                if (key == null) {
                    throw new IOException("Cannot identify new report destination for safe rollback: " + destination);
                }
                return new Destination(destination, channel, lock(channel, file), Access.CREATED,
                        key, scratch, pending, claim, keys.usePrivateLinks);
            } catch (IOException | RuntimeException failure) {
                try {
                    channel.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private void checkNewName() throws IOException {
            if (privateName != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (claim != null && claim.namespaceDestination) {
                    CreationClaim.awaitNamespaceRelease(path);
                }
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(path.toString());
                }
            }
        }

        private void installNewOutput(Path peer) throws IOException {
            if (privateName == null) {
                return;
            }
            // A single-file publisher also cooperates with claims held for a pair of new reports.
            try (CreationClaim publication = claim == null ? CreationClaim.acquire(path, peer) : null) {
                while (true) {
                    try {
                        if (useHardLink) {
                            linkOrMove();
                        } else {
                            PosixFiles.moveNewFile(privateName.path, path);
                        }
                        return;
                    } catch (FileAlreadyExistsException conflict) {
                        CreationClaim held = claim == null ? publication : claim;
                        if (held == null || !held.namespaceDestination) {
                            throw conflict;
                        }
                        CreationClaim.awaitNamespaceRelease(path);
                    }
                }
            }
        }

        private void linkOrMove() throws IOException {
            try {
                Files.createLink(path, privateName.path);
            } catch (FileAlreadyExistsException collision) {
                throw collision;
            } catch (UnsupportedOperationException | IOException linkUnavailable) {
                try {
                    PosixFiles.moveNewFile(privateName.path, path);
                } catch (IOException moveFailure) {
                    moveFailure.addSuppressed(linkUnavailable);
                    throw moveFailure;
                }
            }
        }

        private static FileLock lock(FileChannel channel, Path path) throws IOException {
            try {
                FileLock acquired = channel.tryLock();
                if (acquired != null) {
                    return acquired;
                }
            } catch (OverlappingFileLockException busy) {
                throw new IOException("Another conversion is writing " + path, busy);
            }
            throw new IOException("Another conversion is writing " + path);
        }

        private void replace(Content content) throws IOException {
            channel.position(0);
            content.copyTo(Channels.newOutputStream(channel));
            channel.truncate(channel.position());
            channel.force(false);
        }

        private void verifyContents(Path staged) throws IOException {
            // Reuse the locked descriptor. Opening and closing another channel could release the POSIX lock.
            channel.position(0);
            InputStream published = Channels.newInputStream(channel);
            byte[] expected = new byte[8192];
            byte[] actual = new byte[expected.length];
            try (InputStream source = Files.newInputStream(staged)) {
                int count;
                do {
                    count = source.readNBytes(expected, 0, expected.length);
                    if (published.readNBytes(actual, 0, actual.length) != count
                            || !Arrays.equals(expected, 0, count, actual, 0, count)) {
                        throw conflictingReports(path);
                    }
                } while (count == expected.length);
            }
        }

        private void verifyUnchanged() throws IOException {
            if (backup == null) {
                if (channel.size() != 0) {
                    throw conflictingReports(path);
                }
            } else {
                // close() restores the snapshot only when the borrowed stream actually changed the file.
                modified = true;
                verifyContents(backup.path);
                modified = false;
            }
        }

        @Override
        public void close() throws IOException {
            try (CreationClaim creation = claim; Closeable unregister = this::finish; Temporary name = privateName;
                 WindowsFiles.PendingOutput pending = pendingOutput;
                 Closeable locked = keepChannel ? lock::release : channel) {
                if (!committed && pending != null) {
                    pending.rollback();
                } else if (!committed && modified && backup != null) {
                    try {
                        replace(output -> Files.copy(backup.path, output));
                    } catch (IOException failure) {
                        throw new IOException("Could not restore " + path + "; original saved at " + backup.path, failure);
                    }
                }
                if (backup != null) {
                    backup.close();
                }
            }
        }

        private void finish() throws IOException {
            synchronized (Destination.class) {
                ACTIVE.remove(this);
                REJECTED_CHANNELS.removeIf(rejected -> {
                    if (!Collections.disjoint(ACTIVE, rejected.possibleOwners)) {
                        return false;
                    }
                    deferredClose.add(rejected.channel);
                    return true;
                });
                IOException failure = null;
                for (FileChannel descriptor : deferredClose) {
                    try {
                        descriptor.close();
                    } catch (IOException error) {
                        if (failure == null) {
                            failure = error;
                        } else {
                            failure.addSuppressed(error);
                        }
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        }
    }
}
