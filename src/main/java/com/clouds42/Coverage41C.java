package com.clouds42;

import com._1c.g5.v8.dt.debug.core.runtime.client.RuntimeDebugClientException;
import com._1c.g5.v8.dt.debug.model.area.DebugAreaInfo;
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
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "Coverage41C", mixinStandardHelpOptions = true, version = "Coverage41C 1.3-SNAPSHOT",
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

    @Option(names = {"-x", "--externalDataProcessor"}, description = "External data processor (or external report) url",
            defaultValue = "")
    private String externalDataProcessorUrl;

    @Option(names = {"-s", "--srcDir"}, description = "Directory with sources exported to xml", defaultValue = "")
    private String srcDirName;

    @Option(names = {"-P", "--projectDir"}, description = "Directory with project", defaultValue = "")
    private String projectDirName;

    @Option(names = {"-o", "--out"}, description = "Output file name")
    private File outputFile;

    @Option(names = {"-u", "--debugger"}, description = "Debugger url. Default - ${DEFAULT-VALUE}", defaultValue = "http://127.0.0.1:1550/")
    private String debugServerUrl;

    @Option(names = {"-p", "--password"}, description = "Dbgs password", interactive = true)
    private String password;

    @Option(names = {"-p:env", "--password:env"}, description = "Password environment variable name", defaultValue = "")
    private String passwordEnv;

    @Option(names = {"-n", "--areanames"}, description = "Debug area names (not for general use!)")
    private List<String> debugAreaNames;

    @Option(names = {"-t", "--timeout"}, description = "Ping timeout. Default - ${DEFAULT-VALUE}", defaultValue = "1000")
    private Integer pingTimeout;

    @Option(names = {"-r", "--removeSupport"}, description = "Remove support values: ${COMPLETION-CANDIDATES}. Default - ${DEFAULT-VALUE}", defaultValue = "NONE")
    private SupportVariant removeSupport;

    @Option(names = "--verbose", description = "If you need more logs. Default - ${DEFAULT-VALUE}", defaultValue = "false")
    private Boolean verbose;

    private RuntimeDebugHttpClient client;
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final Map<URI, Map<BigDecimal, Boolean>> coverageData = new HashMap<URI,Map<BigDecimal, Boolean>> () {
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
    private static final String DUMP_COMMAND = "DUMP";
    private static final String CLEAN_COMMAND = "CLEAN";
    private static final String CHECK_COMMAND = "CHECK";
    private static final String EXIT_RESULT = "OK";
    private static final String FAIL_RESULT = "ER";

    private static final int EXIT_SUCCESS = 0;
    private static final int EXIT_FAILURE = -1;

    private ServerSocket serverSocket;
    private CompletableFuture<Boolean> commandListenServer;

    private Configuration conf;

    private AtomicBoolean stopExecution = new AtomicBoolean(false);
    private boolean rawMode = false;
    private boolean systemStarted = false;

    private enum CommandAction {
        start,
        stop,
        dump,
        clean,
        check
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Coverage41C()).execute(args);
        System.exit(exitCode);
    }

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
                    logger.info("Get command: " + line.trim());
                    if (DUMP_COMMAND.equals(line.trim())) {
                        dumpCoverageFile();
                        out.println(EXIT_RESULT);
                        return true;
                    } else if (CLEAN_COMMAND.equals(line.trim())) {
                        coverageData.forEach((uri, bigDecimalBooleanMap) -> {
                            for (var key : bigDecimalBooleanMap.keySet()) {
                                bigDecimalBooleanMap.put(key, false);
                            }
                        });
                        out.println(EXIT_RESULT);
                        return true;
                    } else if (CHECK_COMMAND.equals(line.trim())) {
                        for (int i = 0; i<60; i++) {
                            if (systemStarted) {
                                out.println(EXIT_RESULT);
                                return true;
                            }
                            Thread.sleep(1000);
                        }
                        out.println(FAIL_RESULT);
                        return true;
                    }
                }
            } while (line == null || !line.trim().equals(EXIT_COMMAND));
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

        URI debugUri = URI.create(debugServerUrl);
        String pipeName;
        if (isWindows) {
            pipeName = String.format("\\\\.\\pipe\\COVER_%s_%s", infobaseAlias, debugUri.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_"));
        } else {
            Path tempDir = Files.createTempDirectory("coverage41c");
            Path sock = tempDir.resolve(String.format("%s_%s.sock", infobaseAlias, debugUri.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_")));
            pipeName = sock.toString();
        }
        if (commandAction != CommandAction.start) {
            logger.info("Trying to send command to main application...");
            Socket client;
            if (isWindows) {
                client = new Win32NamedPipeSocket(pipeName);
            } else {
                client = new UnixDomainSocket(pipeName);
            }
            PrintWriter pipeOut = new PrintWriter(client.getOutputStream(), true);
            BufferedReader pipeIn = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String commandText;
            if (commandAction == CommandAction.stop) {
                commandText = EXIT_COMMAND;
            } else if (commandAction == CommandAction.dump) {
                commandText = DUMP_COMMAND;
            } else if (commandAction == CommandAction.check) {
                commandText = CHECK_COMMAND;
            } else if (commandAction == CommandAction.clean) {
                commandText = CLEAN_COMMAND;
            } else {
                throw new Exception("Unknown command");
            }
            pipeOut.println(commandText);
            logger.info("Command send finished: " + commandText);
            String result = "";
            for(int i = 0; i < 60; i++) {
                try {
                    result = pipeIn.readLine();
                    break;
                } catch(IOException e) {
                    logger.info("Can't read answer from main app...");
                    Thread.sleep(1000);
                }
            }
            if (result.equals(EXIT_RESULT)) {
                logger.info("Command success: " + commandText);
                return EXIT_SUCCESS;
            } else {
                logger.info("Command failed: " + commandText);
                return EXIT_FAILURE;
            }
        }

        if (srcDirName.isEmpty() && projectDirName.isEmpty()) {
            logger.info("Sources directory not set. Enabling RAW mode");
            rawMode = true;
        }

        if (projectDirName.isEmpty() && !srcDirName.isEmpty()) {
            // for backward compatibility
            projectDirName = srcDirName;
            srcDirName = "";
        }

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
        if (debugAreaNames == null) {
            debugAreaNames = new ArrayList<>();
        }

        Map<String, URI> uriListByKey = new HashMap<>();

        if (!rawMode) {
            logger.info("Reading configuration sources...");

            if (externalDataProcessorUrl.isEmpty()) {

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

            } else {

                // TODO: EDT

                conf = Configuration.create();

                File externalDataprocessorRootXmlFile = Path.of(projectDirName).resolve(srcDirName).toFile();
                FileInputStream fileIS = new FileInputStream(externalDataprocessorRootXmlFile);
                DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = builderFactory.newDocumentBuilder();
                Document xmlDocument = builder.parse(fileIS);
                XPath xPath = XPathFactory.newInstance().newXPath();
                String nameExpression = "/MetaDataObject/ExternalDataProcessor/Properties/Name/text()";
                String uuidExpression = "/MetaDataObject/ExternalDataProcessor/@uuid";
                String externalDataProcessorName = (String) xPath.compile(nameExpression).evaluate(xmlDocument,
                        XPathConstants.STRING);
                String externalDataProcessorUuid = (String) xPath.compile(uuidExpression).evaluate(xmlDocument,
                        XPathConstants.STRING);
                uriListByKey.put(getUriKey(externalDataProcessorUuid, ModuleType.ObjectModule, null),
                        Paths.get(externalDataprocessorRootXmlFile.getParent(),
                                externalDataProcessorName, "Ext", "ObjectModule.bsl").toUri());

                var externalDataProcessorPath = Paths.get(
                        externalDataprocessorRootXmlFile.getParent(),
                                externalDataProcessorName, "Forms");
                try (Stream<Path> walk = Files.list(externalDataProcessorPath)) {

                    List<String> result = walk.map(x -> x.toString())
                            .filter(f -> f.endsWith(".xml")).collect(Collectors.toList());

                    XPath formXPath = XPathFactory.newInstance().newXPath();
                    String formUuidExpression = "/MetaDataObject/Form/@uuid";
                    String formNameExpression = "/MetaDataObject/Form/Properties/Name/text()";

                    result.forEach(formXmlFileName -> {
                        try {
                            FileInputStream formFileIS = new FileInputStream(formXmlFileName);
                            Document formXmlDocument = builder.parse(formFileIS);
                            String formUuid = (String) formXPath.compile(formUuidExpression).evaluate(formXmlDocument,
                                    XPathConstants.STRING);
                            String formName = (String) formXPath.compile(formNameExpression).evaluate(formXmlDocument,
                                    XPathConstants.STRING);
                            uriListByKey.put(getUriKey(formUuid, ModuleType.FormModule, null),
                                    Paths.get(externalDataProcessorPath.toString(),
                                            formName, "Ext", "Form", "Module.bsl").toUri());
                        } catch (Exception e) {
                            logger.error("Can't read form xml: " + e.getLocalizedMessage());
                        }
                    });

                    uriListByKey.forEach((s, uri) -> {
                        addCoverageData(uri);
                    });

                } catch (IOException e) {
                    e.printStackTrace();
                }

            }

            logger.info("Configuration sources reading DONE");
        }

        boolean firstRun = true;

        try {
            client.configure(debugServerUrl, debugServerUuid, infobaseAlias);
        } catch (RuntimeDebugClientException e) {
            logger.error(e.getLocalizedMessage());
            return EXIT_FAILURE;
        }

        Runtime.getRuntime().addShutdownHook(new Thread()
        {
            public void run()
            {
                gracefulShutdown(null);
            }
        });

        if (password != null) {
            if (password.trim().isEmpty()) {
                if (!passwordEnv.isEmpty()) {
                    password = System.getenv(passwordEnv);
                }
            }
        }

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
                                if (verbose && !externalDataProcessorsUriSet.contains(moduleUrl)) {
                                    logger.info("Found external data processor: " + moduleUrl);
                                    externalDataProcessorsUriSet.add(moduleUrl);
                                }
                                String moduleExtensionName = moduleId.getExtensionName();
                                if (this.extensionName.equals(moduleExtensionName)
                                    && this.externalDataProcessorUrl.equals(moduleUrl)) {
                                    String objectId = moduleId.getObjectID();
                                    String propertyId = moduleId.getPropertyID();
                                    String key = getUriKey(objectId, propertyId);

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
                                                    if (verbose.booleanValue()) {
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

                    systemStarted = true;
                } catch (RuntimeDebugClientException e1) {
                    logger.error(e1.getLocalizedMessage());
                    return EXIT_FAILURE;
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
        return EXIT_SUCCESS;
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

        dumpCoverageFile();

        if (serverSocket != null) {
            if (serverPipeOut != null) {
                serverPipeOut.println(EXIT_RESULT);
            }
        }

        stopExecution.set(true);

        logger.info("Bye!");
    }

    private void dumpCoverageFile() {
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
                logger.info("Coverage: " + Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100. + "%");
            }
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            DOMSource source = new DOMSource(doc);
            StreamResult outputStream;
            if (outputFile == null) {
                outputStream = new StreamResult(System.out);
            } else {
                outputStream = new StreamResult(new FileOutputStream(outputFile));
            }
            transformer.transform(source, outputStream);
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    private String getModuleTypeUuid(ModuleType moduleType, MDObjectBase mdObject) {
        if (moduleType == ModuleType.CommandModule) {
            return "078a6af8-d22c-4248-9c33-7e90075a3d2c";
        } else if (moduleType == ModuleType.ObjectModule) {
            return "a637f77f-3840-441d-a1c3-699c8c5cb7e0";
        } else if (moduleType == ModuleType.ManagerModule) {
            if (mdObject instanceof SettingsStorage) {
                return "0c8cad23-bf8c-468e-b49e-12f1927c048b";
            } else {
                return "d1b64a2c-8078-4982-8190-8f81aefda192";
            }
        } else if (moduleType == ModuleType.FormModule) {
            return "32e087ab-1491-49b6-aba7-43571b41ac2b";
        } else if (moduleType == ModuleType.RecordSetModule) {
            return "9f36fd70-4bf4-47f6-b235-935f73aab43f";
        } else if (moduleType == ModuleType.ValueManagerModule) {
            return "3e58c91f-9aaa-4f42-8999-4baf33907b75";
        } else if (moduleType == ModuleType.ManagedApplicationModule) {
            return "d22e852a-cf8a-4f77-8ccb-3548e7792bea";
        } else if (moduleType == ModuleType.SessionModule) {
            return "9b7bbbae-9771-46f2-9e4d-2489e0ffc702";
        } else if (moduleType == ModuleType.ExternalConnectionModule) {
            return "a4a9c1e2-1e54-4c7f-af06-4ca341198fac";
        } else if (moduleType == ModuleType.OrdinaryApplicationModule) {
            return "a78d9ce3-4e0c-48d5-9863-ae7342eedf94";
        } else if (moduleType == ModuleType.HTTPServiceModule
            || moduleType == ModuleType.WEBServiceModule
            || moduleType == ModuleType.CommonModule) {
            return "d5963243-262e-4398-b4d7-fb16d06484f6";
        } else if (moduleType == ModuleType.ApplicationModule
            || moduleType == ModuleType.Unknown)
        {

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

            addCoverageData(uri);

        });
    }

    private void addCoverageData(URI uri) {
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
    }

    private static boolean mustCovered(Tree node) {
        // the same as in BSL LS
        return node instanceof BSLParser.StatementContext
                || node instanceof BSLParser.GlobalMethodCallContext
                || node instanceof BSLParser.Var_nameContext;
    }

}
