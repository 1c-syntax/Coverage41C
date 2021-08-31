/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2021
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

import com._1c.g5.v8.dt.debug.core.runtime.client.RuntimeDebugClientException;
import com._1c.g5.v8.dt.debug.model.base.data.AttachDebugUIResult;
import com._1c.g5.v8.dt.debug.model.base.data.BSLModuleIdInternal;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetId;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoBase;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmds;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.impl.DBGUIExtCmdInfoMeasureImpl;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.impl.DBGUIExtCmdInfoStartedImpl;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoLine;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoMain;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoModule;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugModelXmlSerializer;
import com.clouds42.CommandLineOptions.*;
import com.clouds42.DebugClient;
import com.clouds42.MyRuntimeDebugModelXmlSerializer;
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import java.lang.module.ModuleDescriptor.Version;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

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

    private DebugClient client;

    private final Map<URI, Map<BigDecimal, Integer>> coverageData = new HashMap<>() {
        @Override
        public Map<BigDecimal, Integer> get(Object key) {
            Map<BigDecimal, Integer> map = super.get(key);
            if (map == null) {
                map = new HashMap<>();
                put((URI) key, map);
            }
            return map;
        }
    };


    private final AtomicBoolean stopExecution = new AtomicBoolean(false);
    private boolean rawMode = false;
    private boolean systemStarted = false;

    private void connectAllTargets(List<DebugTargetId> debugTargets) {
        logger.info("Current debug targets size: {}", debugTargets.size());
        debugTargets.forEach(debugTarget -> {
            String id = debugTarget.getId();
            String seanceId = debugTarget.getSeanceId();
            String targetType = debugTarget.getTargetType().getName();
            logger.info("Id: {} , seance id: {} , target type: {}", id, seanceId, targetType);
            try {
                client.attachRuntimeDebugTargets(Collections.singletonList(UUID.fromString(debugTarget.getId())));
            } catch (RuntimeDebugClientException e) {
                logger.error(e.getLocalizedMessage());
            }
        });
    }

    @Override
    public Integer call() throws Exception {

        int result = CommandLine.ExitCode.OK;
        getServerSocket();


        RuntimeDebugModelXmlSerializer serializer = new MyRuntimeDebugModelXmlSerializer();
        client = new DebugClient(serializer);

        UUID measureUuid = UUID.randomUUID();

        rawMode = metadataOptions.isRawMode();

        Map<String, URI> uriListByKey = Utils.readMetadata(metadataOptions, coverageData);

        try {
            startSystem(measureUuid);
        } catch (RuntimeDebugClientException e) {
            logger.info("Connecting to dbgs failed");
            logger.error(e.getLocalizedMessage());
            result = CommandLine.ExitCode.SOFTWARE;
            return result;
        }

        addShutdownHook();

        Set<String> externalDataProcessorsUriSet = new HashSet<>();

        try {
            mainLoop(uriListByKey, externalDataProcessorsUriSet);
        } catch (RuntimeDebugClientException e) {
            logger.error("Can't send ping to debug server. Coverage analyzing finished");
            logger.error(e.getLocalizedMessage());
            e.printStackTrace();
            result = CommandLine.ExitCode.SOFTWARE;
        }
        Thread.sleep(debuggerOptions.getPingTimeout());

        shutdown();
        return result;
    }

    private void shutdown() throws IOException {
        if (opid > 0 && !Utils.isProcessStillAlive(opid)) {
            logger.info("Owner process stopped: {}", opid);
        }

        gracefulShutdown(null);

        logger.info("Disconnecting from dbgs...");
        try {
            client.disconnect();
            client.dispose();
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        }
        closeSocket();

        logger.info("Main thread finished");
        stopExecution.set(true);
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shutdown();
            } catch (IOException e) {
                logger.info("Shutdown error.");
                e.printStackTrace();
            }
        }));
    }

    private void targetStarted(DBGUIExtCmdInfoStartedImpl command) {
        DebugTargetId targetId = command.getTargetID();
        try {
            client.attachRuntimeDebugTargets(Collections.singletonList(UUID.fromString(targetId.getId())));
        } catch (RuntimeDebugClientException e) {
            logger.info("Command: {} error!", command.getCmdID().getName());
            logger.error(e.getLocalizedMessage());
        }
    }

    private void mainLoop(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet) throws RuntimeDebugClientException {
        while (!stopExecution.get()) {
            List<? extends DBGUIExtCmdInfoBase> commandsList = client.ping();
            logger.info("Ping result commands size: {}", commandsList.size());
            commandsList.forEach(command -> {
                logger.info("Command: {}", command.getCmdID().getName());
                if (command.getCmdID() == DBGUIExtCmds.MEASURE_RESULT_PROCESSING) {
                    measureResultProcessing(uriListByKey, externalDataProcessorsUriSet, (DBGUIExtCmdInfoMeasureImpl) command);
                } else if (command.getCmdID() == DBGUIExtCmds.TARGET_STARTED) {
                    targetStarted((DBGUIExtCmdInfoStartedImpl) command);
                }
            });
        }
    }

    private void measureResultProcessing(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet, DBGUIExtCmdInfoMeasureImpl command) {
        logger.info("Found MEASURE_RESULT_PROCESSING command");
        PerformanceInfoMain measure = command.getMeasure();
        EList<PerformanceInfoModule> moduleInfoList = measure.getModuleData();
        moduleInfoList.forEach(moduleInfo -> {
            BSLModuleIdInternal moduleId = moduleInfo.getModuleID();
            String moduleUrl = moduleId.getURL();
            if (loggingOptions.isVerbose() && !moduleUrl.isEmpty() && !externalDataProcessorsUriSet.contains(moduleUrl)) {
                logger.info("Found external data processor: {}", moduleUrl);
                externalDataProcessorsUriSet.add(moduleUrl);
            }
            String moduleExtensionName = moduleId.getExtensionName();
            if (filterOptions.getExtensionName().equals(moduleExtensionName)
                    && filterOptions.getExternalDataProcessorUrl().equals(moduleUrl)) {
                String objectId = moduleId.getObjectID();
                String propertyId = moduleId.getPropertyID();
                String key = Utils.getUriKey(objectId, propertyId);

                URI uri;
                if (!rawMode) {
                    uri = uriListByKey.get(key);
                } else {
                    uri = URI.create("file:///" + key);
                }
                if (uri == null) {
                    logger.info("Couldn't find object id {}, property id {} in sources!", objectId, propertyId);
                } else {
                    EList<PerformanceInfoLine> lineInfoList = moduleInfo.getLineInfo();
                    lineInfoList.forEach(lineInfo -> {
                        BigDecimal lineNo = lineInfo.getLineNo();
                        Map<BigDecimal, Integer> coverMap = coverageData.get(uri);
                        if (!coverMap.isEmpty() || rawMode) {
                            if (!rawMode && !coverMap.containsKey(lineNo)) {
                                if (loggingOptions.isVerbose()) {
                                    logger.info("Can't find line to cover {} in module {}", lineNo, uri);
                                    try {
                                        Stream<String> all_lines = Files.lines(Paths.get(uri));
                                        Optional<String> first = all_lines.skip(lineNo.longValue() - 1).findFirst();
                                        if (first.isPresent()) {
                                            String specific_line_n = first.get();
                                            logger.info(">>> {}", specific_line_n);
                                        }
                                    } catch (Exception e) {
                                        logger.error(e.getLocalizedMessage());
                                    }
                                }
                            } else {
                                int currentValue = coverMap.getOrDefault(lineNo, 0);
                                if (currentValue < 0) {
                                    currentValue = 0;
                                }
                                coverMap.put(lineNo,
                                        currentValue
                                                + lineInfo.getFrequency().intValue());
                            }
                        }
                    });
                }
            }
        });
    }

    private void startSystem(UUID measureUuid) throws RuntimeDebugClientException {
        UUID debugServerUuid = UUID.randomUUID();
        client.configure(
                connectionOptions.getDebugServerUrl(),
                debugServerUuid,
                connectionOptions.getInfobaseAlias());
        logger.info("Connecting to debugger...");
        AttachDebugUIResult connectionResult = client.connect(debuggerOptions.getPassword());
        if (connectionResult != AttachDebugUIResult.REGISTERED) {
            if (connectionResult == AttachDebugUIResult.IB_IN_DEBUG) {
                throw new RuntimeDebugClientException("Can't connect to debug server. IB is in debug. Close configurator or EDT first");
            } else if (connectionResult == AttachDebugUIResult.CREDENTIALS_REQUIRED) {
                throw new RuntimeDebugClientException("Can't connect to debug server. Use -p option to set correct password");
            } else {
                throw new RuntimeDebugClientException("Can't connect to debug server. Connection result: " + connectionResult);
            }
        }
        Version apiver =  Version.parse(client.getApiVersion());
        logger.info("Setup settings...");
        client.initSettings(false);
        client.setAutoconnectDebugTargets(
                debuggerOptions.getDebugAreaNames(),
                debuggerOptions.getFilteredAutoconnectTargets(apiver));
        logger.info("Setup targets...");
        List<DebugTargetId> debugTargets;
        if (debuggerOptions.getDebugAreaNames().isEmpty()) {
            debugTargets = client.getRuntimeDebugTargets(null);
        } else {
            debugTargets = new LinkedList<>();
            debuggerOptions.getDebugAreaNames().forEach(areaName -> {
                try {
                    debugTargets.addAll(client.getRuntimeDebugTargets(areaName));
                } catch (RuntimeDebugClientException ex) {
                    logger.error(ex.getLocalizedMessage());
                }
            });
        }
        connectAllTargets(debugTargets);

        logger.info("Enabling profiling...");
        client.toggleProfiling(null);
        client.toggleProfiling(measureUuid);

        systemStarted = true;

    }

    protected void gracefulShutdown(PrintWriter serverPipeOut) {
        if (stopExecution.get()) {
            return;
        }

        logger.info("Disabling profiling...");
        try {
            client.toggleProfiling(null);
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        }

        Utils.dumpCoverageFile(coverageData, metadataOptions, outputOptions);
        if(serverPipeOut!=null) {
            serverPipeOut.println(PipeMessages.OK_RESULT);
        }
        stopExecution.set(true);

        logger.info("Bye!");
    }

    @Override
    protected MetadataOptions getMetadataOptions() {
        return metadataOptions;
    }

    @Override
    protected Map<URI, Map<BigDecimal, Integer>> getCoverageData() {
        return coverageData;
    }

    @Override
    protected OutputOptions getOutputOptions() {
        return outputOptions;
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
