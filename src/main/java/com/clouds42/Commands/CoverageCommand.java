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
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugHttpClient;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugModelXmlSerializer;
import com.clouds42.CommandLineOptions.*;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Command(name = PipeMessages.START_COMMAND, mixinStandardHelpOptions = true,
        description = "Start measure and save coverage data to file",
        sortOptions = false)
public class CoverageCommand extends BaseCommand implements Callable<Integer> {

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

    private RuntimeDebugHttpClient client;

    private Map<URI, Map<BigDecimal, Integer>> coverageData = new HashMap<URI, Map<BigDecimal, Integer>>() {
        @Override
        public Map<BigDecimal, Integer> get(Object key) {
            Map<BigDecimal, Integer> map = super.get(key);
            if (map == null) {
                map = new HashMap<BigDecimal, Integer>();
                put((URI) key, map);
            }
            return map;
        }
    };

    private CompletableFuture<Boolean> commandListenServer;

    private AtomicBoolean stopExecution = new AtomicBoolean(false);
    private boolean rawMode = false;
    private boolean systemStarted = false;

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
                    logger.info("Get command: " + line);
                    if (PipeMessages.DUMP_COMMAND.equals(line)) {
                        Utils.dumpCoverageFile(coverageData, metadataOptions, outputOptions);
                        out.println(PipeMessages.OK_RESULT);
                        return true;
                    } else if (PipeMessages.STATS_COMMAND.equals(line)) {
                        Utils.printCoverageStats(coverageData, metadataOptions);
                        out.println(PipeMessages.OK_RESULT);
                        return true;
                    } else if (PipeMessages.CLEAN_COMMAND.equals(line)) {
                        coverageData.forEach((uri, bigDecimalIntegerMap) -> {
                            for (var key : bigDecimalIntegerMap.keySet()) {
                                bigDecimalIntegerMap.put(key, 0);
                            }
                        });
                        out.println(PipeMessages.OK_RESULT);
                        return true;
                    } else if (PipeMessages.CHECK_COMMAND.equals(line)) {
                        for (int i = 0; i < 60; i++) {
                            if (systemStarted) {
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

        Integer result = CommandLine.ExitCode.OK;

        createCommandListner();

        RuntimeDebugModelXmlSerializer serializer = new MyRuntimeDebugModelXmlSerializer();
        client = new RuntimeDebugHttpClient(serializer);

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

        Set<String> externalDataProcessorsUriSet = new HashSet<String>();

        try {
            mainLoop(uriListByKey, externalDataProcessorsUriSet);
        } catch (RuntimeDebugClientException e) {
            logger.error("Can't send ping to debug server. Coverage analyzing finished");
            if(logger.isDebugEnabled()) {
                logger.error(e.getLocalizedMessage());
            }
            result = CommandLine.ExitCode.SOFTWARE;
        }
        Thread.sleep(debuggerOptions.getPingTimeout());
        if (opid > 0 && !Utils.isProcessStillAlive(opid)) {
            logger.info("Owner process stopped: " + opid);
            gracefulShutdown(null);
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
        return result;
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    gracefulShutdown(null);
                } catch (IOException e) {
                    logger.info("Socket closed.");
                    e.printStackTrace();
                }
            }
        });
    }

    private void targetStarted(DBGUIExtCmdInfoStartedImpl command) {
        DebugTargetId targetId = command.getTargetID();
        try {
            client.attachRuntimeDebugTargets(Arrays.asList(UUID.fromString(targetId.getId())));
        } catch (RuntimeDebugClientException e) {
            logger.info("Command: " + command.getCmdID().getName() + " error!");
            logger.error(e.getLocalizedMessage());
        }
    }

    private void mainLoop(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet) throws RuntimeDebugClientException {
        while (!stopExecution.get()) {
            List<? extends DBGUIExtCmdInfoBase> commandsList = client.ping();
            if (commandsList.size() > 0) {
                logger.info("Ping result commands size: " + commandsList.size());
                commandsList.forEach(command -> {
                    logger.info("Command: " + command.getCmdID().getName());
                    if (command.getCmdID() == DBGUIExtCmds.MEASURE_RESULT_PROCESSING) {
                        meashureResultProcessing(uriListByKey, externalDataProcessorsUriSet, (DBGUIExtCmdInfoMeasureImpl) command);
                    } else if (command.getCmdID() == DBGUIExtCmds.TARGET_STARTED) {
                        targetStarted((DBGUIExtCmdInfoStartedImpl) command);
                    }
                });
            }
        }
    }

    private void meashureResultProcessing(Map<String, URI> uriListByKey, Set<String> externalDataProcessorsUriSet, DBGUIExtCmdInfoMeasureImpl command) {
        logger.info("Found MEASURE_RESULT_PROCESSING command");
        DBGUIExtCmdInfoMeasureImpl measureCommand = command;
        PerformanceInfoMain measure = measureCommand.getMeasure();
        EList<PerformanceInfoModule> moduleInfoList = measure.getModuleData();
        moduleInfoList.forEach(moduleInfo -> {
            BSLModuleIdInternal moduleId = moduleInfo.getModuleID();
            String moduleUrl = moduleId.getURL();
            if (loggingOptions.isVerbose() && !moduleUrl.isEmpty() && !externalDataProcessorsUriSet.contains(moduleUrl)) {
                logger.info("Found external data processor: " + moduleUrl);
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
                    logger.info("Couldn't find object id " + objectId
                            + ", property id " + propertyId + " in sources!");
                } else {
                    EList<PerformanceInfoLine> lineInfoList = moduleInfo.getLineInfo();
                    lineInfoList.forEach(lineInfo -> {
                        BigDecimal lineNo = lineInfo.getLineNo();
                        Map<BigDecimal, Integer> coverMap = coverageData.get(uri);
                        if (!coverMap.isEmpty() || rawMode) {
                            if (!rawMode && !coverMap.containsKey(lineNo)) {
                                if (loggingOptions.isVerbose()) {
                                    logger.info("Can't find line to cover " + lineNo + " in module " + uri);
                                    try {
                                        Stream<String> all_lines = Files.lines(Paths.get(uri));
                                        String specific_line_n = all_lines.skip(lineNo.longValue() - 1).findFirst().get();
                                        logger.info(">>> " + specific_line_n);
                                    } catch (Exception e) {
                                        logger.error(e.getLocalizedMessage());
                                    }
                                }
                            } else {
                                coverMap.put(lineNo,
                                        coverMap.getOrDefault(lineNo, 0)
                                                + lineInfo.getFrequency().intValue());
                            }
                        }
                    });
                }
            }
        });
    }

    private void createCommandListner() {
        commandListenServer = CompletableFuture.supplyAsync(() -> {
            AtomicBoolean stopListen = new AtomicBoolean(false);
            while (!stopListen.get()) {
                try {
                    Socket clientSocket = getServerSocket(true).accept();
                    CompletableFuture.supplyAsync(() -> listenSocket(clientSocket)).thenAccept(aBoolean -> {
                        if (aBoolean) {
                            stopListen.set(false);
                        }
                    });
                } catch (IOException e) {
                    logger.info("Can't accept socket: " + e.getLocalizedMessage());
                }
            }
            return true;
        });
    }


    private void startSystem(UUID measureUuid) throws RuntimeDebugClientException {
        UUID debugServerUuid = UUID.randomUUID();
        client.configure(
                connectionOptions.getDebugServerUrl(),
                debugServerUuid,
                connectionOptions.getInfobaseAlias());
        logger.info("Connecting to debugger");
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
        logger.info("Setup settings");
        client.initSettings(false);
        client.setAutoconnectDebugTargets(
                debuggerOptions.getDebugAreaNames(),
                debuggerOptions.getAutoconnectTargets());

        logger.info("Setup targets");
        List<DebugTargetId> debugTargets;
        if (debuggerOptions.getDebugAreaNames().isEmpty()) {
            debugTargets = client.getRuntimeDebugTargets(null);
        } else {
            debugTargets = new LinkedList<DebugTargetId>();
            debuggerOptions.getDebugAreaNames().forEach(areaName -> {
                try {
                    debugTargets.addAll(client.getRuntimeDebugTargets(areaName));
                } catch (RuntimeDebugClientException ex) {
                    logger.error(ex.getLocalizedMessage());
                }
            });
        }
        connectAllTargets(debugTargets);

        logger.info("Profiling ON");
        client.toggleProfiling(null);
        client.toggleProfiling(measureUuid);

        systemStarted = true;

    }

    private void gracefulShutdown(PrintWriter serverPipeOut) throws IOException {
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

        if (getServerSocket(false) != null) {
            if (serverPipeOut != null) {
                serverPipeOut.println(PipeMessages.OK_RESULT);
            }
        }

        stopExecution.set(true);

        logger.info("Bye!");
    }

    @Override
    public ConnectionOptions getConnectionOptions() {
        return connectionOptions;
    }
}
