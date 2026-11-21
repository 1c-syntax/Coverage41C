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
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;

final class PosixFiles {
    private PosixFiles() { }

    static Pin pinOnLinux(Path path) throws IOException {
        if (!Platform.isLinux()) {
            return null;
        }
        // O_PATH keeps the inode alive without the lock-releasing close of an ordinary POSIX descriptor.
        int descriptor = LibC.INSTANCE.open(nativePath(path), 0x200000 | 0x80000); // O_PATH | O_CLOEXEC
        if (descriptor < 0) {
            throw new FileSystemException(path.toString(), null, "Cannot retain report identity (errno "
                    + Native.getLastError() + ")");
        }
        return new Pin(descriptor, path);
    }

    static final class Pin implements Closeable {
        private final int descriptor;
        private final Path path;

        private Pin(int descriptor, Path path) {
            this.descriptor = descriptor;
            this.path = path;
        }

        Path descriptorPath() {
            return PosixFiles.descriptorPath(descriptor);
        }

        @Override
        public void close() throws IOException {
            if (LibC.INSTANCE.close(descriptor) != 0) {
                throw new FileSystemException(path.toString(), null, "Cannot release report identity (errno "
                        + Native.getLastError() + ")");
            }
        }
    }

    private static Path descriptorPath(int descriptor) {
        String number = Integer.toString(descriptor);
        Path proc = Path.of("/proc/self/fd", number);
        if (Files.exists(proc)) {
            return proc;
        }
        Path dev = Path.of("/dev/fd", number);
        return Files.exists(dev) ? dev : null;
    }

