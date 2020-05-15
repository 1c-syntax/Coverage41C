package com.clouds42.Commands;

import com._1c.g5.v8.dt.debug.core.runtime.client.RuntimeDebugClientException;
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
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import org.eclipse.emf.common.util.EList;
import org.scalasbt.ipcsocket.UnixDomainServerSocket;
import org.scalasbt.ipcsocket.Win32NamedPipeServerSocket;
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
import java.net.ServerSocket;
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
public class CoverageCommand implements Callable<Integer> {

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

    private Map<URI, Map<BigDecimal, Boolean>> coverageData = new HashMap<URI,Map<BigDecimal, Boolean>> () {
        @Override
        public Map<BigDecimal, Boolean> get(Object key) {
            Map<BigDecimal, Boolean> map = super.get(key);
            if (map == null) {
                map = new HashMap<BigDecimal, Boolean>();
                put((URI)key, map);
            }
            return map;
        }
    };

    private ServerSocket serverSocket;
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
                        coverageData.forEach((uri, bigDecimalBooleanMap) -> {
                            for (var key : bigDecimalBooleanMap.keySet()) {
                                bigDecimalBooleanMap.put(key, false);
                            }
                        });
                        out.println(PipeMessages.OK_RESULT);
                        return true;
                    } else if (PipeMessages.CHECK_COMMAND.equals(line)) {
                        for (int i = 0; i<60; i++) {
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

        boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

        String pipeName = Utils.getPipeName(connectionOptions);

        if (isWindows) {
            serverSocket = new Win32NamedPipeServerSocket(pipeName);
        } else {
            serverSocket = new UnixDomainServerSocket(pipeName);
        }

        commandListenServer = CompletableFuture.supplyAsync(() -> {
            AtomicBoolean stopListen = new AtomicBoolean(false);
            while(!stopListen.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    CompletableFuture.supplyAsync(() -> listenSocket(clientSocket)).thenAccept(aBoolean -> {
                        if(aBoolean) {
                            stopListen.set(false);
                        }
                    });
                } catch (IOException e) {
                    logger.info("Can't accept socket: " + e.getLocalizedMessage());
                }
            }
            return true;
        });

        RuntimeDebugModelXmlSerializer serializer = new RuntimeDebugModelXmlSerializer();
        client = new RuntimeDebugHttpClient(serializer);

        UUID debugServerUuid = UUID.randomUUID();
        UUID measureUuid = UUID.randomUUID();

        rawMode = metadataOptions.isRawMode();

        Map<String, URI> uriListByKey = Utils.readMetadata(metadataOptions, coverageData);

        boolean firstRun = true;

        try {
            client.configure(
                    connectionOptions.getDebugServerUrl(),
                    debugServerUuid,
                    connectionOptions.getInfobaseAlias());
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }

        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            public void run()
            {
                gracefulShutdown(null);
            }
        });

        Set<String> externalDataProcessorsUriSet = new HashSet<String>();

        while (!stopExecution.get()) {
            try {
                if (firstRun) {
                    firstRun = false;
                    throw new RuntimeDebugClientException("First run - connecting to dbgs");
                }
                List<? extends DBGUIExtCmdInfoBase> commandsList = client.ping();
                if (commandsList.size() > 0) {
                    logger.info("Ping result commands size: " + commandsList.size());
                    commandsList.forEach(command -> {
                        logger.info("Command: " + command.getCmdID().getName());
                        if (command.getCmdID() == DBGUIExtCmds.MEASURE_RESULT_PROCESSING) {
                            logger.info("Found MEASURE_RESULT_PROCESSING command");
                            DBGUIExtCmdInfoMeasureImpl measureCommand = (DBGUIExtCmdInfoMeasureImpl) command;
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
                                            Map<BigDecimal, Boolean> coverMap = coverageData.get(uri);
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
                                                    coverMap.put(lineNo, true);
                                                }
                                            }
                                        });
                                    }
                                }
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
                }
            } catch (RuntimeDebugClientException e) {
                logger.info(e.getLocalizedMessage());
                if (systemStarted) {
                    logger.info("Can't send ping to dbgs. Coverage analyzing finished");
                    gracefulShutdown(null);
                } else {
                    try {
                        client.connect(debuggerOptions.getPassword());
                        client.initSettings(false);
                        client.setAutoconnectDebugTargets(
                                debuggerOptions.getDebugAreaNames(),
                                debuggerOptions.getAutoconnectTargets());

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

                        client.toggleProfiling(null);
                        client.toggleProfiling(measureUuid);

                        systemStarted = true;
                    } catch (RuntimeDebugClientException e1) {
                        logger.error(e1.getLocalizedMessage());
                        return CommandLine.ExitCode.SOFTWARE;
                    }
                }
            }
            Thread.sleep(debuggerOptions.getPingTimeout());
            if (opid > 0 && !Utils.isProcessStillAlive(opid)) {
                logger.info("Owner process stopped: " + opid);
                gracefulShutdown(null);
            }
        }

        logger.info("Disconnecting from dbgs...");
        try {
            client.disconnect();
            client.dispose();
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        }

        if (serverSocket != null) {
            serverSocket.close();
            serverSocket = null;
        }

        logger.info("Main thread finished");
        return CommandLine.ExitCode.OK;
    }



    private void gracefulShutdown(PrintWriter serverPipeOut) {
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

        if (serverSocket != null) {
            if (serverPipeOut != null) {
                serverPipeOut.println(PipeMessages.OK_RESULT);
            }
        }

        stopExecution.set(true);

        logger.info("Bye!");
    }

}
