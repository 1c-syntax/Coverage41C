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
import com.clouds42.DebugClient;
import com.clouds42.MyRuntimeDebugModelXmlSerializer;
import com.github._1c_syntax.coverage41C.CoverageCollector;
import com.github._1c_syntax.coverage41C.DebugClientException;
import com.github._1c_syntax.coverage41C.DebugTargetType;
import com.github._1c_syntax.coverage41C.IDebugClient;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.module.ModuleDescriptor;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class DebugClientEDT implements IDebugClient {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final DebugClient client;

    private final CoverageCollector collector;

    public DebugClientEDT(CoverageCollector collector, String debugServerUrl, String infobaseAlias) {
        RuntimeDebugModelXmlSerializer serializer = new MyRuntimeDebugModelXmlSerializer();
        client = new DebugClient(serializer);

        this.collector = collector;

        configure(debugServerUrl, infobaseAlias);
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

    public void connectTargets(List<String> debugAreaNames, List<DebugTargetType> debugTargetTypes) throws DebugClientException {

        var apiver = ModuleDescriptor.Version.parse(getApiVersion());
        var filteredTargets = filterTargetsByApiVersion(debugTargetTypes, apiver);
        setupSettings(debugAreaNames, filteredTargets);

        var debugTargets = getCurrentTargets(debugAreaNames);
        connectAllTargets(debugTargets);
    }

    private List<DebugTargetId> getCurrentTargets(List<String> debugAreaNames) throws DebugClientException {
        logger.info("Setup targets...");
        List<DebugTargetId> debugTargets;
        if (debugAreaNames.isEmpty()) {
            try {
                return client.getRuntimeDebugTargets(null);
            } catch (RuntimeDebugClientException ex) {
                throw new DebugClientException("Error calling getRuntimeDebugTargets", ex);
            }
        } else {
            debugTargets = new LinkedList<>();
            debugAreaNames.forEach(areaName -> {
                try {
                    debugTargets.addAll(client.getRuntimeDebugTargets(areaName));
                } catch (RuntimeDebugClientException ex) {
                    logger.error(ex.getLocalizedMessage());
                }
            });
            return debugTargets;
        }
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

    public void disableProfiling() throws DebugClientException {
        logger.info("Disabling profiling...");
        try {
            client.toggleProfiling(null);
        } catch (RuntimeDebugClientException e) {
            throw new DebugClientException("Error disableProfiling", e);
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

    public void disconnect() throws DebugClientException {
        logger.info("Disconnecting from dbgs...");
        try {
            client.disconnect();
            client.dispose();
        } catch (RuntimeDebugClientException e) {
            throw new DebugClientException("Error disconnect", e);
        }
    }

    public String getApiVersion() throws DebugClientException {
        try {
            return client.getApiVersion();
        } catch (RuntimeDebugClientException ex) {
            throw new DebugClientException("Error getApiVersion", ex);
        }
    }

    private void setupSettings(List<String> debugAreaNames,  List<DebugTargetType> debugTargets) throws DebugClientException {
        logger.info("Setup settings...");

        try {
            client.initSettings(false);
            client.setAutoconnectDebugTargets(
                    debugAreaNames,
                    convertDebugTargets(debugTargets));
        } catch (RuntimeDebugClientException ex) {
            throw new DebugClientException("Error setupSettings", ex);
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

    public void ping() throws DebugClientException {

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
                measureResultProcessing((DBGUIExtCmdInfoMeasureImpl) command);
            } else if (command.getCmdID() == DBGUIExtCmds.TARGET_STARTED) {
                targetStarted((DBGUIExtCmdInfoStartedImpl) command);
            }
        });
    }

    private void measureResultProcessing(DBGUIExtCmdInfoMeasureImpl command) {
        logger.info("Found MEASURE_RESULT_PROCESSING command");

        PerformanceInfoMain measure = command.getMeasure();
        EList<PerformanceInfoModule> moduleInfoList = measure.getModuleData();
        moduleInfoList.forEach(moduleInfo -> {
            BSLModuleIdInternal moduleId = moduleInfo.getModuleID();
            String moduleUrl = moduleId.getURL();

            String moduleExtensionName = moduleId.getExtensionName();
            if (collector.isFiltered(moduleUrl, moduleExtensionName)) {
                String objectId = moduleId.getObjectID();
                String propertyId = moduleId.getPropertyID();

                URI uri = collector.getUri(objectId, propertyId);
                if (uri == null) {
                    logger.info("Couldn't find object id {}, property id {} in sources!", objectId, propertyId);
                } else {
                    EList<PerformanceInfoLine> lineInfoList = moduleInfo.getLineInfo();
                    lineInfoList.forEach(lineInfo -> {
                        BigDecimal lineNo = lineInfo.getLineNo();
                        var frequency = lineInfo.getFrequency().intValue();
                        collector.addCoverage(uri, lineNo, frequency);
                    });
                }
            }
        });
    }

    private static List<DebugTargetType> filterTargetsByApiVersion(List<DebugTargetType> debugTargets, ModuleDescriptor.Version ApiVersion) {
        List<DebugTargetType> debugTypes = new java.util.ArrayList<>(debugTargets);

        if (ModuleDescriptor.Version.parse("8.3.16").compareTo(ApiVersion) > 0) {
            debugTypes.remove(DebugTargetType.MOBILE_MANAGED_CLIENT);
            logger.info("[{}] was removed", DebugTargetType.MOBILE_MANAGED_CLIENT);
        }
        return debugTypes;
    }
}
