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

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Serialize both reports before publishing either. Keep the original inodes, links and access rights. */
final class ConversionOutput {
    private ConversionOutput() { }

    @FunctionalInterface
    interface Writer {
        void write(Path file) throws IOException;
    }

    static void writeFile(Path destination, Writer writer) throws IOException {
        write(destination, null, writer, null, null);
    }

    static void write(Path coverage, Path report, Writer writeCoverage, Writer writeReport, OutputStream stdout)
            throws IOException {
        try (Temporary coverageStage = Temporary.create(coverage);
             Temporary reportStage = report == null ? null : Temporary.create(report)) {
            writeCoverage.write(coverageStage.path);
            if (reportStage != null) {
                writeReport.write(reportStage.path);
            }

            publish(coverage, report, output -> Files.copy(coverageStage.path, output),
                    reportStage == null ? null : output -> Files.copy(reportStage.path, output), stdout);
        }
    }

    @FunctionalInterface
    interface Content {
        void copyTo(OutputStream output) throws IOException;
    }

    static void publish(Path coverage, Path report, Content coverageContent, Content reportContent,
                        OutputStream stdout) throws IOException {
        try (Destination coverageFile = Destination.open(coverage);
             Destination reportFile = Destination.open(report)) {
            if (report != null) {
                writeResult(report, reportFile, reportContent, null);
            }
            writeResult(coverage, coverageFile, coverageContent, stdout);
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
            try {
                // Use ordinary output permissions under the umask, behind an owner-only directory.
                return new Temporary(Files.createFile(directory.resolve("output")), true);
            } catch (IOException | RuntimeException failure) {
                Files.delete(directory);
                throw failure;
            }
        }

        static Temporary create(Path near) throws IOException {
            if (near != null && near.getParent() != null && !isSpecialFile(near)) {
                try {
                    return new Temporary(Files.createTempFile(near.toAbsolutePath().getParent(), ".coverage41c-", ".tmp",
                            ownerAccess(near.getFileSystem())));
                } catch (AccessDeniedException denied) {
                    // An existing output can be writable even if its directory only permits traversal.
                    if (!Files.isWritable(near)) {
                        throw new IOException("Cannot prepare report output: " + near, denied);
                    }
                } catch (IOException failure) {
                    throw new IOException("Cannot prepare report output: " + near, failure);
                }
            }
            return new Temporary(Files.createTempFile("coverage41c-", ".tmp", ownerAccess(FileSystems.getDefault())));
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
            if (inPrivateDirectory) {
                Files.delete(path.getParent());
            }
        }
    }

    private static final class Destination implements Closeable {
        // On POSIX, closing another channel to the same inode can drop this JVM's OS lock.
        // Reject local contenders before open; unrelated files and output streams remain independent.
        private static final Set<Destination> ACTIVE = new HashSet<>();
        private final Object fileKey;
        private final Path path;
        private final FileChannel channel;
        private final boolean created;
        private final Temporary privateName;
        private Temporary backup;
        private boolean modified;
        private boolean committed;

        private Destination(Path path, FileChannel channel, boolean created, Temporary privateName, Object fileKey) {
            this.path = path;
            this.channel = channel;
            this.created = created;
            this.privateName = privateName;
            this.fileKey = fileKey;
        }

