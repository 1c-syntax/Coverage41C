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
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoMeasure;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoQuit;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoStarted;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmds;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoMain;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoLine;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    private final LongSupplier targetLookupClock;
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
    // Guarded by coverageData. Deferred packets belong to the period in which their ping was admitted.
    private long coverageGeneration;

    private final AtomicBoolean stopExecution = new AtomicBoolean(false);
    private final AtomicBoolean shutDown = new AtomicBoolean(false);
    private volatile boolean coverageWritten = true;
    private volatile boolean profilingFinished = true;
    private volatile boolean targetDiscoveryUnconfirmed;
    private volatile boolean targetAttachmentFailed;
    private volatile boolean receivedAcceptedPacket;
    private volatile boolean collectedUsableMeasurement;
    private boolean rawMode = false;
    private volatile boolean systemStarted = false;
    private volatile boolean mainLoopRunning = false;
    private final long finalMeasureWindowNanos;
    private final long finalConfirmationTimeoutNanos;
    private final LongSupplier finalMeasureClock;
    private volatile Long finalMeasureWindowEndNanos;
    // Guarded by coverageData.
    private boolean finalDrainConfirmed;
    private boolean finalConfirmationFailed;
    // Guarded by coverageData, so the final report and result see the same measurement failures.
    private boolean collectingFinalMeasures;
    private boolean finalMeasureFailed;

    private static final Duration FINAL_MEASURE_WINDOW = Duration.ofSeconds(10);
    private static final Duration FINAL_CONFIRMATION_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_FINAL_POLL_RETRY_MILLIS = 50;
    private static final long FAILED_LOOKUP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private long targetLookupAllowedAt = System.nanoTime();

    public CoverageCommand() {
        this(FINAL_MEASURE_WINDOW, FINAL_CONFIRMATION_TIMEOUT);
    }

    public CoverageCommand(LongSupplier targetLookupClock) {
        this(targetLookupClock, FINAL_MEASURE_WINDOW, FINAL_CONFIRMATION_TIMEOUT);
    }

    public CoverageCommand(Duration finalMeasureWindow, Duration finalConfirmationTimeout) {
        this(System::nanoTime, finalMeasureWindow, finalConfirmationTimeout);
    }

    public CoverageCommand(LongSupplier targetLookupClock, Duration finalMeasureWindow, Duration finalConfirmationTimeout) {
        this(targetLookupClock, finalMeasureWindow, finalConfirmationTimeout, System::nanoTime);
    }

    protected CoverageCommand(LongSupplier targetLookupClock, Duration finalMeasureWindow,
                              Duration finalConfirmationTimeout, LongSupplier finalMeasureClock) {
        this.targetLookupClock = targetLookupClock;
        this.finalMeasureWindowNanos = finalMeasureWindow.toNanos();
        this.finalConfirmationTimeoutNanos = finalConfirmationTimeout.toNanos();
        this.finalMeasureClock = finalMeasureClock;
    }

    private void connectAllTargets(List<DebugTargetId> debugTargets) {
        logger.info("Current debug targets size: {}", debugTargets.size());
        debugTargets.forEach(this::connectListedTarget);
    }

    // Return names to resolve packets in this ping. A null result means no successful lookup.
    private Map<String, String> refreshTargets(String unknownTargetId, PendingPoll pending) {
        if (!canSendDebuggerRequest() || System.nanoTime() - targetLookupAllowedAt < 0) {
            return null;
        }
        logger.info("Refreshing debug targets for unknown target {}", unknownTargetId);
        try {
            List<String> areas = debuggerOptions.getDebugAreaNames();
            List<DebugTargetId> listed = client.getRuntimeDebugTargets(areas.size() == 1 ? areas.get(0) : null);
            // With multiple areas, the unscoped list can resolve user names but cannot authorize attachment.
            if (areas.size() <= 1) {
                synchronized (coverageData) {
                    if (!stopExecution.get()) {
                        for (DebugTargetId target : listed) {
                            if (registerListedTarget(target)) {
                                pending.queueAttachment(target);
                            }
                        }
                        targetDiscoveryUnconfirmed = false;
                    }
                }
            }
            return UserTargets.listedUsersById(listed);
        } catch (RuntimeDebugClientException | RuntimeException e) {
            logger.error("Can't list debug targets: {}", e.getLocalizedMessage());
        }
        targetLookupAllowedAt = System.nanoTime() + FAILED_LOOKUP_INTERVAL_NANOS;
        return null;
    }

    @Override
    public Integer call() throws Exception {
        try (measurementReports) {
            return collect();
        }
    }

    private int collect() throws Exception {
        int result = CommandLine.ExitCode.OK;
        metadataOptions.validateSourceFilters(filterOptions.getExtensionName(), filterOptions.getExternalDataProcessorUrl());
        IncludeUsers includeUsers = measurementReports.loadIncludeUsers(metadataOptions.getConfigFile(), includeUserNames);
        targets = new UserTargets(includeUsers, targetLookupClock);
        if (includeUsers.isActive()) {
            logger.info(includeUsers.describe());
        }
        startListening();

        Map<String, URI> uriListByKey;
        try {
            RuntimeDebugModelXmlSerializer serializer = MyRuntimeDebugModelXmlSerializer.forMeasurementCollection();
            client = new DebugClient(serializer);
            rawMode = metadataOptions.isRawMode();
            uriListByKey = readMeasurementMetadata(metadataOptions, filterOptions.getExtensionName(),
                    filterOptions.getExternalDataProcessorUrl(), coverageData, includeUsers.isActive(),
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

        if (targetDiscoveryUnconfirmed) {
            logger.error("Debug target discovery failed and no usable measurements were collected");
        }
        targets.summary().forEach(logger::info);
        if (!targets.matchedAnyTarget()) {
            logger.error(targets.noMatchMessage());
        } else if (!hasConfirmedCollection()) {
            logger.error("No debug target could be attached and no usable measurements were collected");
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

    private void connectListedTarget(DebugTargetId target) {
        if (registerListedTarget(target)) {
            attachTarget(target.getId());
        }
    }

    private boolean registerListedTarget(DebugTargetId target) {
        String id = target == null ? null : target.getId();
        if (id == null || id.isBlank()) {
            logger.warn("Debug target without id skipped");
            return false;
        }
        if (!UserTargets.isValidTargetUuid(id)) {
            logger.warn("Debug target with invalid UUID skipped");
            return false;
        }
        return targets.register(target);
    }

    private void attachTarget(String id) {
        try {
            client.attachRuntimeDebugTargets(Collections.singletonList(UUID.fromString(id)));
            targets.attached(id);
        } catch (RuntimeDebugClientException e) {
            targetAttachmentFailed = true;
            logger.error(e.getLocalizedMessage());
        }
    }

    private void mainLoop(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet)
            throws RuntimeDebugClientException, InterruptedException {
        mainLoopRunning = true;
        systemStarted = true;
        try {
            pingLoop(uriListByKey, externalDataProcessorsUriSet);
        } catch (RuntimeDebugClientException | InterruptedException | RuntimeException error) {
            // Stop serving ready/collection commands before the caller logs or waits to shut down.
            systemStarted = false;
            closeCollectionCommands();
            throw error;
        } finally {
            synchronized (coverageData) {
                mainLoopRunning = false;
                coverageData.notifyAll();
            }
        }
    }

    private void pingLoop(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet)
            throws RuntimeDebugClientException, InterruptedException {
        while (canSendDebuggerRequest()) {
            long pollStarted = finalMeasureClock.getAsLong();
            AtomicLong pollReceived = new AtomicLong(pollStarted);
            Optional<List<? extends DBGUIExtCmdInfoBase>> response;
            try {
                response = client.pingResponse(() -> pollReceived.set(finalMeasureClock.getAsLong()));
            } catch (RuntimeDebugClientException | RuntimeException e) {
                if (!recordFinalMeasureFailure()) {
                    throw e;
                }
                logger.warn("Cannot read final debugger poll", e);
                response = Optional.empty();
            }
            if (response.isEmpty()) {
                logger.debug("Ignoring unexpected ping response from debug server");
                recordFinalMeasureFailure();
                if (shouldStopAfterFinalPoll(pollStarted, pollReceived.get(), false)) {
                    return;
                }
                waitBeforeNextPoll();
                continue;
            }
            List<? extends DBGUIExtCmdInfoBase> commandsList = response.get();
            logger.info("Ping result commands size: {}", commandsList.size());
            boolean hasMeasures = false;
            if (!commandsList.isEmpty()) {
                PendingPoll pending;
                // Admit and merge the local part of this response before stop can take its final snapshot.
                synchronized (coverageData) {
                    if (stopExecution.get()) {
                        return;
                    }
                    pending = new PendingPoll(coverageGeneration);
                    hasMeasures = collectReceivedPoll(commandsList, uriListByKey, externalDataProcessorsUriSet, pending);
                }
                completePendingPoll(uriListByKey, externalDataProcessorsUriSet, pending);
            }
            if (shouldStopAfterFinalPoll(pollStarted, pollReceived.get(), !hasMeasures)) {
                return;
            }
        }
    }

    private static final class PendingPoll {
        private final long generation;
        private final List<DBGUIExtCmdInfoMeasure> unresolvedMeasures = new ArrayList<>();
        private final Map<UUID, DebugTargetId> attachments = new LinkedHashMap<>();
        private final Set<UUID> quitTargets = new HashSet<>();
        private final List<Runnable> diagnostics = new ArrayList<>();

        private PendingPoll(long generation) {
            this.generation = generation;
        }

        private void queueAttachment(DebugTargetId target) {
            UUID key = UUID.fromString(target.getId());
            if (!quitTargets.contains(key)) {
                attachments.putIfAbsent(key, target);
            }
        }

        private void targetStarted(DebugTargetId target) {
            quitTargets.remove(UUID.fromString(target.getId()));
        }

        private void targetQuit(String id) {
            UUID key = UUID.fromString(id);
            quitTargets.add(key);
            attachments.remove(key);
        }
    }

    private boolean collectReceivedPoll(List<? extends DBGUIExtCmdInfoBase> commands,
                                        Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet,
                                        PendingPoll pending) {
        boolean hasMeasures = false;
        for (DBGUIExtCmdInfoBase command : commands) {
            DBGUIExtCmds commandId = null;
            // One malformed command must not discard later measurements.
            try {
                commandId = Objects.requireNonNull(command.getCmdID(), "Missing debugger command ID");
                boolean measurePayload = command instanceof DBGUIExtCmdInfoMeasure;
                boolean measureCommand = commandId == DBGUIExtCmds.MEASURE_RESULT_PROCESSING;
                hasMeasures |= measurePayload || measureCommand;
                logger.info("Command: {}", commandId);
                if (!measurePayload && MyRuntimeDebugModelXmlSerializer.hasInvalidAttribute(command)) {
                    throw new IllegalArgumentException("Unreadable attribute in debugger command");
                }
                if (measurePayload != measureCommand) {
                    throw new IllegalArgumentException("Debugger measure payload does not match the command ID");
                }
                boolean startedCommand = commandId == DBGUIExtCmds.TARGET_STARTED;
                boolean quitCommand = commandId == DBGUIExtCmds.TARGET_QUIT;
                if ((command instanceof DBGUIExtCmdInfoStarted) != startedCommand
                        || (command instanceof DBGUIExtCmdInfoQuit) != quitCommand) {
                    throw new IllegalArgumentException("Debugger target payload does not match the command ID");
                }
                if (measureCommand) {
                    DBGUIExtCmdInfoMeasure measure = (DBGUIExtCmdInfoMeasure) command;
                    String targetId = targets.isActive() ? UserTargets.targetIdOf(measure) : null;
                    if (targetId != null && !UserTargets.isValidTargetUuid(targetId)) {
                        throw new IllegalArgumentException("Debugger measurement has an invalid target UUID");
                    }
                    if (targets.needsLookup(measure)) {
                        pending.unresolvedMeasures.add(measure);
                    } else {
                        mergeByVerdict(uriListByKey, externalDataProcessorsUriSet, measure, pending,
                                targets.verdict(measure, loggingOptions.isVerbose()));
                    }
                } else if (startedCommand || quitCommand) {
                    DebugTargetId target = command.getTargetID();
                    String id = target == null ? null : target.getId();
                    if (!UserTargets.isValidTargetUuid(id)) {
                        recordFinalMeasureFailure();
                        logger.warn("Ignoring debugger command {} with a missing or invalid target UUID", commandId);
                    } else if (startedCommand) {
                        pending.targetStarted(target);
                        if (targets.register(target)) {
                            pending.queueAttachment(target);
                        }
                    } else {
                        // Keep the target's known user for its last measurements.
                        pending.targetQuit(id);
                        logger.info("Target {} quit", id);
                    }
                } else if (commandId == DBGUIExtCmds.UNKNOWN && recordFinalMeasureFailure()) {
                    logger.warn("Ignoring final debugger command with a missing or unknown ID");
                }
            } catch (RuntimeException e) {
                recordFinalMeasureFailure();
                logger.error("Can't process command {}: {}", commandId, e.getMessage(), e);
            }
        }
        return hasMeasures;
    }

    private void completePendingPoll(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet,
                                     PendingPoll pending) {
        if (!pending.unresolvedMeasures.isEmpty()) {
            String firstId = UserTargets.targetIdOf(pending.unresolvedMeasures.get(0));
            Map<String, String> listedUsers = refreshTargets(firstId, pending);
            synchronized (coverageData) {
                if (!stopExecution.get() && pending.generation == coverageGeneration) {
                    for (DBGUIExtCmdInfoMeasure measure : pending.unresolvedMeasures) {
                        String targetId = UserTargets.targetIdOf(measure);
                        if (listedUsers != null) {
                            targets.rememberListedUser(targetId, listedUsers);
                            targets.markUnknown(targetId);
                        }
                        try {
                            mergeByVerdict(uriListByKey, externalDataProcessorsUriSet, measure, pending,
                                    targets.verdictFromLookup(targetId, listedUsers, loggingOptions.isVerbose()));
                        } catch (RuntimeException e) {
                            recordFinalMeasureFailure();
                            logger.error("Can't process deferred measure: {}", e.getMessage(), e);
                        }
                    }
                }
            }
        }
        pending.diagnostics.forEach(Runnable::run);
        for (DebugTargetId target : pending.attachments.values()) {
            if (!canSendDebuggerRequest()) {
                return;
            }
            // A later packet may have supplied a different, authoritative user for this target.
            if (targets.register(target) && canSendDebuggerRequest()) {
                attachTarget(target.getId());
            }
        }
    }

    private boolean canSendDebuggerRequest() {
        Long windowEnd = finalMeasureWindowEndNanos;
        return !stopExecution.get()
                && (windowEnd == null || finalMeasureClock.getAsLong() - windowEnd < finalConfirmationTimeoutNanos);
    }

    private void mergeByVerdict(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet,
                                DBGUIExtCmdInfoMeasure measure, PendingPoll pending, UserTargets.Verdict verdict) {
        if (verdict == UserTargets.Verdict.ACCEPTED) {
            if (!measureResultProcessing(uriListByKey, externalDataProcessorsUriSet, measure, pending)) {
                recordFinalMeasureFailure();
            } else if (!receivedAcceptedPacket) {
                String targetId = UserTargets.targetIdOf(measure);
                receivedAcceptedPacket = targetId == null
                        ? measure.getMeasure().getModuleData().stream().anyMatch(module -> !module.getLineInfo().isEmpty())
                        : UserTargets.isValidTargetUuid(targetId);
            }
        } else if (verdict == UserTargets.Verdict.UNKNOWN) {
            recordFinalMeasureFailure();
        }
    }

    private void waitBeforeNextPoll() throws InterruptedException {
        synchronized (coverageData) {
            if (stopExecution.get()) {
                return;
            }
            long retryDelay = Math.max(10, debuggerOptions.getPingTimeout());
            if (collectingFinalMeasures) {
                retryDelay = Math.min(retryDelay, MAX_FINAL_POLL_RETRY_MILLIS);
            }
            // Stop wakes a live retry wait so it cannot consume the final collection window.
            coverageData.wait(retryDelay);
        }
    }

    private boolean shouldStopAfterFinalPoll(long pollStarted, long pollReceived, boolean quiet) {
        Long windowEnd = finalMeasureWindowEndNanos;
        if (windowEnd == null || finalMeasureClock.getAsLong() - windowEnd < 0) {
            return false;
        }
        synchronized (coverageData) {
            if (stopExecution.get() || finalMeasureClock.getAsLong() - windowEnd >= finalConfirmationTimeoutNanos) {
                return true;
            }
            if (!quiet) {
                // A late batch means more may still be in flight. Processing time does not make a batch late.
                if (pollReceived - windowEnd >= 0) {
                    finalConfirmationFailed = true;
                }
                return false;
            }
            // An empty reply to a request already in flight may predate the end of the window.
            if (finalConfirmationFailed || pollStarted - windowEnd < 0) {
                return false;
            }
            finalDrainConfirmed = true;
            return true;
        }
    }

    private boolean recordFinalMeasureFailure() {
        synchronized (coverageData) {
            if (collectingFinalMeasures && !stopExecution.get()) {
                finalMeasureFailed = true;
            }
            return collectingFinalMeasures;
        }
    }

    private boolean measureResultProcessing(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet,
                                            DBGUIExtCmdInfoMeasure command, PendingPoll pending) {
        logger.info("Found MEASURE_RESULT_PROCESSING command");
        PerformanceInfoMain measure = command.getMeasure();
        boolean complete = !MyRuntimeDebugModelXmlSerializer.hasInvalidAttribute(command);
        for (PerformanceInfoModule moduleInfo : measure.getModuleData()) {
            try {
                complete &= mergeModuleMeasure(uriListByKey, externalDataProcessorsUriSet, moduleInfo, pending);
            } catch (RuntimeException e) {
                logger.error("Can't process module measure", e);
                complete = false;
            }
        }
        return complete;
    }

    private boolean mergeModuleMeasure(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet,
                                       PerformanceInfoModule moduleInfo, PendingPoll pending) {
        BSLModuleIdInternal moduleId = moduleInfo.getModuleID();
        if (moduleId == null) {
            throw new IllegalArgumentException("Missing module ID in debugger measure");
        }
        String moduleUrl = moduleId.getURL();
        if (loggingOptions.isVerbose() && !moduleUrl.isEmpty() && externalDataProcessorsUriSet.add(moduleUrl)) {
            logger.info("Found external data processor: {}", moduleUrl);
        }
        URI uri = measuredModuleUri(uriListByKey, moduleId, moduleUrl);
        Map<BigDecimal, Integer> coverMap = uri == null ? null : coverageData.get(uri);
        boolean complete = true;
        for (PerformanceInfoLine lineInfo : moduleInfo.getLineInfo()) {
            BigDecimal lineNo;
            int hits;
            try {
                lineNo = CoverageHits.lineNumber(lineInfo.getLineNo());
                hits = CoverageHits.hitCount(lineInfo.getFrequency());
            } catch (IllegalArgumentException | ArithmeticException e) {
                logger.error("Invalid debugger measure for module {}: {}",
                        uri == null ? moduleId.getObjectID() : uri, e.getMessage());
                complete = false;
                continue;
            }
            if (coverMap == null) {
                continue;
            }
            if (CoverageHits.add(coverMap, lineNo, hits, rawMode)) {
                if (rawMode || (hits > 0 && !coverMap.isEmpty())) {
                    collectedUsableMeasurement = true;
                    targetDiscoveryUnconfirmed = false;
                }
            } else if (loggingOptions.isVerbose()) {
                pending.diagnostics.add(() -> logUnmatchedLine(uri, lineNo));
            }
        }
        return complete;
    }

    private URI measuredModuleUri(Map<String, URI> uriListByKey, BSLModuleIdInternal moduleId, String moduleUrl) {
        String objectId = moduleId.getObjectID();
        String propertyId = moduleId.getPropertyID();
        String key = SourceKey.moduleKey(objectId, propertyId);
        String moduleExtensionName = moduleId.getExtensionName();
        if (!rawMode && !metadataOptions.hasMultipleSources()
                && (!filterOptions.getExtensionName().equals(moduleExtensionName)
                || !filterOptions.getExternalDataProcessorUrl().equals(moduleUrl))) {
            return null;
        }
        if (!rawMode && metadataOptions.hasMultipleSources()) {
            key = SourceKey.qualify(key, moduleExtensionName, moduleUrl);
        }

        URI uri = rawMode ? Utils.getRawUri(moduleExtensionName, moduleUrl, key) : uriListByKey.get(key);
        if (uri == null) {
            logger.info("Couldn't find object id {}, property id {} in sources!", objectId, propertyId);
        }
        return uri;
    }

    private void logUnmatchedLine(URI uri, BigDecimal lineNo) {
        logger.info("Can't find line to cover {} in module {}", lineNo, uri);
        try (Stream<String> lines = Files.lines(Paths.get(uri))) {
            lines.skip(lineNo.longValue() - 1).findFirst().ifPresent(line -> logger.info(">>> {}", line));
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
        boolean profilingRequested = false;
        try {
            Version apiver = Version.parse(client.getApiVersion());
            logger.info("Setup settings...");
            client.initSettings(false);
            client.setAutoconnectDebugTargets(
                    debuggerOptions.getDebugAreaNames(),
                    debuggerOptions.getFilteredAutoconnectTargets(apiver));
            logger.info("Setup targets...");
            targetDiscoveryUnconfirmed = !connectListedTargets();

            logger.info("Enabling profiling...");
            client.toggleProfiling(null);
            profilingRequested = true;
            client.toggleProfiling(measureUuid);
        } catch (RuntimeDebugClientException | RuntimeException startupError) {
            if (profilingRequested) {
                // The server may have enabled profiling before the reply failed validation.
                disableProfiling();
            }
            try {
                if (!client.disconnect()) {
                    logger.warn("Debugger did not confirm detach after failed startup");
                }
            } catch (RuntimeDebugClientException | RuntimeException detachError) {
                startupError.addSuppressed(detachError);
                logger.warn("Can't detach debugger after failed startup: {}", detachError.getLocalizedMessage());
            }
            throw startupError;
        }
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
                    // Final measures can arrive in an in-flight ping before disable returns.
                    synchronized (coverageData) {
                        collectingFinalMeasures = true;
                        coverageData.notifyAll();
                    }
                    boolean disableAcknowledged = disableProfiling();
                    // The debugger may queue its last measurements even when its reply cannot be confirmed.
                    try {
                        boolean finalPollConfirmed = awaitFinalMeasures();
                        profilingFinished = disableAcknowledged && finalPollConfirmed;
                    } catch (InterruptedException e) {
                        logger.error("Interrupted while waiting for final measures");
                        profilingFinished = false;
                        interrupted = true;
                    }
                    synchronized (coverageData) {
                        stopExecution.set(true);
                        coverageData.notifyAll();
                        if (finalMeasureFailed) {
                            logger.error("Final debugger measures are incomplete");
                            profilingFinished = false;
                        }
                    }
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
        return systemStarted && profilingFinished && coverageWritten
                && !targetDiscoveryUnconfirmed && hasConfirmedCollection();
    }

    private boolean hasConfirmedCollection() {
        // Cleaning the output resets counters, but it does not undo a successful collection.
        // dbgs can auto-connect an allowed user even if every measured line is outside the report.
        return (!targets.isActive() && !targetAttachmentFailed)
                || receivedAcceptedPacket || collectedUsableMeasurement || targets.hasAcceptedAttachment();
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

    // A debugger poll acknowledges only that poll. Observe the full window, then confirm with a new poll.
    private boolean awaitFinalMeasures() throws InterruptedException {
        long windowEnd = finalMeasureClock.getAsLong() + finalMeasureWindowNanos;
        long deadline = windowEnd + finalConfirmationTimeoutNanos;
        synchronized (coverageData) {
            finalMeasureWindowEndNanos = windowEnd;
            while (mainLoopRunning) {
                long remaining = deadline - finalMeasureClock.getAsLong();
                if (remaining <= 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(coverageData, remaining);
            }
            if (finalDrainConfirmed) {
                return true;
            }
        }
        logger.error("Debugger did not confirm a quiet poll after the final measurement window");
        return false;
    }

    @Override
    protected void cleanCoverageData() {
        synchronized (coverageData) {
            coverageGeneration++;
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
