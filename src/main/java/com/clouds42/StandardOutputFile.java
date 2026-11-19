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
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Compare a named destination to the stream already opened on stdout by the shell. */
final class StandardOutputFile {
    private StandardOutputFile() { }

    static boolean matches(Path destination) throws IOException {
        if (!Files.exists(destination)) {
            return false;
        }
        if (File.separatorChar == '\\') {
            Path file = windowsFile();
            return file != null && Files.isSameFile(file, destination);
        }
        Path descriptor = Path.of("/proc/self/fd/1");
        if (!Files.exists(descriptor)) {
            descriptor = Path.of("/dev/fd/1");
        }
        if (!Files.exists(descriptor)) {
            return false;
        }
        if (Files.isSameFile(descriptor, destination)) {
            return true;
        }
        if (!System.getProperty("os.name").startsWith("Mac")) {
            return false;
        }
        return matchesDarwinVnode(destination, descriptor);
    }

    // Darwin's /dev/fd is on devfs. Ask the open vnode for its device and 64-bit file ID:
    // a pathname may already have been unlinked or reused while the shell still holds the descriptor.
    private static boolean matchesDarwinVnode(Path destination, Path descriptor) throws IOException {
        // struct attrlist (sys/attr.h): bitmap count, reserved, then five attribute groups.
        byte[] attributes = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
                .putShort((short) 5).putShort((short) 0).putInt(Darwin.ATTR_CMN_DEVID | Darwin.ATTR_CMN_FILEID).array();
        // Darwin packs the requested attributes after a 4-byte length: dev_t, then uint64_t file ID.
        ByteBuffer result = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        if (Native.load("c", Darwin.class).fgetattrlist(1, attributes, result.array(), result.capacity(), 0) != 0) {
            int error = Native.getLastError();
            if ((error == Darwin.EINVAL || error == Darwin.EBADF) && !Files.isRegularFile(descriptor)) {
                // An anonymous pipe or socket has no vnode. Direct descriptor aliases were compared above.
                return false;
            }
            throw new IOException("Cannot identify standard output (errno " + error + ")");
        }
        if (result.getInt(0) != result.capacity()) {
            throw new IOException("Incomplete file identity for standard output");
        }
        Map<String, Object> identity = Files.readAttributes(destination, "unix:dev,ino");
        return result.getInt(4) == ((Number) identity.get("dev")).intValue()
                && result.getLong(8) == ((Number) identity.get("ino")).longValue();
    }

    private interface Darwin extends Library {
        int ATTR_CMN_DEVID = 0x00000002;
        int ATTR_CMN_FILEID = 0x02000000;
        int EBADF = 9;
        int EINVAL = 22;
        int fgetattrlist(int fd, byte[] attributes, byte[] result, long size, int options);
    }

    private interface Windows extends StdCallLibrary {
        int STD_OUTPUT_HANDLE = -11;
        int FILE_TYPE_DISK = 1;
        Pointer GetStdHandle(int handle);
        int GetFileType(Pointer handle);
        int GetFinalPathNameByHandleW(Pointer handle, char[] path, int size, int flags);
    }

    private static Path windowsFile() throws IOException {
        Windows api = Native.load("kernel32", Windows.class);
        Pointer handle = api.GetStdHandle(Windows.STD_OUTPUT_HANDLE);
        if (handle == null || api.GetFileType(handle) != Windows.FILE_TYPE_DISK) {
            return null;
        }
        char[] path = new char[32768]; // Maximum length of a Windows extended path, including its terminator.
        int size = api.GetFinalPathNameByHandleW(handle, path, path.length, 0);
        if (size == 0 || size >= path.length) {
            throw new IOException("Cannot identify standard output (Windows error " + Native.getLastError() + ")");
        }
        String name = new String(path, 0, size);
        if (name.startsWith("\\\\?\\UNC\\")) {
            return Path.of("\\\\" + name.substring(8));
        }
        return Path.of(name.startsWith("\\\\?\\") ? name.substring(4) : name);
    }
}
