/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2022
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
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class CoverServer {

    public abstract ConnectionOptions getConnectionOptions();

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private ServerSocket serverSocket;
    private final boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

    protected void closeSocket() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
            serverSocket = null;
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
            CompletableFuture.supplyAsync(() -> {
                logger.info("Set socket listener...");
                AtomicBoolean stopListen = new AtomicBoolean(false);
                while (!stopListen.get()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        CompletableFuture.supplyAsync(() -> listenSocket(clientSocket)).thenAccept(aBoolean -> {
                            if (aBoolean) {
                                logger.info("Start listen");
                                stopListen.set(false);
                            }
                        });
                    } catch (IOException e) {
                        logger.info("Can't accept socket: {}", e.getLocalizedMessage());
                    }
                }
                return true;
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
                            Utils.dumpCoverageFile(getCoverageData(), getMetadataOptions(), getOutputOptions());
                            out.println(PipeMessages.OK_RESULT);
                            return true;
                        case PipeMessages.STATS_COMMAND:
                            out.println(PipeMessages.OK_RESULT);
                            return true;
                        case PipeMessages.CLEAN_COMMAND:
                            getCoverageData().forEach((uri, bigDecimalIntegerMap) ->
                                    bigDecimalIntegerMap.replaceAll((k, v) -> 0));
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

    protected abstract void gracefulShutdown(PrintWriter out) throws IOException;

    protected abstract MetadataOptions getMetadataOptions();

    protected abstract Map<URI, Map<BigDecimal, Integer>> getCoverageData();

    protected abstract OutputOptions getOutputOptions();


}