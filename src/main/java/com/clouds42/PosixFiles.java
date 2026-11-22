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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class PosixFiles {
    private PosixFiles() { }

    // Closing a rejected writable descriptor can release the host JVM's POSIX locks. Keep at most
    // one per inode, and bound the total so long-lived locks cannot consume all open descriptors.
    private static final int MAX_REJECTED_REPORTS = 32;
    private static final Map<Object, OpenReport> REJECTED_REPORTS = new HashMap<>();

    static Pin pinOnLinux(Path path) throws IOException {
        if (!Platform.isLinux()) {
            return null;
        }
        return pinOnLinux(path, 0x80000); // O_CLOEXEC
    }

    private static Pin pinOnLinux(Path path, int flags) throws IOException {
        // O_PATH keeps the inode alive without the lock-releasing close of an ordinary POSIX descriptor.
        int descriptor = LibC.INSTANCE.open(nativePath(path), 0x200000 | flags); // O_PATH
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
        boolean lock = !darwin && expected != null;
        try (Pin special = !darwin && !lock ? pinOnLinux(path, noFollowCloseOnExec()) : null;
             OpenReport output = lock ? ConversionOutput.openLinuxMeasurementOutput(path, expected)
                     : special != null ? reopenPinnedReportOnLinux(path, special, null)
                     : openReport(path, path, noFollowCloseOnExec() | 1 | (expected != null ? nonBlocking() : 0))) {
            int descriptor = output.descriptor;
            if (darwin && !Objects.equals(expected, StandardOutputFile.darwinOpenFileKey(descriptor, path))) {
                throw new IOException("Coverage output changed before it could be written: " + path);
            }
            if (expected != null) {
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

    static synchronized OpenReport openLockedReportOnLinux(Path path, Object expected) throws IOException {
        OpenReport rejected = REJECTED_REPORTS.get(expected);
        if (rejected != null) {
            rejected.lockOnLinux(path);
            REJECTED_REPORTS.remove(expected);
            rejected.close();
        }
        REJECTED_REPORTS.values().removeIf(OpenReport::closeIfUnlockedOnLinux);
        if (REJECTED_REPORTS.size() >= MAX_REJECTED_REPORTS) {
            throw new IOException("Too many coverage reports have outstanding file locks; retry writing " + path);
        }

        OpenReport output;
        try (Pin pin = pinOnLinux(path, noFollowCloseOnExec())) {
            output = reopenPinnedReportOnLinux(path, pin, expected);
            REJECTED_REPORTS.put(expected, output);
        }
        output.lockOnLinux(path);
        REJECTED_REPORTS.remove(expected);
        return output;
    }

    private static OpenReport reopenPinnedReportOnLinux(Path path, Pin pin, Object expected) throws IOException {
        Path reference = pin.descriptorPath();
        if (reference == null) {
            throw new IOException("Cannot reopen the retained report inode for " + path + ": /proc/self/fd or /dev/fd is required");
        }
        var stat = Files.readAttributes(reference, "unix:dev,ino,isRegularFile,isSymbolicLink");
        Object actual = Boolean.TRUE.equals(stat.get("isRegularFile"))
                ? StandardOutputFile.fileKey(((Number) stat.get("dev")).longValue(), ((Number) stat.get("ino")).longValue())
                : null;
        if (!Objects.equals(expected, actual) || Boolean.TRUE.equals(stat.get("isSymbolicLink"))) {
            throw new IOException("Coverage output changed before it could be written: " + path);
        }
        // Open the verified inode through its descriptor. A replacement of the public name cannot redirect this open.
        return openReport(path, reference, 1 | linuxCloseOnExec() | (expected != null ? nonBlocking() : 0));
    }

    private static OpenReport openReport(Path path, Path source, int flags) throws IOException {
        int descriptor = LibC.INSTANCE.open(nativePath(source), flags);
        if (descriptor < 0) {
            throw reportError(path, "open");
        }
        return new OpenReport(descriptor, path);
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
        if ((info.getShort(28) & 0_170000) != 0_100000) {
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

    static final class OpenReport extends OutputStream {
        private final int descriptor;
        private final Path path;

        private OpenReport(int descriptor, Path path) {
            this.descriptor = descriptor;
            this.path = path;
        }

        private void lockOnLinux(Path path) throws IOException {
            // A whole-file open-description write lock interoperates with FileChannel's POSIX
            // record lock and survives closes of unrelated descriptors in this process.
            // Only the first field of struct flock is nonzero (l_type = F_WRLCK).
            byte[] lock = new byte[64];
            short writeLock = (short) (Platform.isSPARC() || Platform.ARCH.equals("alpha") ? 2 : 1);
            ByteBuffer.wrap(lock).order(ByteOrder.nativeOrder()).putShort(writeLock);
            if (LibC.INSTANCE.fcntl(descriptor, 37, lock) != 0) { // F_OFD_SETLK
                int error = Native.getLastError();
                if (error == 11 || error == 13) { // EAGAIN / EACCES
                    throw new IOException("Another conversion is writing " + path);
                }
                if (error == 22 || error == 38 || error == 95) { // EINVAL / ENOSYS / EOPNOTSUPP
                    throw new FileSystemException(path.toString(), null,
                            "Report locking is unavailable (errno " + error
                                    + "). Use a Linux kernel and filesystem that support open-file-description locks.");
                }
                throw reportError(path, "lock");
            }
        }

        private boolean closeIfUnlockedOnLinux() {
            try {
                lockOnLinux(path);
            } catch (IOException locked) {
                return false;
            }
            // Linux removes process locks during close, before releasing this descriptor's OFD lock.
            // The descriptor number is released even if close reports a writeback error.
            LibC.INSTANCE.close(descriptor);
            return true;
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
        return noFollow | linuxCloseOnExec();
    }

    private static int linuxCloseOnExec() {
        return Platform.isSPARC() ? 0x400000 : 0x80000;
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

        int fcntl(int descriptor, int command, byte[] lock);

        int rmdir(byte[] name);

        int statx(int descriptor, byte[] name, int flags, int mask, byte[] result);

        NativeLong lseek(int descriptor, NativeLong offset, int origin);

        int ftruncate(int descriptor, NativeLong length);

        NativeLong write(int descriptor, byte[] data, NativeLong count);

        int renameat2(int sourceDirectory, byte[] from, int targetDirectory, byte[] to, int flags);

        int renamex_np(byte[] from, byte[] to, int flags);
    }
}
