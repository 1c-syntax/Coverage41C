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
import com.github._1c_syntax.bsl.parser.BSLParser;
import com.github._1c_syntax.bsl.parser.BSLParserRuleContext;
import com.github._1c_syntax.bsl.parser.Tokenizer;
import com.github._1c_syntax.mdclasses.mdo.Form;
import com.github._1c_syntax.mdclasses.mdo.MDObjectBase;
import com.github._1c_syntax.mdclasses.mdo.SettingsStorage;
import com.github._1c_syntax.mdclasses.metadata.Configuration;
import com.github._1c_syntax.mdclasses.metadata.additional.ModuleType;
import com.github._1c_syntax.mdclasses.metadata.additional.SupportVariant;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.runtime.tree.Trees;
import org.eclipse.emf.common.util.EList;
import org.scalasbt.ipcsocket.UnixDomainServerSocket;
import org.scalasbt.ipcsocket.UnixDomainSocket;
import org.scalasbt.ipcsocket.Win32NamedPipeServerSocket;
import org.scalasbt.ipcsocket.Win32NamedPipeSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Command(name = "Coverage41C", mixinStandardHelpOptions = true, version = "Coverage41C 1.0",
        description = "Make measures from 1C:Enterprise and save them to genericCoverage.xml file",
        sortOptions = false)
public class Coverage41C implements Callable<Integer> {

    @Option(names = {"-a", "--action"}, description = "Action: ${COMPLETION-CANDIDATES}. Default - ${DEFAULT-VALUE}",
            defaultValue = "start", fallbackValue = "start")
    private CommandAction commandAction;

    @Option(names = {"-i", "--infobase"}, description = "InfoBase name. For file infobase use 'DefAlias' name", required = true)
    private String infobaseAlias;

    @Option(names = {"-e", "--extensionName"}, description = "Extension name", defaultValue = "")
    private String extensionName;

    @Option(names = {"-s", "--srcDir"}, description = "Directory with sources exported to xml", defaultValue = "")
    private String srcDirName;

    @Option(names = {"-P", "--projectDir"}, description = "Directory with project")
    private String projectDirName;

    @Option(names = {"-o", "--out"}, description = "Output file name")
    private File outputFile;

    @Option(names = {"-u", "--debugger"}, description = "Debugger url. Default - ${DEFAULT-VALUE}", defaultValue = "http://127.0.0.1:1550/")
    private String debugServerUrl;

    @Option(names = {"-p", "--password"}, description = "Dbgs password", interactive = true)
    String password;

    @Option(names = {"-n", "--areanames"}, description = "Debug area names (not for general use!)")
    List<String> debugAreaNames;

    @Option(names = {"-t", "--timeout"}, description = "Ping timeout. Default - ${DEFAULT-VALUE}", defaultValue = "1000")
    Integer pingTimeout;

    @Option(names = {"-r", "--removeSupport"}, description = "Remove support values: ${COMPLETION-CANDIDATES}. Default - ${DEFAULT-VALUE}", defaultValue = "NONE")
    SupportVariant removeSupport;

