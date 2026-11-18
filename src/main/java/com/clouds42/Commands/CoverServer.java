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
import org.scalasbt.ipcsocket.UnixDomainServerSocket;
import org.scalasbt.ipcsocket.UnixDomainSocket;
import org.scalasbt.ipcsocket.Win32NamedPipeServerSocket;
import org.scalasbt.ipcsocket.Win32SecurityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public abstract class CoverServer {

    public abstract ConnectionOptions getConnectionOptions();

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private ServerSocket serverSocket;
    private String pipeName;
    private volatile boolean listening;
    private final boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

    protected void closeSocket() throws IOException {
        if (serverSocket != null) {
            listening = false;
            serverSocket.close();
            serverSocket = null;
            if (!isWindows) {
                Files.deleteIfExists(Path.of(pipeName));
            }
            logger.info("Close server socket");
        }
    }

    protected ServerSocket getServerSocket() throws IOException {

        if(serverSocket == null) {
            pipeName = Utils.getPipeName(getConnectionOptions());
            if (isWindows) {
                serverSocket = new Win32NamedPipeServerSocket(pipeName, false, Win32SecurityLevel.OWNER_DACL);
            } else {
                removeStaleSocket(pipeName);
                serverSocket = new UnixDomainServerSocket(pipeName);
            }
            listening = true;
            startDaemon(this::acceptCommands, "coverage41c-commands");
            logger.info("Create server socket");
        }
        return serverSocket;
    }

    // A socket file left by a killed process blocks bind, a live one answers the connection.
    private static void removeStaleSocket(String pipeName) throws IOException {
        Path socketFile = Path.of(pipeName);
        if (!Files.exists(socketFile)) {
            return;
        }
        boolean alive;
        try (Socket ignored = new UnixDomainSocket(pipeName)) {
            alive = true;
        } catch (IOException e) {
            alive = false;
        }
        if (alive) {
            throw new IOException("Coverage41C is already running for this infobase and debugger: " + pipeName);
        }
        Files.delete(socketFile);
        logger.info("Removed socket left by a stopped process: {}", pipeName);
    }

    private static void startDaemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptCommands() {
        logger.info("Set socket listener...");
        ServerSocket socket = serverSocket;
        while (listening) {
            try {
                Socket clientSocket = socket.accept();
                startDaemon(() -> listenSocket(clientSocket), "coverage41c-command");
            } catch (IOException e) {
                if (listening) {
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
                String command = line.trim().toLowerCase();
                logger.info("Get command: {}", command);
                executeCommand(command, out);
            }
        } catch (IOException | InterruptedException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    private void executeCommand(String command, PrintWriter out) throws InterruptedException {
        switch (command) {
            case PipeMessages.DUMP_COMMAND:
                out.println(dumpCoverage() ? PipeMessages.OK_RESULT : PipeMessages.FAIL_RESULT);
                break;
            case PipeMessages.STATS_COMMAND:
                out.println(PipeMessages.OK_RESULT);
                break;
            case PipeMessages.CLEAN_COMMAND:
                cleanCoverageData();
                out.println(PipeMessages.OK_RESULT);
                break;
            case PipeMessages.CHECK_COMMAND:
                for (int i = 0; i < 60; i++) {
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