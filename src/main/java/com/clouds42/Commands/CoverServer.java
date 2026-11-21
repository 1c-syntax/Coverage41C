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
package com.clouds42.Commands;

import com.clouds42.CommandLineOptions.ConnectionOptions;
import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import org.scalasbt.ipcsocket.NativeErrorException;
import org.scalasbt.ipcsocket.UnixDomainSocket;
import org.scalasbt.ipcsocket.UnixDomainSocketLibrary;
import org.scalasbt.ipcsocket.UnixDomainSocketLibraryProvider;
import org.scalasbt.ipcsocket.Win32NamedPipeServerSocket;
import org.scalasbt.ipcsocket.Win32SecurityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public abstract class CoverServer {

    public abstract ConnectionOptions getConnectionOptions();

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

    // Borrow stderr for the process lifetime; never close this descriptor.
    private static final OutputStream STATISTICS_OUTPUT = new FileOutputStream(FileDescriptor.err);
    // Keep the protected default usable by subclasses that wrap super.coverageOutput().
    private static final OutputStream DEFAULT_COVERAGE_OUTPUT = new FileOutputStream(FileDescriptor.out);

    private final Object collectionCommands = new Object();
    private volatile boolean stopping;
    private volatile CommandSocket serverSocket;
    private OutputStream measurementOutput;
    final Utils.MeasurementReports measurementReports = new Utils.MeasurementReports();

    protected synchronized void closeSocket() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
    }

    protected synchronized void startListening() throws IOException {
        // Existing subclasses can override how the command listener starts.
        getServerSocket();
    }

    protected synchronized ServerSocket getServerSocket() throws IOException {
        if (serverSocket == null) {
            String pipeName = Utils.getPipeName(getConnectionOptions());
            CommandSocket socket = OS.contains("win") ? new CommandSocket(pipeName)
                    : openUnixSocket(Path.of(pipeName));
            serverSocket = socket;
            socket.acceptThread = startDaemon(() -> acceptCommands(socket), "coverage41c-commands");
            logger.info("Create server socket");
        }
        return serverSocket;
    }

    private CommandSocket openUnixSocket(Path socketPath) throws IOException {
        UnixSocketLock lock = UnixSocketLock.acquire(socketPath);
        UnixListener listener = null;
        Object key = null;
        try {
            removeStaleSocket(socketPath);
            listener = new UnixListener();
            listener.bind(socketPath);
            key = socketKey(socketPath);
            listener.listen();
            return new CommandSocket(listener, lock, socketPath, key);
        } catch (IOException | RuntimeException e) {
            try (UnixSocketLock failedLock = lock; UnixListener failedListener = listener) {
                try {
                    removeSocketIfOwned(socketPath, key);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
                throw e;
            }
        }
    }

    private final class CommandSocket extends ServerSocket {
        private final SocketAcceptor acceptor;
        private final Closeable endpoint;
        private Thread acceptThread;

        private CommandSocket(String pipeName) throws IOException {
            ServerSocket pipe = new Win32NamedPipeServerSocket(pipeName, false, Win32SecurityLevel.OWNER_DACL);
            acceptor = pipe::accept;
            endpoint = pipe::close;
        }

        private CommandSocket(UnixListener listener, UnixSocketLock lock, Path path, Object key) throws IOException {
            acceptor = listener::accept;
            endpoint = () -> {
                try (lock; Closeable socketFile = () -> removeSocketIfOwned(path, key)) {
                    listener.close(acceptThread);
                }
            };
        }

        @Override
        public Socket accept() throws IOException {
            if (isClosed()) {
                throw new SocketException("Socket is closed");
            }
            return acceptor.accept();
        }

        @Override
        public boolean isClosed() {
            return serverSocket != this;
        }

        @Override
        public boolean isBound() {
            return true;
        }

        // Neither local IPC transport has an Internet address or TCP port.
        @Override
        public InetAddress getInetAddress() {
            return null;
        }

        @Override
        public int getLocalPort() {
            return -1;
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return null;
        }

        @Override
        public void close() throws IOException {
            synchronized (CoverServer.this) {
                if (isClosed()) {
                    return;
                }
                try {
                    super.close();
                } finally {
                    serverSocket = null;
                    endpoint.close();
                }
                logger.info("Close server socket");
            }
        }
    }

    private static IOException alreadyRunning(Path socketPath) {
        return new IOException("Coverage41C is already running for this infobase and debugger: " + socketPath);
    }

    private static final class UnixSocketLock implements AutoCloseable {
        private static final NativeFileLock NATIVE = Native.load(Platform.C_LIBRARY_NAME, NativeFileLock.class);
        private static final int O_NOFOLLOW = OS.contains("linux") ? 0x20000 : 0x100;
        private static final int LOCK_EXCLUSIVE_NONBLOCKING = 2 | 4;
        private static final int EWOULDBLOCK = OS.contains("linux") ? 11 : 35;
        // Closing any descriptor of this inode can release this JVM's POSIX compatibility lock.
        // Reserve by inode before either open, even if the lock file has aliases.
        private static final Set<Object> LOCAL_INODES = ConcurrentHashMap.newKeySet();

        private final Object inode;
        private int descriptor = -1;
        private FileChannel compatibilityChannel;

        private UnixSocketLock(Object inode) {
            this.inode = inode;
        }

        private static UnixSocketLock acquire(Path socketPath) throws IOException {
            Path lockPath = socketPath.resolveSibling(socketPath.getFileName() + ".lock");
            ensureLockFile(lockPath);
            BasicFileAttributes attributes = Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Expected a regular socket lock file: " + lockPath);
            }
            Object inode = attributes.fileKey() == null ? lockPath.toRealPath() : attributes.fileKey();
            if (!LOCAL_INODES.add(inode)) {
                throw alreadyRunning(socketPath);
            }
            UnixSocketLock lock = new UnixSocketLock(inode);
            try {
                lock.open(lockPath, socketPath);
                return lock;
            } catch (IOException | RuntimeException e) {
                try (UnixSocketLock ignored = lock) {
                    throw e;
                }
            }
        }

        private static void ensureLockFile(Path lockPath) throws IOException {
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path temporary = Files.createTempFile(lockPath.getParent(), ".coverage41c-", ".lock",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.createLink(lockPath, temporary);
            } catch (FileAlreadyExistsException ignored) {
                // Another process published its marker first. Both processes will lock that inode.
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private void open(Path lockPath, Path socketPath) throws IOException {
            // flock is exclusive even on a read-only descriptor, so a historical 0644 marker
            // remains usable by the next Unix user without replacing its inode.
            descriptor = NATIVE.open(lockPath.toString(), O_NOFOLLOW);
            if (descriptor < 0) {
                throw new IOException("Cannot open socket lock file " + lockPath + ": errno " + Native.getLastError());
            }
            if (NATIVE.flock(descriptor, LOCK_EXCLUSIVE_NONBLOCKING) != 0) {
                int errno = Native.getLastError();
                if (errno == EWOULDBLOCK) {
                    throw alreadyRunning(socketPath);
                }
                throw new IOException("Cannot lock " + lockPath + ": errno " + errno);
            }
            // Darwin already conflicts with the FileChannel lock of older releases.
            // Linux keeps flock and POSIX record locks separate.
            if (!OS.contains("mac")) {
                compatibilityChannel = FileChannel.open(lockPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                if (compatibilityChannel.tryLock(0, Long.MAX_VALUE, true) == null) {
                    throw alreadyRunning(socketPath);
                }
            }
        }

        private void closeDescriptor() throws IOException {
            if (descriptor >= 0 && NATIVE.close(descriptor) != 0) {
                throw new IOException("Cannot close socket lock: errno " + Native.getLastError());
            }
        }

        @Override
        public void close() throws IOException {
            try (Closeable nativeDescriptor = this::closeDescriptor;
                 FileChannel channel = compatibilityChannel) {
                // Close the POSIX compatibility lock before releasing flock.
            } finally {
                LOCAL_INODES.remove(inode);
            }
        }

        private interface NativeFileLock extends Library {
            int open(String path, int flags);
            int flock(int fd, int operation);
            int close(int fd);
        }
    }

    // Only a refused connection identifies a stale socket. Missing permissions and other errors are reported.
    private static void removeStaleSocket(Path path) throws IOException {
        Object key = socketKey(path);
        if (key == null) {
            return;
        }
        try (UnixListener probe = new UnixListener()) {
            if (probe.isStale(path) && removeSocketIfOwned(path, key)) {
                logger.info("Removed socket left by a stopped process: {}", path);
                return;
            }
        }
        throw alreadyRunning(path);
    }

    private static boolean removeSocketIfOwned(Path path, Object key) throws IOException {
        return key != null && key.equals(socketKey(path)) && Files.deleteIfExists(path);
    }

    private static Object socketKey(Path path) throws IOException {
        try {
            Map<String, Object> attributes = Files.readAttributes(path, "unix:mode,fileKey", LinkOption.NOFOLLOW_LINKS);
            int mode = (Integer) attributes.get("mode");
            // The high mode bits distinguish a socket from regular files and symbolic links.
            return (mode & 0170000) == 0140000 ? attributes.get("fileKey") : null;
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SocketAcceptor {
        Socket accept() throws IOException;
    }

    // Java 11's Unix listener uses the native calls already provided by ipcsocket.
    private static final class UnixListener implements AutoCloseable {
        private static final int BACKLOG = 50;
        private static final int SHUT_RDWR = 2;
        private static final int ECONNREFUSED = OS.contains("linux") ? 111 : 61; // BSD/macOS uses 61.

        private final UnixDomainSocketLibraryProvider provider = UnixDomainSocketLibraryProvider.get(false);
        private final int fd;

        private UnixListener() throws IOException {
            try {
                fd = provider.socket(UnixDomainSocketLibrary.PF_LOCAL, UnixDomainSocketLibrary.SOCK_STREAM, 0);
            } catch (NativeErrorException e) {
                throw new IOException(e);
            }
        }

        private void bind(Path path) throws IOException {
            byte[] address = path.toString().getBytes(Charset.defaultCharset());
            try {
                provider.bind(fd, address, address.length);
            } catch (NativeErrorException e) {
                throw new IOException(e);
            }
        }

        private void listen() throws IOException {
            try {
                provider.listen(fd, BACKLOG);
            } catch (NativeErrorException e) {
                throw new IOException(e);
            }
        }

        private Socket accept() throws IOException {
            try {
                return new UnixDomainSocket(provider.accept(fd));
            } catch (NativeErrorException e) {
                throw new IOException(e);
            }
        }

        private boolean isStale(Path path) throws IOException {
            byte[] address = path.toString().getBytes(Charset.defaultCharset());
            try {
                provider.connect(fd, address, address.length);
                return false;
            } catch (NativeErrorException e) {
                if (e.returnCode() == ECONNREFUSED) {
                    return true;
                }
                throw new IOException(e);
            }
        }

        private void close(Thread accepter) throws IOException {
            if (OS.contains("linux")) {
                // Linux close(2) alone leaves a concurrent accept(2) blocked.
                try {
                    provider.shutdown(fd, SHUT_RDWR);
                    awaitListenerExit(accepter);
                } catch (NativeErrorException e) {
                    throw new IOException(e);
                } finally {
                    close();
                }
            } else {
                // Darwin close wakes accept; shutdown on a listening socket returns ENOTCONN.
                close();
                awaitListenerExit(accepter);
            }
        }

        private static void awaitListenerExit(Thread accepter) throws IOException {
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            try {
                while (accepter.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new IOException("Command listener did not stop");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedJoin(accepter, remaining);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() throws IOException {
            try {
                provider.close(fd);
            } catch (NativeErrorException e) {
                throw new IOException(e);
            }
        }
    }

    private static Thread startDaemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void acceptCommands(ServerSocket socket) {
        logger.info("Set socket listener...");
        while (!socket.isClosed()) {
            try {
                Socket clientSocket = socket.accept();
                if (socket.isClosed()) {
                    clientSocket.close();
                    return;
                }
                startDaemon(() -> listenSocket(clientSocket), "coverage41c-command");
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    logger.info("Can't accept socket: {}", e.getLocalizedMessage());
                }
            }
        }
    }

    private void listenSocket(Socket clientSocket) {
        try (Socket socket = clientSocket) {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            // The client closes the connection after reading the reply. Closing it here first could drop
            // an unread reply on a Windows named pipe.
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.trim().toLowerCase(Locale.ROOT);
                logger.info("Get command: {}", command);
                executeCommand(command, out);
            }
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void executeCommand(String command, PrintWriter out) throws InterruptedException {
        if (!getSystemStarted() && (PipeMessages.DUMP_COMMAND.equals(command)
                || PipeMessages.CLEAN_COMMAND.equals(command) || PipeMessages.EXIT_COMMAND.equals(command))) {
            out.println(PipeMessages.FAIL_RESULT);
            return;
        }
        switch (command) {
            case PipeMessages.DUMP_COMMAND:
                out.println(runDuringCollection(this::dumpCoverage));
                break;
            case PipeMessages.STATS_COMMAND:
                out.println(printCoverageStats() ? PipeMessages.OK_RESULT : PipeMessages.FAIL_RESULT);
                break;
            case PipeMessages.CLEAN_COMMAND:
                out.println(runDuringCollection(() -> {
                    cleanCoverageData();
                    return true;
                }));
                break;
            case PipeMessages.CHECK_COMMAND:
                for (int i = 0; i < 60 && !stopping; i++) {
                    if (getSystemStarted()) {
                        out.println(PipeMessages.OK_RESULT);
                        return;
                    }
                    Thread.sleep(1000);
                }
                out.println(PipeMessages.FAIL_RESULT);
                break;
            case PipeMessages.EXIT_COMMAND:
                gracefulShutdown(out);
                break;
            default:
                logger.info("Unknown command skipped: {}", command);
        }
    }

    protected final void closeCollectionCommands() {
        synchronized (collectionCommands) {
            stopping = true;
        }
    }

    private String runDuringCollection(BooleanSupplier command) {
        synchronized (collectionCommands) {
            return !stopping && command.getAsBoolean() ? PipeMessages.OK_RESULT : PipeMessages.FAIL_RESULT;
        }
    }

    private boolean printCoverageStats() {
        if (!getSystemStarted()) {
            return false;
        }
        Map<URI, Map<BigDecimal, Integer>> coverageData = getCoverageData();
        synchronized (coverageData) {
            try {
                Utils.writeCoverageStats(coverageData, getMetadataOptions(), statisticsOutput());
                return true;
            } catch (IOException | RuntimeException e) {
                logger.error("Can't print coverage statistics: {}", e.toString());
                return false;
            }
        }
    }

    /**
     * Native process stderr by default; embedded collectors can supply a throwing sink.
     * The sink is borrowed, flushed and never closed. It must report write/flush failures
     * with IOException; PrintStream suppresses those errors and is not supported.
     */
    protected OutputStream statisticsOutput() {
        return STATISTICS_OUTPUT;
    }

    /** The collector borrows this sink for stdout reports. It must propagate IOExceptions. */
    protected OutputStream coverageOutput() {
        return DEFAULT_COVERAGE_OUTPUT;
    }

    private static OutputStream reportOutput(OutputStream sink, File coverageFile) {
        // Without -o the default follows System.out at dump/stop; an explicit fd path uses fd 1.
        return coverageFile == null && sink == DEFAULT_COVERAGE_OUTPUT ? null : Objects.requireNonNull(sink,
                "coverageOutput() must return an OutputStream");
    }

    final Map<String, URI> readMeasurementMetadata(MetadataOptions options, String mainExtension, String mainSourceUrl,
                                                   Map<URI, Map<BigDecimal, Integer>> coverage,
                                                   boolean hasUserFilter, File coverageFile) throws Exception {
        return measurementReports.readMetadata(options, mainExtension, mainSourceUrl, coverage, hasUserFilter, coverageFile, () -> {
            measurementOutput = coverageOutput();
            return reportOutput(measurementOutput, coverageFile);
        });
    }

    protected boolean dumpCoverage() {
        Map<URI, Map<BigDecimal, Integer>> coverageData = getCoverageData();
        synchronized (coverageData) {
            try {
                OutputOptions options = getOutputOptions();
                measurementReports.dump(coverageData, getMetadataOptions(), options,
                        () -> reportOutput(measurementOutput == null ? coverageOutput() : measurementOutput,
                                options.getOutputFile()));
                return true;
            } catch (IOException | RuntimeException e) {
                logger.error("Can't write coverage file: {}", e.toString());
                return false;
            }
        }
    }

    protected abstract void cleanCoverageData();

    protected abstract boolean getSystemStarted();

    protected abstract void gracefulShutdown(PrintWriter out);

    protected abstract MetadataOptions getMetadataOptions();

    protected abstract Map<URI, Map<BigDecimal, Integer>> getCoverageData();

    protected abstract OutputOptions getOutputOptions();


}