    private static RuntimeDebugHttpClient client;
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final Map<URI, Map<BigDecimal, Boolean>> coverageData = new HashMap<URI,Map<BigDecimal, Boolean>> () {
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

    private static final String EXIT_COMMAND = "EXIT";
    private static final String EXIT_RESULT = "OK";

    private ServerSocket serverSocket;
    private CompletableFuture<Boolean> commandListenServer;

    private Configuration conf;

    private AtomicBoolean stopExecution = new AtomicBoolean(false);

    private enum CommandAction {
        start,
        stop
    }

    private class CommandListenServer {
        private final ServerSocket serverSocket;

        public CommandListenServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }

        public void run() throws IOException {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                try {
                    PrintWriter out =
                            new PrintWriter(clientSocket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(clientSocket.getInputStream()));
                    String line;
                    do {
                        line = in.readLine();
                    } while (line == null || !line.trim().equals(EXIT_COMMAND));
                    gracefulShutdown(out);
                    out.println(EXIT_RESULT);
                } catch (IOException e) {
                    logger.error(e.getLocalizedMessage());
                }
            }
        }
    }

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

        boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

        URI debugUri = URI.create(debugServerUrl);
        String pipeName;
        if (isWindows) {
            pipeName = String.format("\\\\.\\pipe\\COVER_%s_%s", infobaseAlias, debugUri.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_"));
        } else {
            Path tempDir = Files.createTempDirectory("coverage41c");
            Path sock = tempDir.resolve(String.format("%s_%s.sock", infobaseAlias, debugUri.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_")));
            pipeName = sock.toString();
        }
        if (commandAction == CommandAction.stop) {
            logger.info("Trying to stop main application...");
            Socket client;
            if (isWindows) {
                client = new Win32NamedPipeSocket(pipeName);
            } else {
                client = new UnixDomainSocket(pipeName);
            }
            PrintWriter pipeOut = new PrintWriter(client.getOutputStream(), true);
            BufferedReader pipeIn = new BufferedReader(new InputStreamReader(client.getInputStream()));
            pipeOut.println(EXIT_COMMAND);
            logger.info("Command send finished");
            String result = pipeIn.readLine();
            if (result.equals(EXIT_RESULT)) {
                logger.info("OK");
                return 0;
            } else {
                logger.info("Incorrect response from main application");
                return -1;
            }
        }

        if (isWindows) {
            serverSocket = new Win32NamedPipeServerSocket(pipeName);
        } else {
            serverSocket = new UnixDomainServerSocket(pipeName);
        }
        commandListenServer = CompletableFuture.supplyAsync(() -> {
            try {
                CommandListenServer runner = new CommandListenServer(serverSocket);
                runner.run();
            } catch (IOException e) {
                logger.error(e.getLocalizedMessage());
            }
            return true;
        });

        RuntimeDebugModelXmlSerializer serializer = new RuntimeDebugModelXmlSerializer();
        client = new RuntimeDebugHttpClient(serializer);

        UUID debugServerUuid = UUID.randomUUID();
        UUID measureUuid = UUID.randomUUID();
        if (debugAreaNames == null) {
            debugAreaNames = new ArrayList<>();
        }

        logger.info("Reading configuration sources...");

        Map<String, URI> uriListByKey = new HashMap<>();

        conf = Configuration.create(Path.of(projectDirName).resolve(srcDirName));

        Set<MDObjectBase> configurationChildren = conf.getChildren();
        for (MDObjectBase mdObj : configurationChildren) {

            addAllModulesToList(uriListByKey, mdObj);

            List<com.github._1c_syntax.mdclasses.mdo.Command> commandsList = mdObj.getCommands();
            if (commandsList != null) {
                commandsList.forEach(cmd -> {
                    addAllModulesToList(uriListByKey, cmd);
                });
            }

            List<Form> formsList = mdObj.getForms();
            if (formsList != null) {
                formsList.forEach(form -> {
                    addAllModulesToList(uriListByKey, form);
                });
            }
        }
        logger.info("Configuration sources reading DONE");

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
                gracefulShutdown(null);
            }
        });

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
                                String url = moduleId.getURL();
                                String moduleExtensionName = moduleId.getExtensionName();
                                if (this.extensionName.equals(moduleExtensionName)) {
                                    String objectId = moduleId.getObjectID();
                                    String propertyId = moduleId.getPropertyID();
                                    String key = getUriKey(objectId, propertyId);

                                    URI uri = uriListByKey.get(key);
                                    if (uri == null) {
                                        logger.info("Couldn't find object id " + objectId
                                                + ", property id " + propertyId + " in sources!");
                                    } else {
                                        EList<PerformanceInfoLine> lineInfoList = moduleInfo.getLineInfo();
                                        lineInfoList.forEach(lineInfo -> {
                                            BigDecimal lineNo = lineInfo.getLineNo();
                                            Map<BigDecimal, Boolean> coverMap = coverageData.get(uri);
                                            if (!coverMap.isEmpty()) {
                                                if (!coverMap.containsKey(lineNo)) {
                                                    logger.info("Can't find line to cover " + lineNo + " in module " + uri);
                                                }
                                                coverMap.put(lineNo, true);
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
        logger.info("Disconnecting from dbgs...");
        try {
            client.disconnect();
            client.dispose();
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
        }

        logger.info("Main thread finished");
        return 0;
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

        DocumentBuilderFactory icFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder icBuilder;
        try {

            URI projectUri = Path.of(projectDirName).toUri();
            icBuilder = icFactory.newDocumentBuilder();
            Document doc = icBuilder.newDocument();
            Element mainRootElement = doc.createElement("coverage");
            mainRootElement.setAttribute("version", "1");
            doc.appendChild(mainRootElement);
            coverageData.forEach((uri, bigDecimalsMap) -> {
                if (bigDecimalsMap.isEmpty()) {
                    return;
                }
                Element fileElement = doc.createElement("file");
                fileElement.setAttribute("path", projectUri.relativize(uri).getPath());
                bigDecimalsMap.forEach((bigDecimal, bool) -> {
                    Element lineElement = doc.createElement("lineToCover");
                    lineElement.setAttribute("covered", Boolean.toString(bool));
                    lineElement.setAttribute("lineNumber", bigDecimal.toString());
                    fileElement.appendChild(lineElement);
                });
                mainRootElement.appendChild(fileElement);
            });
            long linesToCover = 0;
            long coveredLinesCount = 0;
            for (Map<BigDecimal, Boolean> bigDecimalMap : coverageData.values()) {
                linesToCover += bigDecimalMap.size();
                coveredLinesCount += bigDecimalMap.values().stream().filter(aBoolean -> aBoolean.booleanValue()).count();
            }
            logger.info("Lines to cover: " + linesToCover);
            logger.info("Covered lines: " + coveredLinesCount);
            if (linesToCover > 0) {
                logger.info("Covering: " + Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100. + "%");
            }
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            DOMSource source = new DOMSource(doc);
            StreamResult console = new StreamResult(new FileOutputStream(outputFile));
            transformer.transform(source, console);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (serverSocket != null) {
            if (serverPipeOut != null) {
                serverPipeOut.println(EXIT_RESULT);
            }
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.error(e.getLocalizedMessage());
            }
        }

        if (commandListenServer != null) {
            commandListenServer.cancel(true);
        }

        stopExecution.set(true);

        logger.info("Bye!");
    }

    private String getModuleTypeUuid(ModuleType moduleType, MDObjectBase mdObject) {
        switch (moduleType) {
            case CommandModule:
                return "078a6af8-d22c-4248-9c33-7e90075a3d2c";
            case ObjectModule:
                return "a637f77f-3840-441d-a1c3-699c8c5cb7e0";
            case ManagerModule:
                if (mdObject instanceof SettingsStorage) {
                    return "0c8cad23-bf8c-468e-b49e-12f1927c048b";
                } else {
                    return "d1b64a2c-8078-4982-8190-8f81aefda192";
                }
            case FormModule:
                return "32e087ab-1491-49b6-aba7-43571b41ac2b";
            case RecordSetModule:
                return "9f36fd70-4bf4-47f6-b235-935f73aab43f";
            case ValueManagerModule:
                return "3e58c91f-9aaa-4f42-8999-4baf33907b75";
            case ManagedApplicationModule:
                return "d22e852a-cf8a-4f77-8ccb-3548e7792bea";
            case SessionModule:
                return "9b7bbbae-9771-46f2-9e4d-2489e0ffc702";
            case ExternalConnectionModule:
                return "a4a9c1e2-1e54-4c7f-af06-4ca341198fac";
            case OrdinaryApplicationModule:
                return "a78d9ce3-4e0c-48d5-9863-ae7342eedf94";
            case HTTPServiceModule:
            case WEBServiceModule:
            case CommonModule:
                return "d5963243-262e-4398-b4d7-fb16d06484f6";
            case ApplicationModule:
            case Unknown:
                break;
        }
        logger.info("Couldn't find UUID for module type: " + moduleType + " for object " + mdObject.getName());
        return "UNKNOWN";
    }

    private String getUriKey(String mdObjUuid, ModuleType moduleType, MDObjectBase mdObj) {
        return mdObjUuid + "/" + getModuleTypeUuid(moduleType, mdObj);
    }

    private String getUriKey(String objectId, String propertyId) {
        return objectId + "/" + propertyId;
    }

    private void addAllModulesToList(Map<String, URI> uriListByKey, MDObjectBase mdObj) {
        String mdObjUuid = mdObj.getUuid();
        Map<URI, ModuleType> modulesByType = mdObj.getModulesByType();
        modulesByType.forEach((uri, moduleType) -> {
            uriListByKey.put(getUriKey(mdObjUuid, moduleType, mdObj), uri);

            if (removeSupport != SupportVariant.NONE) {
                SupportVariant moduleSupportVariant = conf.getModuleSupport(uri).values().stream()
                        .min(Comparator.naturalOrder())
                        .orElse(SupportVariant.NONE);
                if (moduleSupportVariant.compareTo(removeSupport) <= 0) {
                    coverageData.put(uri, new HashMap<>());
                    return;
                }
            }

            Tokenizer tokenizer = null;
            try {
                tokenizer = new Tokenizer(Files.readString(Path.of(uri)));
            } catch (IOException e) {
                logger.error(e.getLocalizedMessage());
                return;
            }

            int[] linesToCover = Trees.getDescendants(tokenizer.getAst()).stream()
                    .filter(node -> !(node instanceof TerminalNodeImpl))
                    .filter(Coverage41C::mustCovered)
                    .mapToInt(node -> ((BSLParserRuleContext) node).getStart().getLine())
                    .distinct().toArray();
            Map<BigDecimal, Boolean> coverMap = new HashMap<>();
            for(int lineNumber : linesToCover) {
                coverMap.put(new BigDecimal(lineNumber), false);
            }
            coverageData.put(uri, coverMap);

        });
    }

    private static boolean mustCovered(Tree node) {
        // the same as in BSL LS
        return node instanceof BSLParser.StatementContext
                || node instanceof BSLParser.GlobalMethodCallContext
                || node instanceof BSLParser.Var_nameContext;
    }

}
