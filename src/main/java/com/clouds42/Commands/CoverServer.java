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
import org.scalasbt.ipcsocket.Win32NamedPipeServerSocket;
import org.scalasbt.ipcsocket.Win32SecurityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.StandardProtocolFamily;
import java.net.Socket;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public abstract class CoverServer {

    public abstract ConnectionOptions getConnectionOptions();

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    // Closing a second channel for a locked file can release this JVM's lock on Unix.
    private static final Set<Path> LOCAL_UNIX_LOCKS = ConcurrentHashMap.newKeySet();

    private final Object collectionLock = new Object();
    private volatile boolean stopping;

    private ServerSocket windowsListener;
    private volatile boolean listeningWindows;
    private ServerSocketChannel unixListener;
    private FileChannel unixLockChannel;
    private Path unixSocketPath;
    private Object unixSocketKey;

    protected synchronized void closeSocket() throws IOException {
        if (windowsListener != null) {
            listeningWindows = false;
            windowsListener.close();
            windowsListener = null;
        } else if (unixListener != null) {
            try (FileChannel lock = unixLockChannel) {
                unixListener.close();
                removeSocketIfOwned(unixSocketPath, unixSocketKey);
            } finally {
                LOCAL_UNIX_LOCKS.remove(unixSocketPath);
                unixListener = null;
            }
        }
        logger.info("Close server socket");
    }

    protected synchronized void startListening() throws IOException {
        String pipeName = Utils.getPipeName(getConnectionOptions());
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            ServerSocket listener = new Win32NamedPipeServerSocket(pipeName, false, Win32SecurityLevel.OWNER_DACL);
            windowsListener = listener;
            listeningWindows = true;
            startDaemon(() -> acceptWindowsCommands(listener), "coverage41c-commands");
        } else {
            startUnixListening(Path.of(pipeName));
        }
        logger.info("Create server socket");
    }

    private void startUnixListening(Path socketPath) throws IOException {
        if (!LOCAL_UNIX_LOCKS.add(socketPath)) {
            throw alreadyRunning(socketPath);
        }
        FileChannel lock = null;
        ServerSocketChannel listener = null;
        try {
            Path lockPath = socketPath.resolveSibling(socketPath.getFileName() + ".lock");
            // Leave the lock file in place so all processes lock the same inode. A dead process releases the lock.
            lock = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            if (lock.tryLock() == null) {
                throw alreadyRunning(socketPath);
            }
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
            removeStaleSocket(address);

            listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(address);
            unixSocketKey = socketKey(socketPath);
            unixSocketPath = socketPath;
            unixLockChannel = lock;
            unixListener = listener;
        } catch (IOException | RuntimeException e) {
            try (FileChannel failedLock = lock; ServerSocketChannel failedListener = listener) {
                throw e;
            } finally {
                LOCAL_UNIX_LOCKS.remove(socketPath);
            }
        }
        ServerSocketChannel activeListener = listener;
        startDaemon(() -> acceptUnixCommands(activeListener), "coverage41c-commands");
    }

    private static IOException alreadyRunning(Path socketPath) {
        return new IOException("Coverage41C is already running for this infobase and debugger: " + socketPath);
    }

    // A socket file left by a killed process blocks bind. Only a refused connection identifies a stale socket.
    private static void removeStaleSocket(UnixDomainSocketAddress address) throws IOException {
        Path path = address.getPath();
        Object key = socketKey(path);
        if (key == null) {
            return;
        }
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.configureBlocking(false);
            probe.connect(address);
        } catch (ConnectException e) {
            removeSocketIfOwned(path, key);
            logger.info("Removed socket left by a stopped process: {}", path);
            return;
        }
        throw alreadyRunning(path);
    }

    private static void removeSocketIfOwned(Path path, Object key) throws IOException {
        if (key != null && key.equals(socketKey(path))) {
            Files.deleteIfExists(path);
        }
    }

    private static Object socketKey(Path path) throws IOException {
        try {
            Map<String, Object> attributes = Files.readAttributes(path, "unix:mode,fileKey", LinkOption.NOFOLLOW_LINKS);
            int mode = (Integer) attributes.get("mode");
            // The high mode bits identify the socket file type and distinguish it from regular files and symlinks.
            return (mode & 0170000) == 0140000 ? attributes.get("fileKey") : null;
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    private static void startDaemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptWindowsCommands(ServerSocket listener) {
        logger.info("Set pipe listener...");
        while (listeningWindows) {
            try {
                Socket client = listener.accept();
                startDaemon(() -> {
                    try (client) {
                        listenSocket(client.getInputStream(), client.getOutputStream());
                    } catch (IOException e) {
                        logger.error(e.getLocalizedMessage());
                    }
                }, "coverage41c-command");
            } catch (IOException e) {
                if (listeningWindows) {
                    logger.info("Can't accept pipe: {}", e.getLocalizedMessage());
                }
            }
        }
    }

    private void acceptUnixCommands(ServerSocketChannel listener) {
        logger.info("Set socket listener...");
        while (listener.isOpen()) {
            try {
                SocketChannel client = listener.accept();
                startDaemon(() -> {
                    try (client) {
                        listenSocket(Channels.newInputStream(client), Channels.newOutputStream(client));
                    } catch (IOException e) {
                        logger.error(e.getLocalizedMessage());
                    }
                }, "coverage41c-command");
            } catch (IOException e) {
                if (listener.isOpen()) {
                    logger.info("Can't accept socket: {}", e.getLocalizedMessage());
                }
            }
        }
    }

    private void listenSocket(InputStream input, OutputStream output) throws IOException {
        PrintWriter out = new PrintWriter(output, true);
        BufferedReader in = new BufferedReader(new InputStreamReader(input));
        // The client closes the connection after reading the reply. Closing it here first could drop
        // an unread reply on a Windows named pipe.
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.trim().toLowerCase(Locale.ROOT);
                logger.info("Get command: {}", command);
                executeCommand(command, out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void executeCommand(String command, PrintWriter out) throws InterruptedException {
        switch (command) {
            case PipeMessages.DUMP_COMMAND:
                out.println(runCollectionCommand(this::dumpCoverage));
                break;
            case PipeMessages.STATS_COMMAND:
                out.println(printCoverageStats() ? PipeMessages.OK_RESULT : PipeMessages.FAIL_RESULT);
                break;
            case PipeMessages.CLEAN_COMMAND:
                out.println(runCollectionCommand(() -> {
                    cleanCoverageData();
                    return true;
                }));
                break;
            case PipeMessages.CHECK_COMMAND:
                for (int i = 0; i < 60 && !stopping; i++) {
                    if (getSystemStarted() && !stopping) {
                        out.println(PipeMessages.OK_RESULT);
                        return;
                    }
                    Thread.sleep(1000);
                }
                out.println(PipeMessages.FAIL_RESULT);
                break;
            case PipeMessages.EXIT_COMMAND:
                if (getSystemStarted()) {
                    gracefulShutdown(out);
                } else {
                    out.println(PipeMessages.FAIL_RESULT);
                }
                break;
            default:
                logger.info("Unknown command skipped: {}", command);
        }
    }

    protected final void closeCollectionCommands() {
        synchronized (collectionLock) {
            stopping = true;
        }
    }

    private String runCollectionCommand(BooleanSupplier command) {
        synchronized (collectionLock) {
            if (stopping || !getSystemStarted() || !command.getAsBoolean()) {
                return PipeMessages.FAIL_RESULT;
            }
            return PipeMessages.OK_RESULT;
        }
    }

    private boolean printCoverageStats() {
        if (!getSystemStarted()) {
            return true;
        }
        Map<URI, Map<BigDecimal, Integer>> coverageData = getCoverageData();
        synchronized (coverageData) {
            try {
                if (!coverageData.isEmpty()) {
                    Utils.printCoverageStats(coverageData, getMetadataOptions(), System.err);
                }
                return true;
            } catch (RuntimeException e) {
                logger.error("Can't print coverage statistics: {}", e.toString());
                return false;
            }
        }
    }

    protected boolean dumpCoverage() {
        Map<URI, Map<BigDecimal, Integer>> coverageData = getCoverageData();
        synchronized (coverageData) {
            try {
                Utils.dumpCoverageFile(coverageData, getMetadataOptions(), getOutputOptions());
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