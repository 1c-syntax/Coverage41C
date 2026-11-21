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

import com.sun.jna.Library;
import com.sun.jna.Native;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Compare a named destination to the stream already opened on stdout by the shell. */
final class StandardOutputFile {
    private StandardOutputFile() { }

    // Borrow fd 1 for the lifetime of the CLI. An embedded System.out can target a different stream.
    static final OutputStream PROCESS_OUTPUT = new FileOutputStream(FileDescriptor.out);

    static boolean isProcessOutput(OutputStream output) throws IOException {
        return output instanceof FileOutputStream && ((FileOutputStream) output).getFD() == FileDescriptor.out;
    }

    static boolean isAlias(File output) throws IOException {
        if (output == null || File.separatorChar == '\\') {
            return false;
        }
        Set<Path> visited = new HashSet<>();
        Path path = output.toPath().toAbsolutePath();
        while (visited.add(path)) {
            // Stop before following a descriptor link to its display name (pipe:[...], or "... (deleted)").
            if (isDescriptorPath(path)) {
                return true;
            }
            if (path.getFileName() == null) {
                return false;
            }
            String name = path.getFileName().toString();
            if (name.equals("1") || name.equals("stdout")) {
                try {
                    if (isDescriptorPath(path.getParent().toRealPath().resolve(name))) {
                        return true;
                    }
                } catch (NoSuchFileException missingDirectory) {
                    return false;
                }
            }
            if (!Files.isSymbolicLink(path)) {
                return false;
            }
            path = path.resolveSibling(Files.readSymbolicLink(path));
        }
        throw new FileSystemLoopException(output.toString());
    }

    private static boolean isDescriptorPath(Path path) {
        String name = path.toString();
        return name.equals("/dev/stdout") || name.equals("/dev/fd/1") || name.equals("/proc/self/fd/1")
                || name.equals("/proc/thread-self/fd/1") || name.equals("/proc/" + ProcessHandle.current().pid() + "/fd/1");
    }

    @FunctionalInterface
    interface FileIdentity {
        boolean matches(Path destination) throws IOException;
    }

    static final class NamedFile {
        private final Object key;
        private final Object basicKey;

        private NamedFile(Object key, Object basicKey) {
            this.key = key;
            this.basicKey = basicKey;
        }

        Object key() {
            return key;
        }

        boolean matches(Path path, BasicFileAttributes attributes) throws IOException {
            return mayNameRegularFile(attributes) && (basicKey != null && attributes.fileKey() != null
                    ? basicKey.equals(attributes.fileKey()) : key.equals(regularFileKey(path)));
        }
    }

    static boolean mayNameRegularFile(BasicFileAttributes attributes) {
        return attributes.isRegularFile() || File.separatorChar == '\\' && attributes.isOther();
    }

    static NamedFile inspectNamedFile(Path path) throws IOException {
        if (isDarwin() && Path.of("/dev/fd").equals(path.getParent())) {
            try {
                Object key = darwinOpenFileKey(Integer.parseInt(path.getFileName().toString()), path);
                return key == null ? null : new NamedFile(key, null);
            } catch (NumberFormatException notADescriptor) {
                // Continue with the ordinary path lookup.
            }
        }
        if (File.separatorChar != '\\' && path.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            try {
                Map<String, Object> attributes = Files.readAttributes(path, "unix:dev,ino,fileKey,isRegularFile");
                return Boolean.TRUE.equals(attributes.get("isRegularFile"))
                        ? new NamedFile(fileKey(((Number) attributes.get("dev")).longValue(),
                                ((Number) attributes.get("ino")).longValue()), attributes.get("fileKey")) : null;
            } catch (NoSuchFileException absent) {
                return null;
            }
        }
        Object key = regularFileKey(path);
        return key == null ? null : new NamedFile(key, null);
    }

    static Object regularFileKey(Path path) throws IOException {
        try {
            if (File.separatorChar == '\\') {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (attributes.isRegularFile()) {
                    return WindowsFiles.key(path, false);
                }
                // The JDK also classifies ordinary files with a non-name-surrogate tag as "other".
                return attributes.isOther() ? WindowsFiles.reparseReportKey(path) : null;
            }
            if (!Files.isRegularFile(path)) {
                return null;
            }
            if (path.getFileSystem().supportedFileAttributeViews().contains("unix")) {
                Map<String, Object> attributes = Files.readAttributes(path, "unix:dev,ino");
                return fileKey(((Number) attributes.get("dev")).longValue(),
                        ((Number) attributes.get("ino")).longValue());
            }
            return Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        } catch (NoSuchFileException movedOrRemoved) {
            return null;
        }
    }

