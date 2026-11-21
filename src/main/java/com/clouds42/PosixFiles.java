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
import com.sun.jna.Platform;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.Arrays;

final class PosixFiles {
    private PosixFiles() { }

    static Closeable pinOnLinux(Path path) throws IOException {
        if (!Platform.isLinux()) {
            return null;
        }
        // O_PATH keeps the inode alive without the lock-releasing close of an ordinary POSIX descriptor.
        int descriptor = LibC.INSTANCE.open(nativePath(path), 0x200000 | 0x80000); // O_PATH | O_CLOEXEC
        if (descriptor < 0) {
            throw new FileSystemException(path.toString(), null, "Cannot retain report identity (errno "
                    + Native.getLastError() + ")");
        }
        return () -> {
            if (LibC.INSTANCE.close(descriptor) != 0) {
                throw new FileSystemException(path.toString(), null, "Cannot release report identity (errno "
                        + Native.getLastError() + ")");
            }
        };
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

        int renameat2(int sourceDirectory, byte[] from, int targetDirectory, byte[] to, int flags);

        int renamex_np(byte[] from, byte[] to, int flags);
    }
}
