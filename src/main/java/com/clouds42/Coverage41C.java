package com.clouds42;

import com._1c.g5.v8.dt.debug.core.runtime.client.RuntimeDebugClientException;
import com._1c.g5.v8.dt.debug.model.base.data.BSLModuleIdInternal;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetId;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoBase;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmds;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.impl.DBGUIExtCmdInfoMeasureImpl;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.impl.DBGUIExtCmdInfoStartedImpl;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoLine;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoMain;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoModule;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugHttpClient;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugModelXmlSerializer;
import com.opencsv.CSVWriter;
import org.eclipse.emf.common.util.EList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

@Command(name = "Coverage41C", mixinStandardHelpOptions = true, version = "Coverage41C 1.0",
        description = "Make measures from 1C:Enterprise and save them to csv file",
        sortOptions = false)
public class Coverage41C implements Callable<Integer> {

    @Option(names = {"-i", "--infobase"}, description = "InfoBase name", required = true)
    private String infobaseAlias;

    @Option(names = {"-o", "--out"}, description = "Output file name", required = true)
    private File outputFile;

    @Option(names = {"-u", "--debugger"}, description = "Debugger url", defaultValue = "http://127.0.0.1:1550/")
    private String debugServerUrl;

    @Option(names = {"-p", "--password"}, description = "Dbgs password", interactive = true)
    String password;

    @Option(names = {"-a", "--areanames"}, description = "Debug area names (not for general use!)")
    List<String> debugAreaNames;

    @Option(names = {"-t", "--timeout"}, description = "Ping timeout", defaultValue = "1000")
    Integer pingTimeout;

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static RuntimeDebugHttpClient client;
    private static CSVWriter writer;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Coverage41C()).execute(args);
        System.exit(exitCode);
    }

    private void connectAllTargets(List<DebugTargetId> debugTargets) {
        logger.info("Current debug targets size: " + debugTargets.size());
        debugTargets.forEach(debugTarget -> {
            String id = debugTarget.getId();
            String seanceId = debugTarget.getSeanceId();
            String targetType = debugTarget.getTargetType().getName();
            logger.info("Id: " + id + ", seance id: " + seanceId + ", target type: " + targetType);
            try {
                client.attachRuntimeDebugTargets(Arrays.asList(UUID.fromString(debugTarget.getId())));
            } catch (RuntimeDebugClientException e) {
                logger.error(e.getLocalizedMessage());
            }
        });
    }

    @Override
    public Integer call() throws Exception {
        RuntimeDebugModelXmlSerializer serializer = new RuntimeDebugModelXmlSerializer();
        client = new RuntimeDebugHttpClient(serializer);

        UUID debugServerUuid = UUID.randomUUID();
        UUID measureUuid = UUID.randomUUID();
        if (debugAreaNames == null) {
            debugAreaNames = new ArrayList<>();
        }

        boolean firstRun = true;

        try {
            client.configure(debugServerUrl, debugServerUuid, infobaseAlias);
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
            return -1;
        }

        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            public void run()
            {
                logger.info("Disconnecting from dbgs...");
                try {
                    client.toggleProfiling(null);
                    client.disconnect();
                    client.dispose();
                } catch (RuntimeDebugClientException e) {
                    logger.error(e.getLocalizedMessage());
                }
                try {
                    writer.close();
                } catch (IOException e) {
                    logger.error(e.getLocalizedMessage());
                }
                logger.info("Bye!");
            }
        });

        try {
            writer = new CSVWriter(new FileWriter(outputFile, Charset.forName("UTF-8")));
            String[] headerRecord = {"ExtentionName", "ModuleName", "URL", "ObjectId", "PropertyId", "LineNo"};
            writer.writeNext(headerRecord);

            while (true) {
                try {
                    if (firstRun) {
                        firstRun = false;
                        throw new RuntimeDebugClientException("First run - connecting to dbgs");
                    }
                    logger.info("Sending ping...");
                    List<? extends DBGUIExtCmdInfoBase> commandsList = client.ping();
                    logger.info("Ping result commands size: " + commandsList.size());
                    AtomicBoolean measureFound = new AtomicBoolean(false);
                    if (commandsList.size() > 0) {
                        commandsList.forEach(command -> {
                            logger.info("Command: " + command.getCmdID().getName());
                            if (command.getCmdID() == DBGUIExtCmds.MEASURE_RESULT_PROCESSING) {
                                measureFound.set(true);
                                logger.info("Found MEASURE_RESULT_PROCESSING command");
                                DBGUIExtCmdInfoMeasureImpl measureCommand = (DBGUIExtCmdInfoMeasureImpl) command;
                                PerformanceInfoMain measure = measureCommand.getMeasure();
                                EList<PerformanceInfoModule> moduleInfoList = measure.getModuleData();
                                moduleInfoList.forEach(moduleInfo -> {
                                    BSLModuleIdInternal moduleId = moduleInfo.getModuleID();
                                    String url = moduleId.getURL();
                                    String extentionName = moduleId.getExtensionName();
                                    String moduleName = moduleId.getType().getName();
                                    String objectId = moduleId.getObjectID();
                                    String propertyId = moduleId.getPropertyID();
                                    EList<PerformanceInfoLine> lineInfoList = moduleInfo.getLineInfo();
                                    lineInfoList.forEach(lineInfo -> {
                                        BigDecimal lineNo = lineInfo.getLineNo();
                                        writer.writeNext(new String[]{extentionName, moduleName, url, objectId, propertyId, lineNo.toString()});
                                    });
                                });
                            } else if (command.getCmdID() == DBGUIExtCmds.TARGET_STARTED) {
                                DBGUIExtCmdInfoStartedImpl targetStartedCommand = (DBGUIExtCmdInfoStartedImpl) command;
                                DebugTargetId targetId = targetStartedCommand.getTargetID();
                                try {
                                    client.attachRuntimeDebugTargets(Arrays.asList(UUID.fromString(targetId.getId())));
                                } catch (RuntimeDebugClientException e) {
                                    logger.error(e.getLocalizedMessage());
                                }
                            }
                        });
                        if (measureFound.get()) {
                            writer.flush();
                        }
                    }
                } catch (RuntimeDebugClientException e) {
                    logger.info(e.getLocalizedMessage());
                    try {
                        client.connect(password);
                        client.initSettings(false);
                        List<DebugTargetType> debugTargetTypes = new LinkedList<DebugTargetType>();
                        debugTargetTypes.addAll(DebugTargetType.VALUES);
                        debugTargetTypes.remove(DebugTargetType.UNKNOWN);
                        client.setAutoconnectDebugTargets(debugAreaNames, debugTargetTypes);

                        List<DebugTargetId> debugTargets;
                        if (debugAreaNames.isEmpty()) {
                            debugTargets = client.getRuntimeDebugTargets(null);
                        } else {
                            debugTargets = new LinkedList<DebugTargetId>();
                            debugAreaNames.forEach(areaName -> {
                                try {
                                    debugTargets.addAll(client.getRuntimeDebugTargets(areaName));
                                } catch (RuntimeDebugClientException ex) {
                                    logger.error(ex.getLocalizedMessage());
                                }
                            });
                        }
                        connectAllTargets(debugTargets);

                        client.toggleProfiling(null);
                        client.toggleProfiling(measureUuid);
                    } catch (RuntimeDebugClientException e1) {
                        logger.error(e1.getLocalizedMessage());
                        return -2;
                    }
                }
                Thread.sleep(pingTimeout);
            }
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage());
        }

        try {
            client.toggleProfiling(null);
            client.disconnect();
            client.dispose();
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        }

        return 0;
    }
}