    static Object regularFileKey() throws IOException {
        if (File.separatorChar == '\\') {
            return WindowsFiles.stdoutKey();
        }
        Path descriptor = unixDescriptor();
        return isDarwin() ? darwinKey(descriptor) : regularFileKey(descriptor);
    }

    static Object fileKey(long device, long inode) {
        return Map.of("dev", device, "ino", inode);
    }

    static FileIdentity regularFile() throws IOException {
        if (File.separatorChar == '\\') {
            return WindowsFiles.stdout();
        }
        Path descriptor = unixDescriptor();
        if (!Files.isRegularFile(descriptor)) {
            return null;
        }
        return isDarwin() ? darwinIdentity(descriptor) : path -> Files.isSameFile(descriptor, path);
    }

    static Path unixDescriptor() {
        Path proc = Path.of("/proc/self/fd/1");
        return Files.exists(proc) ? proc : Path.of("/dev/fd/1");
    }

    static boolean matches(Path destination) throws IOException {
        if (!Files.exists(destination)) {
            return false;
        }
        if (File.separatorChar == '\\') {
            FileIdentity identity = WindowsFiles.stdout();
            return identity != null && identity.matches(destination);
        }
        Path descriptor = unixDescriptor();
        if (!Files.exists(descriptor)) {
            return false;
        }
        if (Files.isSameFile(descriptor, destination)) {
            return true;
        }
        FileIdentity identity = isDarwin() ? darwinIdentity(descriptor) : null;
        return identity != null && identity.matches(destination);
    }

    private static boolean isDarwin() {
        return System.getProperty("os.name").startsWith("Mac");
    }

    // Darwin's /dev/fd is on devfs. Ask the open vnode for its device and 64-bit file ID:
    // a pathname may already have been unlinked or reused while the shell still holds the descriptor.
    private static FileIdentity darwinIdentity(Path descriptor) throws IOException {
        Object identity = darwinKey(descriptor);
        return identity == null ? null : destination -> identity.equals(regularFileKey(destination));
    }

    private static Object darwinKey(Path descriptor) throws IOException {
        // APFS file IDs are stable across renames and timestamp changes.
        byte[] attributes = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
                .putShort((short) 5).putShort((short) 0).putInt(Darwin.ATTR_CMN_DEVID | Darwin.ATTR_CMN_FILEID).array();
        ByteBuffer result = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        if (Native.load("c", Darwin.class).fgetattrlist(1, attributes, result.array(), result.capacity(), 0) != 0) {
            int error = Native.getLastError();
            if ((error == Darwin.EINVAL || error == Darwin.EBADF) && !Files.isRegularFile(descriptor)) {
                // An anonymous pipe or socket has no vnode. Direct descriptor aliases were compared above.
                return null;
            }
            throw new IOException("Cannot identify standard output (errno " + error + ")");
        }
        if (result.getInt(0) != result.capacity()) {
            throw new IOException("Incomplete file identity for standard output");
        }
        return fileKey(result.getInt(4), result.getLong(8));
    }

    static Object darwinOpenFileKey(int descriptor, Path path) throws IOException {
        byte[] attributes = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
                .putShort((short) 5).putShort((short) 0)
                .putInt(Darwin.ATTR_CMN_DEVID | Darwin.ATTR_CMN_OBJTYPE | Darwin.ATTR_CMN_FILEID).array();
        ByteBuffer result = ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());
        if (Native.load("c", Darwin.class).fgetattrlist(descriptor, attributes, result.array(), result.capacity(), 0) != 0) {
            int error = Native.getLastError();
            if (error == Darwin.EINVAL && !Files.readAttributes(Path.of("/dev/fd", Integer.toString(descriptor)),
                    BasicFileAttributes.class).isRegularFile()) {
                return null;
            }
            throw new IOException("Cannot identify the open report " + path + " (errno " + error + ")");
        }
        if (result.getInt(0) != result.capacity()) {
            throw new IOException("Incomplete file identity for " + path);
        }
        return result.getInt(8) == 1 ? fileKey(result.getInt(4), result.getLong(12)) : null; // VREG
    }

    private interface Darwin extends Library {
        int ATTR_CMN_DEVID = 0x00000002;
        int ATTR_CMN_OBJTYPE = 0x00000008;
        int ATTR_CMN_FILEID = 0x02000000;
        int EBADF = 9;
        int EINVAL = 22;
        int fgetattrlist(int fd, byte[] attributes, byte[] result, long size, int options);
    }
}
