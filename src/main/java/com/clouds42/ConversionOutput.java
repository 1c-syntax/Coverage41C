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

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    static void writeFile(Path destination, Writer writer) throws IOException {
        write(destination, null, writer, null, null);
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
        OutputStream stream = coverage == null ? Utils.reportStdout(stdout) : null;
        try (Temporary coverageStage = Temporary.create(coverage);
             Temporary reportStage = report == null ? null : Temporary.create(report)) {
            writeCoverage.write(coverageStage.path);
            if (reportStage != null) {
                writeReport.write(reportStage.path);
            }

            // Java cannot identify the destination of a caller's PrintStream or arbitrary OutputStream.
            Path verifyReport = stream != null && reportStage != null && needsFileVerification(stdout)
                    ? reportStage.path : null;
            publish(coverage, report, output -> Files.copy(coverageStage.path, output),
                    reportStage == null ? null : output -> Files.copy(reportStage.path, output),
                    stream, createOnly, SYSTEM_FILE_KEYS, verifyReport);
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
            if (Files.exists(path) && (!Files.isRegularFile(path) || !Files.isReadable(path))) {
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
            try (Destination original = Destination.reservePublished(published, path).withBackup();
                 Closeable verify = original::verifyUnchanged) {
                if (published.hasReplacement(path)) {
                    writeWithNamedPeer(stream, path, writer);
                } else {
                    writer.copyTo(stream);
                }
            }
        } else {
            writeWithNamedPeer(stream, path, writer);
        }
    }

    private static void writeWithNamedPeer(OutputStream stream, Path path, Content writer) throws IOException {
        try (Destination other = Destination.open(path, false, SYSTEM_FILE_KEYS)) {
            if (other == null || other.access == Access.WRITE_ONLY) {
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
        private FileChannel channel;

        private boolean hasReplacement(Path path) throws IOException {
            try {
                BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class);
                return !fileKey.equals(SYSTEM_FILE_KEYS.read(path, current));
            } catch (NoSuchFileException movedOrRemoved) {
                return false;
            }
        }

        void write(Path path, Writer writer) throws IOException {
            try (Temporary staged = Temporary.create(path)) {
                writer.write(staged.path);
                try (Destination output = Destination.open(path, false, SYSTEM_FILE_KEYS)) {
                    writeResult(path, output, stream -> Files.copy(staged.path, stream), null);
                    if (output != null) {
                        output.committed = true;
                        if (output.fileKey != null && output.access != Access.WRITE_ONLY) {
                            fileKey = output.fileKey;
                            channel = output.channel;
                            output.keepChannel = true;
                        }
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (channel != null) {
                Destination.closeAfterUse(fileKey, channel);
                channel = null;
            }
        }
    }

    private static IOException conflictingReports(Path path) {
        return new IOException("Exclusion report and coverage output must use different files: " + path);
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
        publish(coverage, report, coverageContent, reportContent, stdout, false, keys, null);
    }

    private static void publish(Path coverage, Path report, Content coverageContent, Content reportContent,
                                OutputStream stdout, boolean createOnly, FileKeys keys, Path verifyReport) throws IOException {
        try (Destination coverageFile = Destination.open(coverage, createOnly, keys);
             Destination reportFile = Destination.open(report, createOnly, keys)) {
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
            if (reportFile != null) {
                reportFile.committed = true;
            }
            if (coverageFile != null) {
                coverageFile.committed = true;
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
            return Files.readAttributes(path, BasicFileAttributes.class).isOther();
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    private static Object windowsFileKey(Path path, LinkOption... options) throws IOException {
        if (File.separatorChar != '\\' || path.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        WindowsFileIds api = WindowsFileIds.INSTANCE;
        String name = path.toAbsolutePath().toString();
        if (name.length() >= 248 && !name.startsWith("\\\\?\\")) {
            name = name.startsWith("\\\\") ? "\\\\?\\UNC\\" + name.substring(2) : "\\\\?\\" + name;
        }
        int flags = options.length == 0 ? 0 : WindowsFileIds.OPEN_REPARSE_POINT;
        Pointer handle = api.CreateFileW(new WString(name), WindowsFileIds.READ_ATTRIBUTES,
                WindowsFileIds.SHARE_READ_WRITE_DELETE, null, WindowsFileIds.OPEN_EXISTING, flags, null);
        long value = Pointer.nativeValue(handle);
        if (value == 0 || value == -1 || (Native.POINTER_SIZE == 4 && value == 0xffffffffL)) {
            throw windowsIdentityError(path, Native.getLastError());
        }
        try {
            // FILE_ID_INFO contains an eight-byte volume serial number and a 128-bit file ID.
            byte[] id = new byte[24];
            if (api.GetFileInformationByHandleEx(handle, WindowsFileIds.FILE_ID_INFO, id, id.length) != 0) {
                ByteBuffer identity = ByteBuffer.wrap(id).asReadOnlyBuffer();
                return identity.getLong(8) == 0 && identity.getLong(16) == 0 ? null : identity;
            }
            int error = Native.getLastError();
            if (error != WindowsFileIds.INVALID_FUNCTION && error != WindowsFileIds.NOT_SUPPORTED
                    && error != WindowsFileIds.INVALID_PARAMETER) {
                throw windowsIdentityError(path, error);
            }
            // Older Windows and filesystems may only provide BY_HANDLE_FILE_INFORMATION.
            byte[] attributes = new byte[52];
            if (api.GetFileInformationByHandle(handle, attributes) == 0) {
                throw windowsIdentityError(path, Native.getLastError());
            }
            ByteBuffer info = ByteBuffer.wrap(attributes).order(ByteOrder.LITTLE_ENDIAN);
            int indexHigh = info.getInt(44);
            int indexLow = info.getInt(48);
            return indexHigh == 0 && indexLow == 0 ? null : List.of(info.getInt(28), indexHigh, indexLow);
        } finally {
            api.CloseHandle(handle);
        }
    }

    private static IOException windowsIdentityError(Path path, int error) {
        if (error == WindowsFileIds.FILE_NOT_FOUND || error == WindowsFileIds.PATH_NOT_FOUND) {
            return new NoSuchFileException(path.toString());
        }
        return new IOException("Cannot identify report destination " + path + " (Windows error " + error + ")");
    }

    private interface WindowsFileIds extends StdCallLibrary {
        WindowsFileIds INSTANCE = Native.load("kernel32", WindowsFileIds.class);
        int INVALID_FUNCTION = 1;
        int FILE_NOT_FOUND = 2;
        int PATH_NOT_FOUND = 3;
        int NOT_SUPPORTED = 50;
        int INVALID_PARAMETER = 87;
        int OPEN_EXISTING = 3;
        int READ_ATTRIBUTES = 0x80;
        int SHARE_READ_WRITE_DELETE = 0x7;
        int OPEN_REPARSE_POINT = 0x00200000;
        int FILE_ID_INFO = 18;

        Pointer CreateFileW(WString name, int access, int share, Pointer security, int creation,
                            int flags, Pointer template);
        int GetFileInformationByHandleEx(Pointer handle, int infoClass, byte[] info, int size);
        int GetFileInformationByHandle(Pointer handle, byte[] info);
        int CloseHandle(Pointer handle);
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

        // Apply the restriction at creation, including when staging has to use the system temp folder.
        private static FileAttribute<?> ownerAccess(FileSystem fs) throws IOException {
            if (fs.supportedFileAttributeViews().contains("posix")) {
                return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            }
            if (!fs.supportedFileAttributeViews().contains("acl")) {
                throw new IOException("Temporary reports require a filesystem with private file permissions");
            }
            AclEntry owner = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(fs.getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name")))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            return new FileAttribute<List<AclEntry>>() {
                @Override public String name() { return "acl:acl"; }
                @Override public List<AclEntry> value() { return List.of(owner); }
            };
        }

        static Temporary forNewOutput(Path parent) throws IOException {
            Path directory = Files.createTempDirectory(parent, ".coverage41c-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return new Temporary(directory.resolve("output"), true);
        }

        static Temporary create(Path near) throws IOException {
            IOException besideFailure = null;
            if (near != null && near.getParent() != null && !isSpecialFile(near)) {
                try {
                    return new Temporary(Files.createTempFile(near.toAbsolutePath().getParent(), ".coverage41c-", ".tmp",
                            ownerAccess(near.getFileSystem())));
                } catch (IOException failure) {
                    if (!Files.isRegularFile(near) || !Files.isWritable(near)) {
                        throw new IOException("Cannot prepare report output: " + near, failure);
                    }
                    // The file can be writable in a directory where creation is denied or on a read-only mount.
                    besideFailure = failure;
                }
            }
            try {
                return new Temporary(Files.createTempFile("coverage41c-", ".tmp", ownerAccess(FileSystems.getDefault())));
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
        private final Path marker;

        private CreationClaim(Path marker) {
            this.marker = marker;
        }

        private static Path markerFor(Path path) throws IOException {
            Path destination = Utils.resolveOutputPath(path.toAbsolutePath());
            Path name = destination.getFileName();
            if (name == null) {
                throw new IOException("Report destination is not a regular file: " + path);
            }
            return destination.resolveSibling(".coverage41c-creating").resolve(name);
        }

        private static IOException inProgress(Path path, Path marker) {
            return new IOException("Report creation is in progress for " + path
                    + ". After an interrupted conversion, check " + marker);
        }

        static void rejectIfHeld(Path path) throws IOException {
            Path marker = markerFor(path);
            if (Files.isDirectory(marker.getParent(), LinkOption.NOFOLLOW_LINKS)
                    && Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw inProgress(path, marker);
            }
        }

        static CreationClaim acquire(Path path) throws IOException {
            Path marker = markerFor(path);
            Path namespace = marker.getParent();
            while (true) {
                try {
                    Files.createDirectory(namespace);
                } catch (FileAlreadyExistsException occupied) {
                    try {
                        if (!Files.readAttributes(namespace, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                                .isDirectory()) {
                            throw new IOException("Report creation marker path is not a directory: " + namespace, occupied);
                        }
                    } catch (NoSuchFileException removed) {
                        continue;
                    }
                }
                try {
                    Files.createDirectory(marker);
                    return new CreationClaim(marker);
                } catch (FileAlreadyExistsException busy) {
                    throw inProgress(path, marker);
                } catch (NoSuchFileException removed) {
                    // The previous creator can remove the empty namespace between the two creates.
                    if (!Files.isDirectory(namespace.getParent())) {
                        throw removed;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            Files.delete(marker);
            try {
                Files.deleteIfExists(marker.getParent());
            } catch (IOException cleanup) {
                // Another creator can hold a claim here. An empty namespace can also be reused.
            }
        }
    }

    private enum Access { CREATED, READ_WRITE, WRITE_ONLY }

    private static final class Destination implements Closeable {
        // On POSIX, closing another channel to the same inode can drop this JVM's OS lock.
        // Reject local contenders before open; unrelated files and output streams remain independent.
        private static final Set<Destination> ACTIVE = new HashSet<>();
        private final Object fileKey;
        private final FileKeys keys;
        private final Path path;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean keepChannel;
        private final List<FileChannel> deferredClose = new ArrayList<>();
        private final Access access;
        private final Temporary privateName;
        private Temporary backup;
        private boolean modified;
        private boolean committed;

        private Destination(Path path, FileChannel channel, FileLock lock, Access access, Temporary privateName,
                            Object fileKey, FileKeys keys) {
            this.path = path;
            this.channel = channel;
            this.lock = lock;
            this.access = access;
            this.privateName = privateName;
            this.fileKey = fileKey;
            this.keys = keys;
        }

        static Destination open(Path path, boolean createOnly, FileKeys keys) throws IOException {
            if (path == null || (!createOnly && isSpecialFile(path))) {
                return null;
            }
            return reserve(path, createOnly, keys).withBackup();
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

        private static synchronized Destination reserve(Path path, boolean createOnly, FileKeys keys) throws IOException {
            Destination destination = !createOnly && Files.exists(path) ? openExisting(path, keys) : create(path, createOnly, keys);
            ACTIVE.add(destination);
            return destination;
        }

        private static synchronized Destination reservePublished(PublishedReport published, Path name) throws IOException {
            rejectActive(name, published.fileKey);
            FileLock acquired = lock(published.channel, name);
            Destination original = new Destination(name, published.channel, acquired, Access.READ_WRITE,
                    null, published.fileKey, SYSTEM_FILE_KEYS);
            original.keepChannel = true;
            ACTIVE.add(original);
            return original;
        }

        private static void rejectActive(Path path, Object fileKey) throws IOException {
            for (Destination active : ACTIVE) {
                if (active.fileKey != null && fileKey != null
                        ? active.fileKey.equals(fileKey) : Files.isSameFile(path, active.path)) {
                    throw new IOException("Another conversion is writing " + path);
                }
            }
        }

        private static Destination openExisting(Path path, FileKeys keys) throws IOException {
            BasicFileAttributes previous = Files.readAttributes(path, BasicFileAttributes.class);
            if (!previous.isRegularFile()) {
                throw new IOException("Report destination is not a regular file: " + path);
            }
            Object previousKey = keys.read(path, previous);
            rejectActive(path, previousKey);
            FileChannel channel;
            Access access = Access.READ_WRITE;
            try {
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            } catch (AccessDeniedException denied) {
                channel = FileChannel.open(path, StandardOpenOption.WRITE);
                access = Access.WRITE_ONLY;
            }
            try {
                // A fallback creator must lock its new file before another publisher can claim it.
                CreationClaim.rejectIfHeld(path);
                FileLock acquired = lock(channel, path);
                // An earlier publisher may have rolled back and unlinked this file after open.
                BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class);
                Object currentKey = keys.read(path, current);
                if (previousKey != null && !previousKey.equals(currentKey)) {
                    throw new IOException("Report destination changed during conversion: " + path);
                }
                return new Destination(path, channel, acquired, access, null, currentKey, keys);
            } catch (IOException | RuntimeException error) {
                channel.close();
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

        // Publish a locked inode with a no-replace link. A concurrent opener can never lock an
        // empty placeholder before its creator. Use the ordinary create-file mode under the umask.
        private static Destination create(Path path, boolean createOnly, FileKeys keys) throws IOException {
            Path parent = path.toAbsolutePath().getParent();
            if (!keys.usePrivateLinks || !path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                // Let the new output inherit the destination directory's ACL.
                return createWithMarker(path, createOnly, keys);
            }
            CreationClaim.rejectIfHeld(path);
            Temporary scratch = Temporary.forNewOutput(parent);
            boolean linked = false;
            boolean alreadyExists = false;
            try {
                FileChannel channel = openNewChannel(scratch.path);
                try {
                    Object fileKey = keys.read(scratch.path, Files.readAttributes(scratch.path, BasicFileAttributes.class));
                    FileLock acquired = lock(channel, scratch.path);
                    try {
                        Files.createLink(path, scratch.path);
                        linked = true;
                        return new Destination(path, channel, acquired, Access.CREATED, scratch, fileKey, keys);
                    } catch (FileAlreadyExistsException collision) {
                        if (createOnly) {
                            throw collision;
                        }
                        alreadyExists = true;
                    } catch (UnsupportedOperationException | IOException unsupported) {
                        // Some providers or users cannot create hard links. Reserve under a creation marker below.
                    }
                } finally {
                    if (!linked) {
                        channel.close();
                    }
                }
            } finally {
                if (!linked) {
                    scratch.close();
                }
            }
            return alreadyExists ? openExisting(path, keys) : createWithMarker(path, createOnly, keys);
        }

        // Fallback for providers without hard links. Hold the claim until CREATE_NEW and tryLock complete.
        private static Destination createWithMarker(Path path, boolean createOnly, FileKeys keys) throws IOException {
            Destination created = null;
            try (CreationClaim claim = CreationClaim.acquire(path)) {
                // Check identity support while the only new file is inside the directory owned by this claim.
                Path probe = Files.createFile(claim.marker.resolve("identity"));
                try {
                    requireNewFileKey(probe, path, keys);
                } finally {
                    Files.delete(probe);
                }

                try {
                    created = createAndLock(path, keys);
                } catch (FileAlreadyExistsException collision) {
                    if (createOnly) {
                        throw collision;
                    }
                    // A hard-link-capable publisher may have installed the destination first.
                }
            } catch (IOException | RuntimeException failure) {
                if (created != null) {
                    try {
                        created.close();
                    } catch (IOException | RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
            return created == null ? openExisting(path, keys) : created;
        }

        private static Destination createAndLock(Path path, FileKeys keys) throws IOException {
            FileChannel channel = openNewChannel(path);
            Object fileKey = null;
            try {
                fileKey = requireNewFileKey(path, path, keys);
                FileLock acquired = lock(channel, path);
                return new Destination(path, channel, acquired, Access.CREATED, null, fileKey, keys);
            } catch (IOException | RuntimeException error) {
                try (FileChannel allocated = channel) {
                    if (stillOwnsPath(path, fileKey, null, keys)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException | RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        private static Object requireNewFileKey(Path file, Path destination, FileKeys keys) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Report destination changed during conversion: " + destination);
            }
            Object key = keys.read(file, attributes, LinkOption.NOFOLLOW_LINKS);
            if (key == null) {
                throw new IOException("Cannot identify new report destination for safe rollback: " + destination);
            }
            return key;
        }

        private static FileChannel openNewChannel(Path path) throws IOException {
            return FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
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

        private static boolean stillOwnsPath(Path path, Object fileKey, Temporary privateName, FileKeys keys)
                throws IOException {
            try {
                BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!current.isRegularFile()) {
                    return false;
                }
                return fileKey != null ? fileKey.equals(keys.read(path, current, LinkOption.NOFOLLOW_LINKS))
                        : privateName != null && Files.isSameFile(path, privateName.path);
            } catch (NoSuchFileException removed) {
                return false;
            }
        }

        @Override
        public void close() throws IOException {
            try (Closeable unregister = this::finish; Temporary name = privateName;
                 Closeable locked = keepChannel ? lock::release : channel) {
                if (!committed && access == Access.CREATED) {
                    if (stillOwnsPath(path, fileKey, privateName, keys)) {
                        Files.deleteIfExists(path);
                    }
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
