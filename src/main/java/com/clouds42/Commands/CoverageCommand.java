/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2024
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

import com.clouds42.CommandLineOptions.*;
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import com.github._1c_syntax.coverage41C.CoverageCollector;
import com.github._1c_syntax.coverage41C.CoverageManager;
import com.github._1c_syntax.coverage41C.DebugClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

@Command(name = PipeMessages.START_COMMAND, mixinStandardHelpOptions = true,
        description = "Start measure and save coverage data to file",
        sortOptions = false)
public class CoverageCommand extends CoverServer implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Mixin
    private ConnectionOptions connectionOptions;

    @Mixin
    private FilterOptions filterOptions;

    @Mixin
    private MetadataOptions metadataOptions;

    @Mixin
    private OutputOptions outputOptions;

    @Mixin
    private DebuggerOptions debuggerOptions;

    @Mixin
    private LoggingOptions loggingOptions;

    @Option(names = {"--opid"}, description = "Owner process PID", defaultValue = "-1")
    Integer opid;

    private CoverageManager coverageManager;
    private CoverageCollector collector;

    private final AtomicBoolean stopExecution = new AtomicBoolean(false);
    private boolean systemStarted = false;

    @Override
    public Integer call() throws Exception {

        int result = CommandLine.ExitCode.OK;
        getServerSocket();

        collector = new CoverageCollector(metadataOptions, loggingOptions, filterOptions, outputOptions);
        collector.readMetadata();

        coverageManager = new CoverageManager(collector, connectionOptions, debuggerOptions);

        UUID measureUuid = UUID.randomUUID();
        try {
            startSystem(measureUuid);
        } catch (DebugClientException e) {
            logger.info("Connecting to dbgs failed");
            logger.error(e.getLocalizedMessage());
            result = CommandLine.ExitCode.SOFTWARE;
            return result;
        }

        addShutdownHook();

        try {
            mainLoop();
        } catch (DebugClientException e) {
            logger.error("Can't send ping to debug server. Coverage analyzing finished");
            logger.error(e.getLocalizedMessage());
            e.printStackTrace();
            result = CommandLine.ExitCode.SOFTWARE;
        }
        Thread.sleep(debuggerOptions.getPingTimeout());

        shutdown();
        return result;
    }

    private void shutdown() throws IOException, DebugClientException {
        if (opid > 0 && !Utils.isProcessStillAlive(opid)) {
            logger.info("Owner process stopped: {}", opid);
        }

        gracefulShutdown(null);

        coverageManager.disconnect();
        closeSocket();

        logger.info("Main thread finished");
        stopExecution.set(true);
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdown();
            } catch (IOException | DebugClientException e) {
                logger.info("Shutdown error.");
                e.printStackTrace();
            }
        }));
    }

    private void mainLoop() throws DebugClientException {
        while (!stopExecution.get()) {
            coverageManager.ping();
        }
    }

    private void startSystem(UUID measureUuid) throws DebugClientException {
        coverageManager.connect();
        coverageManager.start(measureUuid);
        systemStarted = true;
    }

    protected void gracefulShutdown(PrintWriter serverPipeOut) {
        if (stopExecution.get()) {
            return;
        }

        try {
            coverageManager.stop();
        } catch (DebugClientException e) {
            logger.error("Error gracefulShutdown", e);
        }

        dumpCoverageData();

        if (serverPipeOut != null) {
            serverPipeOut.println(PipeMessages.OK_RESULT);
        }
        stopExecution.set(true);

        logger.info("Bye!");
    }

    @Override
    protected void dumpCoverageData() {
        collector.dumpCoverageData();
    }

    @Override
    protected void cleanCoverageData() {
        collector.cleanCoverageData();
    }

    @Override
    public ConnectionOptions getConnectionOptions() {
        return connectionOptions;
    }

    @Override
    protected boolean getSystemStarted() {
        return systemStarted;
    }
}
