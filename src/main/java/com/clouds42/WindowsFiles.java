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
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class WindowsFiles {
    private static final int OPEN_DIRECTORY = 0x02000000;
    private static final int OPEN_REPARSE_POINT = 0x00200000;

    private WindowsFiles() { }

    static Object key(Path path, boolean noFollow) throws IOException {
        int flags = OPEN_DIRECTORY | (noFollow ? OPEN_REPARSE_POINT : 0);
        Pointer handle = open(path, flags);
        try {
            return identity(handle, path.toString());
        } finally {
            Kernel.INSTANCE.CloseHandle(handle);
        }
    }

    static StandardOutputFile.FileIdentity stdout() throws IOException {
        Object key = stdoutKey();
        return key == null ? null : path -> key.equals(key(path, false));
    }

    static Object stdoutKey() throws IOException {
        Pointer handle = Kernel.INSTANCE.GetStdHandle(-11);
        if (invalid(handle) || Kernel.INSTANCE.GetFileType(handle) != 1) {
            return null;
        }
        Object key = identity(handle, "standard output");
        if (key == null) {
            throw new IOException("Cannot identify standard output");
        }
        return key;
    }

    static boolean caseSensitiveDirectory(Path path) throws IOException {
        Pointer handle = open(path, OPEN_DIRECTORY);
        try {
            byte[] flags = new byte[4];
            // FileCaseSensitiveInfo on Windows 10 and newer. Older systems use ordinary name matching.
            if (Kernel.INSTANCE.GetFileInformationByHandleEx(handle, 23, flags, flags.length) != 0) {
                return (flags[0] & 1) != 0;
            }
            int error = Native.getLastError();
            if (unsupported(error)) {
                return false;
            }
            throw failure(path.toString(), error);
        } finally {
            Kernel.INSTANCE.CloseHandle(handle);
        }
    }

    static void createPrivateFile(Path path) throws IOException {
        PointerByReference descriptor = new PointerByReference();
        // P protects the DACL at creation. OW grants access to the file's owner.
        if (Security.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                new WString("D:P(A;;FA;;;OW)"), 1, descriptor, null) == 0) {
            throw failure(path.toString(), Native.getLastError());
        }
        Pointer security = descriptor.getValue();
        try {
            SecurityAttributes attributes = new SecurityAttributes();
            attributes.length = attributes.size();
            attributes.descriptor = security;
            attributes.write();
            Pointer file = Kernel.INSTANCE.CreateFileW(name(path), 0xc0000000, 7,
                    attributes.getPointer(), 1, 0x80, null); // CREATE_NEW, FILE_ATTRIBUTE_NORMAL
            if (invalid(file)) {
                int error = Native.getLastError();
                if (error == 80 || error == 183) {
                    throw new FileAlreadyExistsException(path.toString());
                }
                throw failure(path.toString(), error);
            }
            Kernel.INSTANCE.CloseHandle(file);
        } finally {
            Kernel.INSTANCE.LocalFree(security);
        }
    }

    static void writeCheckedReport(Path path, Object expected, ConversionOutput.Content writer) throws IOException {
        // Open the logical data of a regular reparse-backed file (for example, with deduplication).
        // OPEN_EXISTING never truncates; any replacement must still match the verified file ID.
        int flags = expected != null && Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isOther() ? 0 : OPEN_REPARSE_POINT;
        Pointer handle = Kernel.INSTANCE.CreateFileW(name(path), 0x40000080, 7, null, 3, flags, null);
        if (invalid(handle)) {
            throw failure(path.toString(), Native.getLastError());
        }
        try (OutputStream output = new OpenReport(handle, path)) {
            Object actual = null;
            int type = Kernel.INSTANCE.GetFileType(handle);
            if (type == 1) { // FILE_TYPE_DISK
                actual = diskReportKey(handle, path);
                if (actual == null) {
                    throw new IOException("Coverage output must name an identifiable file: " + path);
                }
            } else if (type != 2 && type != 3) { // FILE_TYPE_CHAR or FILE_TYPE_PIPE
                throw failure(path.toString(), Native.getLastError());
            }
            if (!Objects.equals(expected, actual)) {
                throw new IOException("Coverage output changed before it could be written: " + path);
            }
            if (actual != null && Kernel.INSTANCE.SetEndOfFile(handle) == 0) {
                throw failure(path.toString(), Native.getLastError());
            }
            OutputStream buffered = new BufferedOutputStream(output);
            writer.copyTo(buffered);
            buffered.flush();
        }
    }

    static Object reparseReportKey(Path path) throws IOException {
        Pointer handle = open(path, OPEN_DIRECTORY | OPEN_REPARSE_POINT);
        try {
            return Kernel.INSTANCE.GetFileType(handle) == 1 ? diskReportKey(handle, path) : null;
        } finally {
            Kernel.INSTANCE.CloseHandle(handle);
        }
    }

    private static Object diskReportKey(Pointer handle, Path path) throws IOException {
        byte[] info = new byte[52];
        if (Kernel.INSTANCE.GetFileInformationByHandle(handle, info) == 0) {
            throw failure(path.toString(), Native.getLastError());
        }
        int attributes = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if ((attributes & 0x10) != 0 || (attributes & 0x400) != 0 && isNameSurrogate(handle, path)) {
            return null;
        }
        return identity(handle, path.toString());
    }

    private static boolean isNameSurrogate(Pointer handle, Path path) throws IOException {
        byte[] tag = new byte[8]; // FILE_ATTRIBUTE_TAG_INFO
        if (Kernel.INSTANCE.GetFileInformationByHandleEx(handle, 9, tag, tag.length) == 0) {
            throw failure(path.toString(), Native.getLastError());
        }
        return (ByteBuffer.wrap(tag).order(ByteOrder.LITTLE_ENDIAN).getInt(4) & 0x20000000) != 0;
    }

    private static final class OpenReport extends OutputStream {
        private final Pointer handle;
        private final Path path;

        private OpenReport(Pointer handle, Path path) {
            this.handle = handle;
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
            IntByReference written = new IntByReference();
            while (offset < end) {
                byte[] buffer = offset == 0 ? bytes : Arrays.copyOfRange(bytes, offset, end);
                if (Kernel.INSTANCE.WriteFile(handle, buffer, end - offset, written, null) == 0) {
                    throw failure(path.toString(), Native.getLastError());
                }
                if (written.getValue() == 0) {
                    throw new IOException("Cannot finish writing report " + path);
                }
                offset += written.getValue();
            }
        }

        @Override
        public void close() throws IOException {
            if (Kernel.INSTANCE.CloseHandle(handle) == 0) {
                throw failure(path.toString(), Native.getLastError());
            }
        }
    }

    static PendingOutput createPendingOutput(Path path) throws IOException {
        // Keep this handle open without FILE_SHARE_DELETE until publication completes.
        // The Java writer can use a second handle, but the public name stays attached to this file.
        Pointer handle = Kernel.INSTANCE.CreateFileW(name(path), 0x10000, 3, null, 1, 0x80, null);
        if (invalid(handle)) {
            int error = Native.getLastError();
            if (error == 80 || error == 183) {
                throw new FileAlreadyExistsException(path.toString());
            }
            throw failure(path.toString(), error);
        }
        return new PendingOutput(handle, path);
    }

    static final class PendingOutput implements Closeable {
        private final Pointer handle;
        private final Path path;

        private PendingOutput(Pointer handle, Path path) {
            this.handle = handle;
            this.path = path;
        }

        void rollback() throws IOException {
            byte[] delete = {1}; // FILE_DISPOSITION_INFO.DeleteFile
            if (Kernel.INSTANCE.SetFileInformationByHandle(handle, 4, delete, delete.length) == 0) {
                throw failure(path.toString(), Native.getLastError());
            }
        }

        @Override
        public void close() throws IOException {
            if (Kernel.INSTANCE.CloseHandle(handle) == 0) {
                throw failure(path.toString(), Native.getLastError());
            }
        }
    }

    private static Pointer open(Path path, int flags) throws IOException {
        Pointer handle = Kernel.INSTANCE.CreateFileW(name(path), 0x80, 7, null, 3, flags, null);
        if (invalid(handle)) {
            throw failure(path.toString(), Native.getLastError());
        }
        return handle;
    }

    private static WString name(Path path) throws IOException {
        String name = path.toAbsolutePath().toString();
        if (name.length() >= 248 && !name.startsWith("\\\\?\\")) {
            WString absolute = new WString(name);
            char[] normalized = new char[name.length() + 1];
            int length = Kernel.INSTANCE.GetFullPathNameW(absolute, normalized.length, normalized, null);
            if (length >= normalized.length) {
                normalized = new char[length];
                length = Kernel.INSTANCE.GetFullPathNameW(absolute, normalized.length, normalized, null);
            }
            if (length == 0) {
                throw failure(path.toString(), Native.getLastError());
            }
            name = new String(normalized, 0, length);
            name = name.startsWith("\\\\") ? "\\\\?\\UNC\\" + name.substring(2) : "\\\\?\\" + name;
        }
        return new WString(name);
    }

    private static boolean invalid(Pointer handle) {
        long value = Pointer.nativeValue(handle);
        return value == 0 || value == -1 || (Native.POINTER_SIZE == 4 && value == 0xffffffffL);
    }

    private static Object identity(Pointer handle, String description) throws IOException {
        byte[] id = new byte[24];
        // FILE_ID_INFO contains an eight-byte volume serial number and a 128-bit file ID.
        if (Kernel.INSTANCE.GetFileInformationByHandleEx(handle, 18, id, id.length) != 0) {
            ByteBuffer identity = ByteBuffer.wrap(id).asReadOnlyBuffer();
            if (identity.getLong(8) != 0 || identity.getLong(16) != 0) {
                return identity;
            }
        } else {
            int error = Native.getLastError();
            if (!unsupported(error)) {
                throw failure(description, error);
            }
        }
        byte[] attributes = new byte[52];
        if (Kernel.INSTANCE.GetFileInformationByHandle(handle, attributes) == 0) {
            throw failure(description, Native.getLastError());
        }
        ByteBuffer info = ByteBuffer.wrap(attributes).order(ByteOrder.LITTLE_ENDIAN);
        int indexHigh = info.getInt(44);
        int indexLow = info.getInt(48);
        return indexHigh == 0 && indexLow == 0 ? null : List.of(info.getInt(28), indexHigh, indexLow);
    }

    private static boolean unsupported(int error) {
        // Some SMB servers report ERROR_INVALID_LEVEL for a supported API with an unsupported information class.
        return error == 1 || error == 50 || error == 87 || error == 124;
    }

    private static IOException failure(String destination, int error) {
        if (error == 2 || error == 3) {
            return new NoSuchFileException(destination);
        }
        return new IOException("Cannot access " + destination + " (Windows error " + error + ")");
    }

    @Structure.FieldOrder({"length", "descriptor", "inheritHandle"})
    public static final class SecurityAttributes extends Structure {
        public int length;
        public Pointer descriptor;
        public int inheritHandle;
    }

    private interface Kernel extends StdCallLibrary {
        Kernel INSTANCE = Native.load("kernel32", Kernel.class);
        Pointer GetStdHandle(int handle);
        int GetFileType(Pointer handle);
        int GetFullPathNameW(WString path, int size, char[] fullPath, Pointer filePart);
        Pointer CreateFileW(WString name, int access, int share, Pointer security, int creation,
                            int flags, Pointer template);
        int GetFileInformationByHandleEx(Pointer handle, int infoClass, byte[] info, int size);
        int GetFileInformationByHandle(Pointer handle, byte[] info);
        int SetFileInformationByHandle(Pointer handle, int infoClass, byte[] info, int size);
        int SetEndOfFile(Pointer handle);
        int WriteFile(Pointer handle, byte[] buffer, int count, IntByReference written, Pointer overlapped);
        int CloseHandle(Pointer handle);
        Pointer LocalFree(Pointer memory);
    }

    private interface Security extends StdCallLibrary {
        Security INSTANCE = Native.load("advapi32", Security.class);
        int ConvertStringSecurityDescriptorToSecurityDescriptorW(WString sddl, int revision,
                                                                  PointerByReference descriptor, Pointer size);
    }
}
