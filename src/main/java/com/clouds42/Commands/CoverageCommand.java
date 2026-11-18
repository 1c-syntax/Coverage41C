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
import com.clouds42.CoverageHits;
import com.clouds42.DebugClient;
import com.clouds42.IncludeUsers;
import com.clouds42.UserTargets;
import com.clouds42.MyRuntimeDebugModelXmlSerializer;
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import com.clouds42.SourceKey;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.lang.module.ModuleDescriptor.Version;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    @Option(names = "--include-user", paramLabel = "USER",
            description = "Collect measures only from debug targets of this infobase user (exact name); "
                    + "repeat for several users. Replaces includeUsers of --config")
    private List<String> includeUserNames = new ArrayList<>();

    private DebugClient client;
    // Debug targets and their users; decides what to attach and which measures to merge.
    private UserTargets targets;

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


    // Coverage right after reading sources: clean restores it, so auto lines stay optional.
    private final Map<URI, Map<BigDecimal, Integer>> initialCoverageData = new HashMap<>();

    private final AtomicBoolean stopExecution = new AtomicBoolean(false);
    private final AtomicBoolean shutDown = new AtomicBoolean(false);
    private volatile boolean coverageWritten = true;
    private boolean rawMode = false;
    private volatile boolean systemStarted = false;
    private volatile boolean mainLoopRunning = false;
    private final AtomicLong pingsProcessed = new AtomicLong();

    // How long stop waits for the measures the platform sends after profiling is off.
    private static final long FINAL_MEASURES_TIMEOUT_SECONDS = 10;

    private void connectAllTargets(List<DebugTargetId> debugTargets) {
        logger.info("Current debug targets size: {}", debugTargets.size());
        debugTargets.forEach(debugTarget -> {
            if (!targets.register(debugTarget)) {
                return;
            }
            try {
                client.attachRuntimeDebugTargets(Collections.singletonList(UUID.fromString(debugTarget.getId())));
                targets.attached(debugTarget.getId());
            } catch (RuntimeDebugClientException e) {
                logger.error(e.getLocalizedMessage());
            }
        });
    }

    // A measure came from a target dbgs never announced: ask for the list once more, attach new allowed targets.
    private void refreshTargets(String unknownTargetId) {
        logger.info("Refreshing debug targets for unknown target {}", unknownTargetId);
        try {
            connectAllTargets(listDebugTargets());
        } catch (RuntimeDebugClientException e) {
            logger.error("Can't list debug targets: {}", e.getLocalizedMessage());
        }
        targets.markUnknown(unknownTargetId);
    }

    @Override
    public Integer call() throws Exception {

        int result = CommandLine.ExitCode.OK;
        metadataOptions.validateSourceFilters(filterOptions.getExtensionName(), filterOptions.getExternalDataProcessorUrl());
        IncludeUsers includeUsers = IncludeUsers.load(metadataOptions.getConfigFile(), includeUserNames);
        targets = new UserTargets(includeUsers);
        if (includeUsers.isActive()) {
            logger.info(includeUsers.describe());
        }
        getServerSocket();


        RuntimeDebugModelXmlSerializer serializer = new MyRuntimeDebugModelXmlSerializer();
        client = new DebugClient(serializer);

        UUID measureUuid = UUID.randomUUID();

        rawMode = metadataOptions.isRawMode();

        Map<String, URI> uriListByKey;
        try {
            uriListByKey = Utils.readMetadata(metadataOptions, filterOptions.getExtensionName(), coverageData, true);
        } catch (Exception e) {
            client.dispose();
            closeSocket();
            throw e;
        }
        coverageData.forEach((uri, coverMap) -> initialCoverageData.put(uri, new HashMap<>(coverMap)));

        try {
            startSystem(measureUuid);
        } catch (RuntimeDebugClientException e) {
            logger.info("Connecting to dbgs failed");
            logger.error(e.getLocalizedMessage());
            client.dispose();
            closeSocket();
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
        return coverageWritten && targets.matchedAnyTarget() ? result : CommandLine.ExitCode.SOFTWARE;
    }

    // Runs at the end of call and again from the shutdown hook.
    private synchronized void shutdown() throws IOException {
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        if (opid > 0 && !Utils.isProcessStillAlive(opid)) {
            logger.info("Owner process stopped: {}", opid);
        }

        gracefulShutdown(null);

        targets.summary().forEach(logger::info);
        if (!targets.matchedAnyTarget()) {
            logger.error(targets.noMatchMessage());
        }
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
        if (!targets.register(targetId)) {
            return;
        }
        try {
            client.attachRuntimeDebugTargets(Collections.singletonList(UUID.fromString(targetId.getId())));
            targets.attached(targetId.getId());
        } catch (RuntimeDebugClientException e) {
            logger.info("Command: {} error!", command.getCmdID().getName());
            logger.error(e.getLocalizedMessage());
        }
    }

    private void mainLoop(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet) throws RuntimeDebugClientException {
        mainLoopRunning = true;
        try {
            pingLoop(uriListByKey, externalDataProcessorsUriSet);
        } finally {
            mainLoopRunning = false;
        }
    }

    private void pingLoop(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet) throws RuntimeDebugClientException {
        while (!stopExecution.get()) {
            List<? extends DBGUIExtCmdInfoBase> commandsList = client.ping();
            logger.info("Ping result commands size: {}", commandsList.size());
            commandsList.forEach(command -> {
                logger.info("Command: {}", command.getCmdID().getName());
                // One malformed command must not end measuring and lose collected coverage.
                try {
                    if (command.getCmdID() == DBGUIExtCmds.MEASURE_RESULT_PROCESSING) {
                        DBGUIExtCmdInfoMeasureImpl measure = (DBGUIExtCmdInfoMeasureImpl) command;
                        if (acceptsMeasure(measure)) {
                            synchronized (coverageData) {
                                measureResultProcessing(uriListByKey, externalDataProcessorsUriSet, measure);
                            }
                        }
                    } else if (command.getCmdID() == DBGUIExtCmds.TARGET_STARTED) {
                        targetStarted((DBGUIExtCmdInfoStartedImpl) command);
                    } else if (command.getCmdID() == DBGUIExtCmds.TARGET_QUIT) {
                        // The target stays known: its last measures may arrive after this event.
                        DebugTargetId quit = command.getTargetID();
                        logger.info("Target {} quit", quit == null ? null : quit.getId());
                    }
                } catch (RuntimeException e) {
                    logger.error("Can't process command {}: {}", command.getCmdID().getName(), e, e);
                }
            });
            pingsProcessed.incrementAndGet();
        }
    }

    // With includeUsers, a measure is merged only when its target belongs to an allowed user.
    private boolean acceptsMeasure(DBGUIExtCmdInfoMeasureImpl measure) {
        // Look the target up before the verdict, so that every packet is counted and logged once.
        if (targets.needsLookup(measure)) {
            refreshTargets(UserTargets.targetIdOf(measure));
        }
        return targets.verdict(measure, loggingOptions.isVerbose()) == UserTargets.Verdict.ACCEPTED;
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
            String moduleExtensionName = Objects.requireNonNullElse(moduleId.getExtensionName(), "");
            if (rawMode || (metadataOptions.usesNamedSources() && moduleUrl.isEmpty())
                    || (moduleUrl.isEmpty() && metadataOptions.hasExtensionSources(moduleExtensionName))
                    || (filterOptions.getExtensionName().equals(moduleExtensionName)
                    && filterOptions.getExternalDataProcessorUrl().equals(moduleUrl))) {
                String objectId = moduleId.getObjectID();
                String propertyId = moduleId.getPropertyID();
                String key = metadataOptions.hasMultipleSources()
                        ? SourceKey.of(objectId, propertyId, moduleExtensionName, moduleUrl)
                        : Utils.getUriKey(objectId, propertyId);

                URI uri;
                if (!rawMode) {
                    uri = uriListByKey.get(key);
                } else {
                    uri = Utils.getRawUri(moduleExtensionName, moduleUrl, key);
                }
                if (uri == null) {
                    logger.info("Couldn't find object id {}, property id {} in sources!", objectId, propertyId);
                } else {
                    EList<PerformanceInfoLine> lineInfoList = moduleInfo.getLineInfo();
                    lineInfoList.forEach(lineInfo -> {
                        BigDecimal lineNo = lineInfo.getLineNo();
                        Map<BigDecimal, Integer> coverMap = coverageData.get(uri);
                        if (!CoverageHits.add(coverMap, lineNo, lineInfo.getFrequency().intValue(), rawMode)) {
                            if (loggingOptions.isVerbose()) {
                                logger.info("Can't find line to cover {} in module {}", lineNo, uri);
                                try (Stream<String> lines = Files.lines(Paths.get(uri))) {
                                    Optional<String> first = lines.skip(lineNo.longValue() - 1).findFirst();
                                    if (first.isPresent()) {
                                        String specific_line_n = first.get();
                                        logger.info(">>> {}", specific_line_n);
                                    }
                                } catch (Exception e) {
                                    logger.error(e.getLocalizedMessage());
                                }
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
        Version apiver = Version.parse(client.getApiVersion());
        logger.info("Setup settings...");
        client.initSettings(false);
        client.setAutoconnectDebugTargets(
                debuggerOptions.getDebugAreaNames(),
                debuggerOptions.getFilteredAutoconnectTargets(apiver));
        logger.info("Setup targets...");
        connectAllTargets(listDebugTargets());

        logger.info("Enabling profiling...");
        client.toggleProfiling(null);
        client.toggleProfiling(measureUuid);

        systemStarted = true;

    }

    private List<DebugTargetId> listDebugTargets() throws RuntimeDebugClientException {
        if (debuggerOptions.getDebugAreaNames().isEmpty()) {
            return client.getRuntimeDebugTargets(null);
        }
        List<DebugTargetId> debugTargets = new LinkedList<>();
        debuggerOptions.getDebugAreaNames().forEach(areaName -> {
            try {
                debugTargets.addAll(client.getRuntimeDebugTargets(areaName));
            } catch (RuntimeDebugClientException ex) {
                logger.error(ex.getLocalizedMessage());
            }
        });
        return debugTargets;
    }

    protected synchronized void gracefulShutdown(PrintWriter serverPipeOut) {
        if (!stopExecution.get()) {
            try {
                if (serverPipeOut != null && mainLoopRunning) {
                    disableProfiling();
                    awaitFinalMeasures();
                    coverageWritten = dumpCoverage();
                } else {
                    // Shutdown hook or lost debug server: the process may be killed soon, write what is collected.
                    coverageWritten = dumpCoverage();
                    disableProfiling();
                }
            } finally {
                stopExecution.set(true);
            }
            logger.info("Bye!");
        }
        if (serverPipeOut != null) {
            // stop fails when the filter matched no target: a typo in a user name must not look like a clean run.
            serverPipeOut.println(coverageWritten && targets.matchedAnyTarget()
                    ? PipeMessages.OK_RESULT : PipeMessages.FAIL_RESULT);
        }
    }

    private void disableProfiling() {
        logger.info("Disabling profiling...");
        try {
            client.toggleProfiling(null);
        } catch (RuntimeDebugClientException | RuntimeException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    // The platform sends the last measures in a ping after profiling is off. A ping already in progress
    // may miss them, so wait until two more pings are processed.
    private void awaitFinalMeasures() {
        long target = pingsProcessed.get() + 2;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FINAL_MEASURES_TIMEOUT_SECONDS);
        while (pingsProcessed.get() < target && mainLoopRunning && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    protected void cleanCoverageData() {
        synchronized (coverageData) {
            coverageData.forEach((uri, coverMap) -> {
                Map<BigDecimal, Integer> initial = initialCoverageData.getOrDefault(uri, Map.of());
                coverMap.replaceAll((lineNo, hits) -> initial.getOrDefault(lineNo, 0));
            });
        }
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
