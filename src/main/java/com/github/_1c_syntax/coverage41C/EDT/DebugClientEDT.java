package com.github._1c_syntax.coverage41C.EDT;

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
import com.clouds42.CommandLineOptions.DebuggerOptions;
import com.clouds42.DebugClient;
import com.clouds42.MyRuntimeDebugModelXmlSerializer;
import com.clouds42.Utils;
import com.github._1c_syntax.coverage41C.CoverageCollector;
import com.github._1c_syntax.coverage41C.DebugClientException;
import com.github._1c_syntax.coverage41C.DebugTargetType;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DebugClientEDT {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final DebugClient client;

    private boolean isVerbose;
    private boolean rawMode;
    private String extensionName;
    private String externalDataProcessorUrl;

    private CoverageCollector collector;

    public DebugClientEDT() {
        RuntimeDebugModelXmlSerializer serializer = new MyRuntimeDebugModelXmlSerializer();
        client = new DebugClient(serializer);
    }

    public void setOptions(boolean isVerbose, boolean rawMode) {
        this.isVerbose = isVerbose;
        this.rawMode = rawMode;

    }

    public void setResolverOptions(String extensionName, String externalDataProcessorUrl, CoverageCollector collector) {
        this.extensionName = extensionName;
        this.externalDataProcessorUrl = externalDataProcessorUrl;
        this.collector = collector;
    }

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

    public void connectTargets(DebuggerOptions debuggerOptions) throws DebugClientException {
        logger.info("Setup targets...");
        List<DebugTargetId> debugTargets;
        if (debuggerOptions.getDebugAreaNames().isEmpty()) {
            try {
                debugTargets = client.getRuntimeDebugTargets(null);
            } catch (RuntimeDebugClientException ex) {
                throw new DebugClientException("Error calling getRuntimeDebugTargets", ex);
            }
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
    }

    public void enableProfiling(UUID measureUuid) throws DebugClientException {
        logger.info("Enabling profiling...");
        try {
            client.toggleProfiling(null);
            client.toggleProfiling(measureUuid);
        } catch (RuntimeDebugClientException ex) {
            throw new DebugClientException("Error enablingProfiling", ex);
        }
    }

    public void disableProfiling() {
        logger.info("Disabling profiling...");
        try {
            client.toggleProfiling(null);
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    public void configure(String debugServerUrl, String infobaseAlias) {
        UUID debugServerUuid = UUID.randomUUID();
        client.configure(debugServerUrl, debugServerUuid, infobaseAlias);
    }

    public void connect(String password) throws DebugClientException {
        logger.info("Connecting to debugger...");
        AttachDebugUIResult connectionResult;
        try {
            connectionResult = client.connect(password);
        } catch (RuntimeDebugClientException ex) {
            throw new DebugClientException("Error on connect", ex);
        }

        if (connectionResult != AttachDebugUIResult.REGISTERED) {
            if (connectionResult == AttachDebugUIResult.IB_IN_DEBUG) {
                throw new DebugClientException("Can't connect to debug server. IB is in debug. Close configurator or EDT first");
            } else if (connectionResult == AttachDebugUIResult.CREDENTIALS_REQUIRED) {
                throw new DebugClientException("Can't connect to debug server. Use -p option to set correct password");
            } else {
                throw new DebugClientException("Can't connect to debug server. Connection result: " + connectionResult);
            }
        }
    }

    public void disconnect() {
        logger.info("Disconnecting from dbgs...");
        try {
            client.disconnect();
            client.dispose();
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    public String getApiVersion() throws DebugClientException {
        try {
            return client.getApiVersion();
        } catch (RuntimeDebugClientException ex) {
            throw new DebugClientException("Error on getApiVersion", ex);
        }
    }

    public void setupSettings(List<String> debugAreaNames,  List<DebugTargetType> debugTargets) throws DebugClientException {
        logger.info("Setup settings...");

        try {
            client.initSettings(false);
            client.setAutoconnectDebugTargets(
                    debugAreaNames,
                    convertDebugTargets(debugTargets));
        } catch (RuntimeDebugClientException ex) {
            throw new DebugClientException("Error on setupSettings", ex);
        }
    }

    private List<com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType> convertDebugTargets(List<DebugTargetType> debugTargets) {
        return debugTargets.stream()
                .map(type -> castByName(type, com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType.class))
                .collect(Collectors.toList());
    }

    private <F extends Enum<F>> F castByName(final Enum<?> e, final Class<F> fClass) {
        return F.valueOf(fClass, e.name());
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

    public void ping(Set<String> externalDataProcessorsUriSet) throws DebugClientException {

        List<? extends DBGUIExtCmdInfoBase> commandsList;

        try {
            commandsList = client.ping();
        } catch (RuntimeDebugClientException ex) {
            throw new DebugClientException("Error on ping", ex);
        }

        logger.info("Ping result commands size: {}", commandsList.size());
        commandsList.forEach(command -> {
            logger.info("Command: {}", command.getCmdID().getName());
            if (command.getCmdID() == DBGUIExtCmds.MEASURE_RESULT_PROCESSING) {
                measureResultProcessing(externalDataProcessorsUriSet, (DBGUIExtCmdInfoMeasureImpl) command);
            } else if (command.getCmdID() == DBGUIExtCmds.TARGET_STARTED) {
                targetStarted((DBGUIExtCmdInfoStartedImpl) command);
            }
        });
    }

    private void measureResultProcessing(Set<String> externalDataProcessorsUriSet, DBGUIExtCmdInfoMeasureImpl command) {
        logger.info("Found MEASURE_RESULT_PROCESSING command");

        var uriListByKey = collector.getUriListByKey();

        PerformanceInfoMain measure = command.getMeasure();
        EList<PerformanceInfoModule> moduleInfoList = measure.getModuleData();
        moduleInfoList.forEach(moduleInfo -> {
            BSLModuleIdInternal moduleId = moduleInfo.getModuleID();
            String moduleUrl = moduleId.getURL();
            if (isVerbose && !moduleUrl.isEmpty() && !externalDataProcessorsUriSet.contains(moduleUrl)) {
                logger.info("Found external data processor: {}", moduleUrl);
                externalDataProcessorsUriSet.add(moduleUrl);
            }
            String moduleExtensionName = moduleId.getExtensionName();
            if (rawMode
                    || (extensionName.equals(moduleExtensionName)
                    && externalDataProcessorUrl.equals(moduleUrl))) {
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
                        Map<BigDecimal, Integer> coverMap = collector.get(uri);
                        if (!coverMap.isEmpty() || rawMode) {
                            if (!rawMode && !coverMap.containsKey(lineNo)) {
                                if (isVerbose) {
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
}
