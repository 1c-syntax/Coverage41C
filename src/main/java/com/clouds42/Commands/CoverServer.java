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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public abstract class CoverServer {

    public abstract ConnectionOptions getConnectionOptions();

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private volatile ServerSocket serverSocket;
    private final boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

    protected void closeSocket() throws IOException {
        ServerSocket listener = serverSocket;
        if (listener != null) {
            serverSocket = null;
            listener.close();
            logger.info("Close server socket");
        }
    }

    protected ServerSocket getServerSocket() throws IOException {

        String pipeName = Utils.getPipeName(getConnectionOptions());
        if(serverSocket == null) {
            if (isWindows) {
                serverSocket = new Win32NamedPipeServerSocket(pipeName, false, Win32SecurityLevel.OWNER_DACL);
            } else {
                serverSocket = new UnixDomainServerSocket(pipeName);
            }
            ServerSocket listener = serverSocket;
            // A blocking native accept must leave the shared workers available for client commands.
            Thread.ofPlatform().daemon().name("coverage-socket-listener").start(() -> {
                logger.info("Set socket listener...");
                while (serverSocket == listener) {
                    try {
                        Socket clientSocket = listener.accept();
                        if (serverSocket != listener) {
                            clientSocket.close();
                            return;
                        }
                        CompletableFuture.runAsync(() -> {
                            if (listenSocket(clientSocket)) {
                                logger.info("Start listen");
                            }
                        });
                    } catch (IOException e) {
                        if (serverSocket == listener) {
                            logger.info("Can't accept socket: {}", e.getLocalizedMessage());
                        }
                    }
                }
            });
            logger.info("Create server socket");
        }
        return serverSocket;
    }

    private Boolean listenSocket(Socket clientSocket) {
        try {
            PrintWriter out =
                    new PrintWriter(clientSocket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
            String line;
            do {
                line = in.readLine();
                if (line != null) {
                    line = line.trim().toLowerCase();
                    logger.info("Get command: {}", line);
                    switch (line) {
                        case PipeMessages.DUMP_COMMAND:
                            out.println(writeCoverageDump() ? PipeMessages.OK_RESULT : PipeMessages.FAIL_RESULT);
                            return true;
                        case PipeMessages.STATS_COMMAND:
                            out.println(PipeMessages.OK_RESULT);
                            return true;
                        case PipeMessages.CLEAN_COMMAND:
                            cleanCoverageData();
                            out.println(PipeMessages.OK_RESULT);
                            return true;
                        case PipeMessages.CHECK_COMMAND:
                            for (int i = 0; i < 60; i++) {
                                if (getSystemStarted()) {
                                    out.println(PipeMessages.OK_RESULT);
                                    return true;
                                }
                                Thread.sleep(1000);
                            }
                            out.println(PipeMessages.FAIL_RESULT);
                            return true;
                    }
                }
            } while (line == null || !line.equals(PipeMessages.EXIT_COMMAND));
            gracefulShutdown(out);
            return false;
        } catch (IOException | InterruptedException e) {
            logger.error(e.getLocalizedMessage());
        }
        return true;
    }

    protected abstract boolean getSystemStarted();

    protected boolean writeCoverageDump() {
        Utils.dumpCoverageFile(getCoverageData(), getMetadataOptions(), getOutputOptions());
        return true;
    }

    protected void cleanCoverageData() {
        getCoverageData().forEach((uri, lines) -> lines.replaceAll((line, hits) -> 0));
    }

    protected abstract void gracefulShutdown(PrintWriter out) throws IOException;

    protected abstract MetadataOptions getMetadataOptions();

    protected abstract Map<URI, Map<BigDecimal, Integer>> getCoverageData();

    protected abstract OutputOptions getOutputOptions();


}
