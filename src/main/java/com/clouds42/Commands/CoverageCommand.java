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
    private volatile boolean profilingFinished = true;
    private boolean rawMode = false;
    private volatile boolean systemStarted = false;
    private volatile boolean mainLoopRunning = false;
    private final AtomicLong pingsProcessed = new AtomicLong();

    // How long stop waits for the measures the platform sends after profiling is off.
    private static final long FINAL_MEASURES_TIMEOUT_SECONDS = 10;
    private static final long FAILED_LOOKUP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private long targetLookupAllowedAt = System.nanoTime();

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

    // Refresh incomplete target information and attach any newly recognized allowed targets.
    private void refreshTargets(String unknownTargetId) {
        if (System.nanoTime() - targetLookupAllowedAt < 0) {
            return;
        }
        logger.info("Refreshing debug targets for unknown target {}", unknownTargetId);
        try {
            if (connectListedTargets()) {
                targets.markUnknown(unknownTargetId);
                return;
            }
        } catch (RuntimeDebugClientException e) {
            logger.error("Can't list debug targets: {}", e.getLocalizedMessage());
        }
        targetLookupAllowedAt = System.nanoTime() + FAILED_LOOKUP_INTERVAL_NANOS;
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
        startListening();

        Map<String, URI> uriListByKey;
        try {
            RuntimeDebugModelXmlSerializer serializer = new MyRuntimeDebugModelXmlSerializer();
            client = new DebugClient(serializer);
            rawMode = metadataOptions.isRawMode();
            uriListByKey = Utils.readMetadata(metadataOptions, filterOptions.getExtensionName(), coverageData,
                    true, includeUsers.isActive());
            coverageData.forEach((uri, coverMap) -> initialCoverageData.put(uri, new HashMap<>(coverMap)));
            startSystem(UUID.randomUUID());
        } catch (Exception e) {
            try {
                closeSocket();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            } finally {
                disposeClient();
            }
            if (e instanceof RuntimeDebugClientException) {
                logger.info("Connecting to dbgs failed");
                logger.error(e.getLocalizedMessage());
                return CommandLine.ExitCode.SOFTWARE;
            }
            throw e;
        }

        addShutdownHook();

        Set<String> externalDataProcessorsUriSet = new HashSet<>();
        try {
            try {
                mainLoop(uriListByKey, externalDataProcessorsUriSet);
            } catch (RuntimeDebugClientException e) {
                logger.error("Can't send ping to debug server. Coverage analyzing finished", e);
                result = CommandLine.ExitCode.SOFTWARE;
            }
            Thread.sleep(debuggerOptions.getPingTimeout());
        } finally {
            shutdown();
        }
        return collectionSucceeded() ? result : CommandLine.ExitCode.SOFTWARE;
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
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        } finally {
            try {
                disposeClient();
            } finally {
                closeSocket();
            }
        }

        logger.info("Main thread finished");
        stopExecution.set(true);
    }

    private void disposeClient() {
        if (client != null) {
            try {
                client.dispose();
            } catch (RuntimeDebugClientException e) {
                logger.error(e.getLocalizedMessage());
            }
        }
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
        systemStarted = true;
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
                if (stopExecution.get()) {
                    return;
                }
                logger.info("Command: {}", command.getCmdID().getName());
                // One malformed command must not end measuring and lose collected coverage.
                try {
                    if (command.getCmdID() == DBGUIExtCmds.MEASURE_RESULT_PROCESSING) {
                        DBGUIExtCmdInfoMeasureImpl measure = (DBGUIExtCmdInfoMeasureImpl) command;
                        if (acceptsMeasure(measure)) {
                            synchronized (coverageData) {
                                if (!stopExecution.get()) {
                                    measureResultProcessing(uriListByKey, externalDataProcessorsUriSet, measure);
                                }
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
        connectListedTargets();

        logger.info("Enabling profiling...");
        client.toggleProfiling(null);
        client.toggleProfiling(measureUuid);
    }

    private boolean connectListedTargets() throws RuntimeDebugClientException {
        if (debuggerOptions.getDebugAreaNames().isEmpty()) {
            connectAllTargets(client.getRuntimeDebugTargets(null));
            return true;
        }
        boolean complete = true;
        List<DebugTargetId> debugTargets = new LinkedList<>();
        for (String areaName : debuggerOptions.getDebugAreaNames()) {
            try {
                debugTargets.addAll(client.getRuntimeDebugTargets(areaName));
            } catch (RuntimeDebugClientException ex) {
                complete = false;
                logger.error(ex.getLocalizedMessage());
            }
        }
        connectAllTargets(debugTargets);
        return complete;
    }

    @Override
    protected synchronized void gracefulShutdown(PrintWriter serverPipeOut) {
        boolean interrupted = false;
        try {
            if (!stopExecution.get()) {
                if (serverPipeOut != null && mainLoopRunning) {
                    profilingFinished = disableProfiling();
                    if (profilingFinished) {
                        try {
                            profilingFinished = awaitFinalMeasures();
                        } catch (InterruptedException e) {
                            logger.error("Interrupted while waiting for final measures");
                            profilingFinished = false;
                            interrupted = true;
                        }
                    }
                    stopExecution.set(true);
                    coverageWritten = dumpCoverage();
                } else {
                    // The process may be killed soon. Save what was collected before contacting dbgs.
                    stopExecution.set(true);
                    coverageWritten = dumpCoverage();
                    profilingFinished = disableProfiling() && serverPipeOut == null;
                }
                logger.info("Bye!");
            }
            if (serverPipeOut != null) {
                serverPipeOut.println(collectionSucceeded() ? PipeMessages.OK_RESULT : PipeMessages.FAIL_RESULT);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean collectionSucceeded() {
        return profilingFinished && coverageWritten && targets.matchedAnyTarget();
    }

    private boolean disableProfiling() {
        logger.info("Disabling profiling...");
        try {
            client.toggleProfiling(null);
            return true;
        } catch (RuntimeDebugClientException | RuntimeException e) {
            logger.error("Can't disable profiling: {}", e.getLocalizedMessage());
            return false;
        }
    }

    // The platform sends the last measures in a ping after profiling is off. A ping already in progress
    // may miss them, so wait until two more pings are processed.
    private boolean awaitFinalMeasures() throws InterruptedException {
        long target = pingsProcessed.get() + 2;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FINAL_MEASURES_TIMEOUT_SECONDS);
        while (System.nanoTime() - deadline < 0) {
            if (pingsProcessed.get() >= target) {
                return true;
            }
            if (!mainLoopRunning) {
                break;
            }
            Thread.sleep(20);
        }
        logger.error("Can't finish collecting final measures before the deadline or debugger shutdown");
        return false;
    }

    @Override
    protected void cleanCoverageData() {
        synchronized (coverageData) {
            if (rawMode) {
                coverageData.clear();
                return;
            }
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
