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
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoMeasure;
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
import java.util.function.LongSupplier;
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
    private final LongSupplier targetLookupClock;

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
    private boolean collectingFinalMeasures;
    private volatile boolean finalCollectionIncomplete;
    private boolean rawMode = false;
    private volatile boolean systemStarted = false;
    private volatile boolean mainLoopRunning = false;
    private volatile Long finalPollDeadlineNanos;
    private int quietFinalPolls; // Guarded by coverageData.

    // How long stop waits for the measures the platform sends after profiling is off.
    private static final long FINAL_MEASURES_TIMEOUT_SECONDS = 10;
    private static final int REQUIRED_QUIET_FINAL_POLLS = 2;
    private static final long FAILED_LOOKUP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private long targetLookupAllowedAt = System.nanoTime();

    public CoverageCommand() {
        this(System::nanoTime);
    }

    public CoverageCommand(LongSupplier targetLookupClock) {
        this.targetLookupClock = targetLookupClock;
    }

    private void connectAllTargets(List<DebugTargetId> debugTargets) {
        logger.info("Current debug targets size: {}", debugTargets.size());
        debugTargets.forEach(this::connectTarget);
    }

    private boolean tryRefreshTargetList(String triggerId) {
        if (System.nanoTime() - targetLookupAllowedAt < 0) {
            return false;
        }
        logger.info("Refreshing debug targets for unknown target {}", triggerId);
        try {
            if (connectListedTargets()) {
                return true;
            }
        } catch (RuntimeDebugClientException e) {
            logger.error("Can't list debug targets: {}", e.getLocalizedMessage());
        }
        targetLookupAllowedAt = System.nanoTime() + FAILED_LOOKUP_INTERVAL_NANOS;
        return false;
    }

    @Override
    public Integer call() throws Exception {

        int result = CommandLine.ExitCode.OK;
        metadataOptions.validateSourceFilters(filterOptions.getExtensionName(), filterOptions.getExternalDataProcessorUrl());
        IncludeUsers includeUsers = IncludeUsers.load(metadataOptions.getConfigFile(), includeUserNames);
        targets = new UserTargets(includeUsers, targetLookupClock);
        if (includeUsers.isActive()) {
            logger.info(includeUsers.describe());
        }
        startListening();

        Map<String, URI> uriListByKey;
        try {
            RuntimeDebugModelXmlSerializer serializer = new MyRuntimeDebugModelXmlSerializer();
            client = new DebugClient(serializer);
            rawMode = metadataOptions.isRawMode();
            uriListByKey = Utils.readMetadata(metadataOptions, filterOptions.getExtensionName(),
                    filterOptions.getExternalDataProcessorUrl(), coverageData, true, includeUsers.isActive(),
                    outputOptions.getOutputFile());
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

    private void connectTarget(DebugTargetId target) {
        String id = target == null ? null : target.getId();
        if (id == null || id.isBlank()) {
            logger.warn("Debug target without id skipped");
            return;
        }
        if (!UserTargets.isValidTargetUuid(id)) {
            logger.warn("Debug target with invalid UUID skipped");
            return;
        }
        if (!targets.register(target)) {
            return;
        }
        try {
            client.attachRuntimeDebugTargets(Collections.singletonList(UUID.fromString(id)));
            targets.attached(id);
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    private void mainLoop(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet)
            throws RuntimeDebugClientException, InterruptedException {
        mainLoopRunning = true;
        systemStarted = true;
        try {
            pingLoop(uriListByKey, externalDataProcessorsUriSet);
        } finally {
            synchronized (coverageData) {
                mainLoopRunning = false;
                coverageData.notifyAll();
            }
        }
    }

    private void pingLoop(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet)
            throws RuntimeDebugClientException, InterruptedException {
        while (!stopExecution.get()) {
            // Capture eligibility before the request. An in-flight poll may predate profiling disable.
            Long drainDeadline = finalPollDeadlineNanos;
            Optional<List<? extends DBGUIExtCmdInfoBase>> poll;
            try {
                poll = client.pollDebugServer();
                if (poll.isEmpty()) {
                    markFinalCollectionIncomplete();
                    logger.debug("Ignoring an unrecognized debugger poll response");
                }
            } catch (RuntimeDebugClientException e) {
                if (!markFinalCollectionIncomplete()) {
                    if (stopExecution.get()) {
                        return;
                    }
                    throw e;
                }
                logger.debug("Final debugger poll failed: {}", e.getLocalizedMessage());
                poll = Optional.empty();
            }
            if (poll.isEmpty()) {
                if (shouldStopAfterFinalPoll(drainDeadline, false)) {
                    return;
                }
                Thread.sleep(Math.max(10, debuggerOptions.getPingTimeout()));
                continue;
            }
            List<? extends DBGUIExtCmdInfoBase> commandsList = poll.get();
            logger.info("Ping result commands size: {}", commandsList.size());
            boolean refreshAttempted = false;
            boolean listAvailable = false;
            boolean hasMeasures = false;
            for (DBGUIExtCmdInfoBase command : commandsList) {
                if (stopExecution.get()) {
                    return;
                }
                DBGUIExtCmds commandType = command.getCmdID();
                boolean measure = commandType == DBGUIExtCmds.MEASURE_RESULT_PROCESSING
                        || command instanceof DBGUIExtCmdInfoMeasure;
                hasMeasures |= measure;
                logger.info("Command: {}", commandType.getName());
                // One malformed command must not discard later measurements.
                try {
                    if (measure) {
                        if (commandType != DBGUIExtCmds.MEASURE_RESULT_PROCESSING
                                || !(command instanceof DBGUIExtCmdInfoMeasure measurement)) {
                            throw new IllegalArgumentException("Debugger measurement command and payload type differ");
                        }
                        String targetId = targets.isActive() ? UserTargets.targetIdOf(measurement) : null;
                        if (targetId != null && !UserTargets.isValidTargetUuid(targetId)) {
                            throw new IllegalArgumentException("Debugger measurement has an invalid target UUID");
                        }
                        if (targets.needsLookup(measurement)) {
                            if (!refreshAttempted) {
                                refreshAttempted = true;
                                listAvailable = tryRefreshTargetList(targetId);
                            }
                            if (listAvailable) {
                                targets.markUnknown(targetId);
                            }
                        }
                        UserTargets.Verdict verdict = targets.verdict(measurement, loggingOptions.isVerbose());
                        if (verdict == UserTargets.Verdict.ACCEPTED) {
                            measureResultProcessing(uriListByKey, externalDataProcessorsUriSet, measurement);
                        } else if (verdict == UserTargets.Verdict.UNKNOWN) {
                            markFinalCollectionIncomplete();
                        }
                    } else if (commandType == DBGUIExtCmds.TARGET_STARTED) {
                        connectTarget(((DBGUIExtCmdInfoStartedImpl) command).getTargetID());
                    } else if (commandType == DBGUIExtCmds.TARGET_QUIT) {
                        // The target stays known: its last measures may arrive after this event.
                        DebugTargetId quit = command.getTargetID();
                        logger.info("Target {} quit", quit == null ? null : quit.getId());
                    }
                } catch (RuntimeException e) {
                    if (measure) {
                        reportMeasureFailure(e);
                    } else {
                        logger.error("Can't process command {}: {}", commandType.getName(), e, e);
                    }
                }
            }
            if (shouldStopAfterFinalPoll(drainDeadline, !hasMeasures)) {
                return;
            }
        }
    }

    private boolean shouldStopAfterFinalPoll(Long deadline, boolean quiet) {
        if (deadline == null) {
            return false;
        }
        synchronized (coverageData) {
            if (stopExecution.get() || System.nanoTime() - deadline >= 0) {
                return true;
            }
            quietFinalPolls = quiet ? quietFinalPolls + 1 : 0;
            return quietFinalPolls >= REQUIRED_QUIET_FINAL_POLLS;
        }
    }

    private void measureResultProcessing(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet,
                                         DBGUIExtCmdInfoMeasure command) {
        synchronized (coverageData) {
            if (stopExecution.get()) {
                return;
            }
            logger.info("Found MEASURE_RESULT_PROCESSING command");
            PerformanceInfoMain measure = command.getMeasure();
            if (measure == null) {
                reportMeasureFailure(new IllegalArgumentException("Missing measurement data"));
                return;
            }
            for (PerformanceInfoModule moduleInfo : measure.getModuleData()) {
                try {
                    mergeMeasuredModule(uriListByKey, externalDataProcessorsUriSet, moduleInfo);
                } catch (RuntimeException e) {
                    reportMeasureFailure(e);
                }
            }
        }
    }

    private void reportMeasureFailure(RuntimeException failure) {
        markFinalCollectionIncomplete();
        logger.error("Can't process measure: {}", failure.getMessage(), failure);
    }

    private boolean markFinalCollectionIncomplete() {
        synchronized (coverageData) {
            if (!collectingFinalMeasures || stopExecution.get()) {
                return false;
            }
            finalCollectionIncomplete = true;
            return true;
        }
    }

    private void mergeMeasuredModule(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet,
                                     PerformanceInfoModule moduleInfo) {
        BSLModuleIdInternal moduleId = Objects.requireNonNull(moduleInfo.getModuleID(), "Missing module identifier");
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
            String moduleKey = SourceKey.moduleKey(objectId, propertyId);
            String key = metadataOptions.hasMultipleSources()
                    ? SourceKey.qualify(moduleKey, moduleExtensionName, moduleUrl)
                    : moduleKey;

            URI uri;
            if (!rawMode) {
                uri = uriListByKey.get(key);
            } else {
                uri = Utils.getRawUri(moduleExtensionName, moduleUrl, key);
                if (SourceKey.isQualified(moduleKey) || !("/" + key).equals(uri.getPath())) {
                    throw new IllegalArgumentException("Invalid module identifier for RAW coverage");
                }
            }
            if (uri == null) {
                logger.info("Couldn't find object id {}, property id {} in sources!", objectId, propertyId);
                return;
            }
            for (PerformanceInfoLine lineInfo : moduleInfo.getLineInfo()) {
                try {
                    mergeMeasuredLine(uri, lineInfo);
                } catch (RuntimeException e) {
                    reportMeasureFailure(e);
                }
            }
        }
    }

    private void mergeMeasuredLine(URI uri, PerformanceInfoLine lineInfo) {
        BigDecimal lineNo = CoverageHits.lineNumber(lineInfo.getLineNo());
        int hits = CoverageHits.hitCount(lineInfo.getFrequency());
        Map<BigDecimal, Integer> coverMap = coverageData.get(uri);
        if (CoverageHits.add(coverMap, lineNo, hits, rawMode) || !loggingOptions.isVerbose()) {
            return;
        }
        logger.info("Can't find line to cover {} in module {}", lineNo, uri);
        try (Stream<String> lines = Files.lines(Paths.get(uri))) {
            lines.skip(lineNo.longValue() - 1).findFirst()
                    .ifPresent(line -> logger.info(">>> {}", line));
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage());
        }
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
                closeCollectionCommands();
                if (serverPipeOut != null && mainLoopRunning) {
                    synchronized (coverageData) {
                        // Include measurements returned by pings already in flight.
                        collectingFinalMeasures = true;
                    }
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
                    finishCollection();
                } else {
                    // The process may be killed soon. Save what was collected before contacting dbgs.
                    finishCollection();
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

    private void finishCollection() {
        synchronized (coverageData) {
            stopExecution.set(true);
            if (finalCollectionIncomplete) {
                logger.warn("Final collection is incomplete; writing the measurements received");
            }
            coverageWritten = dumpCoverage();
        }
    }

    private boolean collectionSucceeded() {
        return profilingFinished && !finalCollectionIncomplete && coverageWritten && targets.matchedAnyTarget();
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

    // The platform can spread the last measures over multiple polls after profiling is off.
    private boolean awaitFinalMeasures() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FINAL_MEASURES_TIMEOUT_SECONDS);
        synchronized (coverageData) {
            finalPollDeadlineNanos = deadline;
            while (mainLoopRunning && quietFinalPolls < REQUIRED_QUIET_FINAL_POLLS) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(coverageData, remaining);
            }
            if (quietFinalPolls >= REQUIRED_QUIET_FINAL_POLLS) {
                return true;
            }
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