    static void writeCheckedReport(Path path, Object expected, ConversionOutput.Content writer) throws IOException {
        boolean darwin = Platform.isMac();
        if (!darwin && !Platform.isLinux()) {
            try (SeekableByteChannel output = Files.newByteChannel(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                if (!Objects.equals(expected, StandardOutputFile.regularFileKey(path))) {
                    throw new IOException("Coverage output changed before it could be written: " + path);
                }
                if (expected != null) {
                    output.truncate(0);
                }
                writer.copyTo(Channels.newOutputStream(output));
            }
            return;
        }
        // O_WRONLY | O_NOFOLLOW | O_CLOEXEC. The original file is untouched until the open inode is verified.
        // A regular destination replaced by a FIFO must fail without waiting for a reader.
        int flags = noFollowCloseOnExec() | 1 | (expected == null ? 0 : nonBlocking());
        int descriptor = LibC.INSTANCE.open(nativePath(path), flags);
        if (descriptor < 0) {
            throw reportError(path, "open");
        }
        try (OutputStream output = new OpenReport(descriptor, path)) {
            Object actual = darwin ? StandardOutputFile.darwinOpenFileKey(descriptor, path)
                    : linuxOpenFileKey(descriptor, path);
            if (!Objects.equals(expected, actual)) {
                throw new IOException("Coverage output changed before it could be written: " + path);
            }
            if (actual != null) {
                NativeLong start = new NativeLong(0);
                if (LibC.INSTANCE.lseek(descriptor, start, 0).longValue() < 0) { // SEEK_SET
                    throw reportError(path, "seek in");
                }
                if (LibC.INSTANCE.ftruncate(descriptor, start) != 0) {
                    throw reportError(path, "truncate");
                }
            }
            OutputStream buffered = new BufferedOutputStream(output);
            writer.copyTo(buffered);
            buffered.flush();
        }
    }

    private static Object linuxOpenFileKey(int descriptor, Path path) throws IOException {
        Path reference = descriptorPath(descriptor);
        if (reference != null) {
            if (!Files.readAttributes(reference, BasicFileAttributes.class).isRegularFile()) {
                return null;
            }
            Object identity = StandardOutputFile.regularFileKey(reference);
            if (identity != null) {
                return identity;
            }
        }

        // statx has a fixed layout across Linux architectures. AT_EMPTY_PATH reads the open descriptor.
        byte[] stat = new byte[256];
        int typeAndInode = 0x101;
        try {
            if (LibC.INSTANCE.statx(descriptor, new byte[1], 0x1000, typeAndInode, stat) != 0) {
                throw reportError(path, "identify");
            }
        } catch (UnsatisfiedLinkError unavailable) {
            throw new IOException("Cannot identify the open report " + path, unavailable);
        }
        ByteBuffer info = ByteBuffer.wrap(stat).order(ByteOrder.nativeOrder());
        if ((info.getInt(0) & typeAndInode) != typeAndInode) {
            throw new IOException("Incomplete file identity for " + path);
        }
        if ((info.getShort(28) & 0170000) != 0100000) {
            return null;
        }
        long major = Integer.toUnsignedLong(info.getInt(136));
        long minor = Integer.toUnsignedLong(info.getInt(140));
        long device = (major & 0xfff) << 8 | (major & ~0xfffL) << 32
                | (minor & 0xff) | (minor & ~0xffL) << 12;
        return StandardOutputFile.fileKey(device, info.getLong(32));
    }

    private static IOException reportError(Path path, String operation) {
        int error = Native.getLastError();
        if (error == 2) {
            return new NoSuchFileException(path.toString());
        }
        if (error == 13) {
            return new AccessDeniedException(path.toString());
        }
        return new FileSystemException(path.toString(), null, "Cannot " + operation + " report (errno " + error + ")");
    }

    private static final class OpenReport extends OutputStream {
        private final int descriptor;
        private final Path path;

        private OpenReport(int descriptor, Path path) {
            this.descriptor = descriptor;
            this.path = path;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            int end = offset + length;
            while (offset < end) {
                byte[] buffer = offset == 0 ? bytes : Arrays.copyOfRange(bytes, offset, end);
                long written = LibC.INSTANCE.write(descriptor, buffer, new NativeLong((long) end - offset)).longValue();
                if (written < 0 && Native.getLastError() == 4) { // EINTR
                    continue;
                }
                if (written <= 0) {
                    throw reportError(path, "write");
                }
                offset += (int) written;
            }
        }

        @Override
        public void close() throws IOException {
            if (LibC.INSTANCE.close(descriptor) != 0) {
                throw reportError(path, "close");
            }
        }
    }

    private static int noFollowCloseOnExec() {
        if (Platform.isMac()) {
            return 0x100 | 0x01000000;
        }
        int noFollow = Platform.isARM() || Platform.isPPC() ? 0x8000 : 0x20000;
        int closeOnExec = Platform.isSPARC() ? 0x400000 : 0x80000;
        return noFollow | closeOnExec;
    }

    private static int nonBlocking() {
        if (Platform.isMac()) {
            return 4;
        }
        return Platform.isSPARC() ? 0x4000 : 0x800;
    }

    static boolean supportsNamespaceLeases(Path path) {
        return path.getFileSystem() == FileSystems.getDefault() && (Platform.isLinux() || Platform.isMac());
    }

    // flock belongs to this open file description. Other opens and closes in the JVM cannot release it.
    static Pin tryNamespaceStampLock(Path path, boolean exclusive) throws IOException {
        return tryNamespaceStampLock(path, exclusive, exclusive);
    }

    static Pin tryNamespaceCleanupLock(Path path) throws IOException {
        try {
            return tryNamespaceStampLock(path, true);
        } catch (AccessDeniedException readOnlyStamp) {
            // On local Unix filesystems, a peer can lock the read-only stamp before removing
            // a directory it is already allowed to delete. Network filesystems may reject this.
            return tryNamespaceStampLock(path, true, false);
        }
    }

    private static Pin tryNamespaceStampLock(Path path, boolean exclusive, boolean writable) throws IOException {
        boolean mac = Platform.isMac();
        byte[] name = nativePath(path);
        // toUri appends a slash to directories. O_NOFOLLOW must apply to the final named entry.
        if (name.length > 2 && name[name.length - 2] == '/') {
            name[name.length - 2] = 0;
        }
        // A writable regular descriptor also supports NFS and SMB implementations of exclusive flock.
        int fd = LibC.INSTANCE.open(name, (writable ? 2 : 0) | nonBlocking() | noFollowCloseOnExec());
        if (fd < 0) {
            int error = Native.getLastError();
            if (error == 2) {
                throw new NoSuchFileException(path.toString());
            }
            if (error == 13) {
                throw new AccessDeniedException(path.toString());
            }
            if (error == 20 || error == (mac ? 62 : 40)) {
                throw new NotDirectoryException(path.toString());
            }
            throw new FileSystemException(path.toString(), null, "Cannot open the report creation stamp (errno " + error + ")");
        }
        Pin pin = new Pin(fd, path);
        if (LibC.INSTANCE.flock(fd, (exclusive ? 2 : 1) | 4) == 0) { // LOCK_EX / LOCK_SH, LOCK_NB
            return pin;
        }
        int error = Native.getLastError();
        pin.close();
        if (error == (mac ? 45 : 95) || error == (mac ? 78 : 38) || error == 9) {
            throw new UnsupportedOperationException("Report creation stamp locks are unavailable for " + path);
        }
        if (error == (mac ? 35 : 11)) { // EWOULDBLOCK
            return null;
        }
        throw new FileSystemException(path.toString(), null, "Cannot lock the report creation stamp (errno " + error + ")");
    }

    static boolean namespaceStampStillNamed(Pin pin, Path path) throws IOException {
        try {
            var attributes = Files.readAttributes(path, "unix:dev,ino,isRegularFile", LinkOption.NOFOLLOW_LINKS);
            if (!Boolean.TRUE.equals(attributes.get("isRegularFile"))) {
                return false;
            }
            Object key = StandardOutputFile.fileKey(((Number) attributes.get("dev")).longValue(),
                    ((Number) attributes.get("ino")).longValue());
            Object openKey = Platform.isMac() ? StandardOutputFile.darwinOpenFileKey(pin.descriptor, path)
                    : linuxOpenFileKey(pin.descriptor, path);
            return key.equals(openKey);
        } catch (NoSuchFileException removed) {
            return false;
        }
    }

    static void removeNamespaceDirectory(Path namespace) throws IOException {
        // rmdir cannot unlink an externally installed regular report at the same name.
        if (LibC.INSTANCE.rmdir(nativePath(namespace)) == 0) {
            return;
        }
        int error = Native.getLastError();
        if (error == 2) {
            return;
        }
        if (error == 17 || error == (Platform.isMac() ? 66 : 39)) {
            throw new DirectoryNotEmptyException(namespace.toString());
        }
        throw new FileSystemException(namespace.toString(), null, "Cannot remove report creation directory (errno " + error + ")");
    }

    static void moveNewFile(Path source, Path target) throws IOException {
        byte[] from = nativePath(source);
        byte[] to = nativePath(target);
        int result;
        try {
            if (Platform.isLinux()) {
                result = LibC.INSTANCE.renameat2(-100, from, -100, to, 1); // AT_FDCWD, RENAME_NOREPLACE
            } else if (Platform.isMac()) {
                result = LibC.INSTANCE.renamex_np(from, to, 4); // RENAME_EXCL
            } else {
                throw new IOException("Exclusive report publication is unavailable for " + target);
            }
        } catch (UnsatisfiedLinkError unavailable) {
            throw new IOException("Exclusive report publication is unavailable for " + target, unavailable);
        }
        if (result != 0) {
            int error = Native.getLastError();
            if (error == 17) { // EEXIST
                throw new FileAlreadyExistsException(target.toString());
            }
            throw new FileSystemException(source.toString(), target.toString(), "Cannot publish report (errno " + error + ")");
        }
    }

    // The Unix provider percent-encodes the native path bytes, including on filesystems
    // that preserve a different Unicode normalization from the Java display name.
    private static byte[] nativePath(Path path) {
        String raw = path.toUri().getRawPath();
        byte[] result = new byte[raw.length() + 1];
        int length = 0;
        for (int offset = 0; offset < raw.length(); offset++) {
            char current = raw.charAt(offset);
            if (current == '%') {
                int high = Character.digit(raw.charAt(++offset), 16);
                int low = Character.digit(raw.charAt(++offset), 16);
                result[length++] = (byte) ((high << 4) | low);
            } else {
                result[length++] = (byte) current;
            }
        }
        return Arrays.copyOf(result, length + 1);
    }

    private interface LibC extends Library {
        LibC INSTANCE = Native.load(Platform.C_LIBRARY_NAME, LibC.class);

        int open(byte[] name, int flags);

        int close(int descriptor);

        int flock(int descriptor, int operation);

        int rmdir(byte[] name);

        int statx(int descriptor, byte[] name, int flags, int mask, byte[] result);

        NativeLong lseek(int descriptor, NativeLong offset, int origin);

        int ftruncate(int descriptor, NativeLong length);

        NativeLong write(int descriptor, byte[] data, NativeLong count);

        int renameat2(int sourceDirectory, byte[] from, int targetDirectory, byte[] to, int flags);

        int renamex_np(byte[] from, byte[] to, int flags);
    }
}