        static Destination open(Path path) throws IOException {
            if (path == null || isSpecialFile(path)) {
                return null;
            }
            Destination destination = reserve(path);
            try {
                if (!destination.created) {
                    destination.backup = Temporary.create(path);
                    try (OutputStream output = Files.newOutputStream(destination.backup.path)) {
                        Channels.newInputStream(destination.channel).transferTo(output);
                    }
                }
                return destination;
            } catch (IOException | RuntimeException error) {
                try {
                    destination.close();
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        private static synchronized Destination reserve(Path path) throws IOException {
            Destination destination = Files.exists(path) ? openExisting(path) : create(path);
            ACTIVE.add(destination);
            return destination;
        }

        private static Destination openExisting(Path path) throws IOException {
            BasicFileAttributes previous = Files.readAttributes(path, BasicFileAttributes.class);
            if (!previous.isRegularFile()) {
                throw new IOException("Report destination is not a regular file: " + path);
            }
            for (Destination active : ACTIVE) {
                if (active.fileKey != null && previous.fileKey() != null
                        ? active.fileKey.equals(previous.fileKey()) : Files.isSameFile(path, active.path)) {
                    throw new IOException("Another conversion is writing " + path);
                }
            }
            FileChannel channel;
            try {
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            } catch (AccessDeniedException denied) {
                throw new IOException("Cannot open existing report for backup and update: " + path, denied);
            }
            try {
                // A fallback creator must lock its new file before another publisher can claim it.
                if (Files.isDirectory(creationMarker(path), LinkOption.NOFOLLOW_LINKS)) {
                    throw creationInProgress(path);
                }
                lock(channel, path);
                // An earlier publisher may have rolled back and unlinked this file after open.
                BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class);
                if (previous.fileKey() != null && !previous.fileKey().equals(current.fileKey())) {
                    throw new IOException("Report destination changed during conversion: " + path);
                }
                return new Destination(path, channel, false, null, current.fileKey());
            } catch (IOException | RuntimeException error) {
                channel.close();
                throw error;
            }
        }

        // Publish a locked inode with a no-replace link. A concurrent opener can never lock an
        // empty placeholder before its creator. Use the ordinary create-file mode under the umask.
        private static Destination create(Path path) throws IOException {
            Path parent = path.toAbsolutePath().getParent();
            if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                // Let the new output inherit the destination directory's ACL.
                return createWithMarker(path);
            }
            Temporary scratch = Temporary.forNewOutput(parent);
            boolean linked = false;
            boolean alreadyExists = false;
            try {
                FileChannel channel = FileChannel.open(scratch.path, StandardOpenOption.WRITE);
                try {
                    Object fileKey = Files.readAttributes(scratch.path, BasicFileAttributes.class).fileKey();
                    lock(channel, scratch.path);
                    try {
                        Files.createLink(path, scratch.path);
                        linked = true;
                        return new Destination(path, channel, true, scratch, fileKey);
                    } catch (FileAlreadyExistsException collision) {
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
            return alreadyExists ? openExisting(path) : createWithMarker(path);
        }

        private static Path creationMarker(Path path) {
            return path.toAbsolutePath().resolveSibling(".coverage41c-creating.lock");
        }

        private static IOException creationInProgress(Path path) {
            return new IOException("Report creation is in progress in " + path.toAbsolutePath().getParent()
                    + ". After an interrupted conversion, check " + creationMarker(path));
        }

        // Fallback for providers without hard links: existing openers observe this marker before locking.
        // Hold it only until CREATE_NEW and tryLock complete. Never remove a competing creator's marker.
        private static Destination createWithMarker(Path path) throws IOException {
            Path marker = creationMarker(path);
            try {
                Files.createDirectory(marker);
            } catch (FileAlreadyExistsException busy) {
                throw creationInProgress(path);
            }
            try {
                FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    lock(channel, path);
                    return new Destination(path, channel, true, null,
                            Files.readAttributes(path, BasicFileAttributes.class).fileKey());
                } catch (IOException | RuntimeException error) {
                    channel.close();
                    Files.deleteIfExists(path);
                    throw error;
                }
            } catch (FileAlreadyExistsException collision) {
                // A hard-link-capable publisher may have installed the destination first.
            } finally {
                Files.delete(marker);
            }
            return openExisting(path);
        }

        private static void lock(FileChannel channel, Path path) throws IOException {
            try {
                if (channel.tryLock() != null) {
                    return;
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

        @Override
        public void close() throws IOException {
            try (Temporary name = privateName; FileChannel locked = channel) {
                if (!committed && created) {
                    Files.deleteIfExists(path);
                } else if (!committed && modified) {
                    try {
                        replace(output -> Files.copy(backup.path, output));
                    } catch (IOException failure) {
                        throw new IOException("Could not restore " + path + "; original saved at " + backup.path, failure);
                    }
                }
                if (backup != null) {
                    backup.close();
                }
            } finally {
                synchronized (Destination.class) {
                    ACTIVE.remove(this);
                }
            }
        }
    }
}
