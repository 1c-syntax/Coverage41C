package com.clouds42;

import com.clouds42.CommandLineOptions.ConnectionOptions;
import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.github._1c_syntax.bsl.parser.BSLParser;
import com.github._1c_syntax.bsl.parser.BSLParserRuleContext;
import com.github._1c_syntax.bsl.parser.Tokenizer;
import com.github._1c_syntax.mdclasses.mdo.Command;
import com.github._1c_syntax.mdclasses.mdo.Form;
import com.github._1c_syntax.mdclasses.mdo.MDObjectBase;
import com.github._1c_syntax.mdclasses.mdo.SettingsStorage;
import com.github._1c_syntax.mdclasses.metadata.Configuration;
import com.github._1c_syntax.mdclasses.metadata.additional.ModuleType;
import com.github._1c_syntax.mdclasses.metadata.additional.SupportVariant;
import de.vandermeer.asciitable.AsciiTable;
import de.vandermeer.asciitable.CWC_LongestLine;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.antlr.v4.runtime.tree.Tree;
import org.antlr.v4.runtime.tree.Trees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Utils {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static String getModuleTypeUuid(ModuleType moduleType, MDObjectBase mdObject) {
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

    private static String getUriKey(String mdObjUuid, ModuleType moduleType, MDObjectBase mdObj) {
        return mdObjUuid + "/" + getModuleTypeUuid(moduleType, mdObj);
    }

    public static String getUriKey(String objectId, String propertyId) {
        return objectId + "/" + propertyId;
    }

    private static void addAllModulesToList(Configuration conf, MetadataOptions metadataOptions,
                                     Map<String, URI> uriListByKey, MDObjectBase mdObj,
                                     Map<URI, Map<BigDecimal, Boolean>> coverageData) {
        String mdObjUuid = mdObj.getUuid();
        Map<URI, ModuleType> modulesByType = mdObj.getModulesByType();
        modulesByType.forEach((uri, moduleType) -> {
            uriListByKey.put(getUriKey(mdObjUuid, moduleType, mdObj), uri);

            if (metadataOptions.getRemoveSupport() != SupportVariant.NONE) {
                SupportVariant moduleSupportVariant = conf.getModuleSupport(uri).values().stream()
                        .min(Comparator.naturalOrder())
                        .orElse(SupportVariant.NONE);
                if (moduleSupportVariant.compareTo(metadataOptions.getRemoveSupport()) <= 0) {
                    coverageData.put(uri, new HashMap<>());
                    return;
                }
            }

            addCoverageData(coverageData, uri);

        });
    }

    private static void addCoverageData(Map<URI, Map<BigDecimal, Boolean>> coverageData, URI uri) {
        Tokenizer tokenizer = null;
        try {
            tokenizer = new Tokenizer(Files.readString(Path.of(uri)));
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage());
            return;
        }

        int[] linesToCover = Trees.getDescendants(tokenizer.getAst()).stream()
                .filter(node -> !(node instanceof TerminalNodeImpl))
                .filter(Utils::mustCovered)
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

    public static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                                Map<URI, Map<BigDecimal, Boolean>> coverageData) throws Exception {

        boolean rawMode = false;
        if (metadataOptions.isRawMode()) {
            logger.info("Sources directory not set. Enabling RAW mode");
            rawMode = true;
        }

        Map<String, URI> uriListByKey = new HashMap<>();

        if (!rawMode) {
            logger.info("Reading configuration sources...");

            Path rootPath = Path.of(metadataOptions.getProjectDirName())
                    .resolve(metadataOptions.getSrcDirName());

            if (Files.isDirectory(rootPath)) {

                Configuration conf = Configuration.create(rootPath);

                Set<MDObjectBase> configurationChildren = conf.getChildren();
                for (MDObjectBase mdObj : configurationChildren) {

                    addAllModulesToList(conf, metadataOptions, uriListByKey, mdObj, coverageData);

                    List<Command> commandsList = mdObj.getCommands();
                    if (commandsList != null) {
                        commandsList.forEach(cmd -> {
                            addAllModulesToList(conf, metadataOptions, uriListByKey, cmd, coverageData);
                        });
                    }

                    List<Form> formsList = mdObj.getForms();
                    if (formsList != null) {
                        formsList.forEach(form -> {
                            addAllModulesToList(conf, metadataOptions, uriListByKey, form, coverageData);
                        });
                    }
                }

            } else {

                File externalDataprocessorRootXmlFile = rootPath.toFile();

                XPath xPath = XPathFactory.newInstance().newXPath();
                FileInputStream fileIS = new FileInputStream(externalDataprocessorRootXmlFile);
                DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = builderFactory.newDocumentBuilder();
                Document xmlDocument = builder.parse(fileIS);
                String documentRootTagName = xmlDocument.getDocumentElement().getTagName();
                if (documentRootTagName.equals("MetaDataObject")) {
                    // CONFIGURATOR
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


                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else if (documentRootTagName.equals("mdclass:ExternalDataProcessor")) {
                    // EDT
                    var externalDataProcessorPath = Paths.get(
                            externalDataprocessorRootXmlFile.getParent(),
                            "Forms");
                    String uuidExpression = "/ExternalDataProcessor/@uuid";
                    String externalDataProcessorUuid = (String) xPath.compile(uuidExpression).evaluate(xmlDocument,
                            XPathConstants.STRING);
                    uriListByKey.put(getUriKey(externalDataProcessorUuid, ModuleType.ObjectModule, null),
                            Paths.get(externalDataprocessorRootXmlFile.getParent(),
                                    "ObjectModule.bsl").toUri());
                    String formUuidExpression = "/ExternalDataProcessor/forms";
                    NodeList externalDataProcessorFormsNodeList = (NodeList) xPath.compile(formUuidExpression).evaluate(xmlDocument,
                            XPathConstants.NODESET);
                    for (int nodeNumber = 0; nodeNumber < externalDataProcessorFormsNodeList.getLength(); nodeNumber++) {
                        Node externalDataProcessorFormsNode = externalDataProcessorFormsNodeList.item(nodeNumber);
                        String formUuid = externalDataProcessorFormsNode.getAttributes().getNamedItem("uuid").getTextContent();
                        String formName = "";
                        NodeList childNodes = externalDataProcessorFormsNode.getChildNodes();
                        for (int childNodeNumber = 0; childNodeNumber < childNodes.getLength(); childNodeNumber++) {
                            Node childNode = childNodes.item(childNodeNumber);
                            if (childNode.getNodeName().equals("name")) {
                                formName = childNode.getTextContent();
                                break;
                            }
                        }
                        if (formName.isEmpty()) {
                            logger.error("Can't find form name: " + formUuid);
                            continue;
                        }
                        uriListByKey.put(getUriKey(formUuid, ModuleType.FormModule, null),
                                Paths.get(externalDataProcessorPath.toString(),
                                        formName, "Module.bsl").toUri());
                    }
                } else {
                    throw new Exception("Unknown source format");
                }

                uriListByKey.forEach((s, uri) -> {
                    addCoverageData(coverageData, uri);
                });

            }

            logger.info("Configuration sources reading DONE");
        }

        return uriListByKey;
    }

    public static void dumpCoverageFile(Map<URI, Map<BigDecimal, Boolean>> coverageData,
                                        MetadataOptions metadataOptions,
                                        OutputOptions outputOptions) {
        DocumentBuilderFactory icFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder icBuilder;
        try {

            URI projectUri = Path.of(metadataOptions.getProjectDirName()).toUri();
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
            if (outputOptions.getOutputFile() == null) {
                outputStream = new StreamResult(System.out);
            } else {
                outputStream = new StreamResult(new FileOutputStream(outputOptions.getOutputFile()));
            }
            transformer.transform(source, outputStream);
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage());
        }
    }

    public static void printCoverageStats(Map<URI, Map<BigDecimal, Boolean>> coverageData,
                                          MetadataOptions metadataOptions) {
        List<Object[]> dataList = new LinkedList<>();
        URI projectUri = Path.of(metadataOptions.getProjectDirName()).toUri();
        coverageData.forEach((uri, bigDecimalsMap) -> {
            if (bigDecimalsMap.isEmpty()) {
                return;
            }
            String path = projectUri.relativize(uri).getPath();
            long linesToCover = bigDecimalsMap.size();
            long coveredLinesCount = bigDecimalsMap.values().stream().filter(aBoolean -> aBoolean.booleanValue()).count();
            Double coverage = Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100.;
            Object[] dataRow = {
                    path,
                    linesToCover,
                    coveredLinesCount,
                    coverage};
            dataList.add(dataRow);
        });
        Collections.sort(dataList, Comparator.comparing(objects -> ((Double) objects[3])));
        AsciiTable at = new AsciiTable();
        at.addRule();
        at.addRow("Path", "Lines to cover", "Covered lines", "Coverage, %");
        at.addRule();
        dataList.forEach(objects -> {
            at.addRow(objects);
            at.addRule();
        });
        CWC_LongestLine cwc = new CWC_LongestLine();
        at.getRenderer().setCWC(cwc);
        String rend = at.render();
        System.out.println(rend);
    }

    public static String getPipeName(ConnectionOptions connectionOptions) throws IOException {
        boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

        URI debugUri = URI.create(connectionOptions.getDebugServerUrl());
        String pipeName;
        if (isWindows) {
            pipeName = String.format("\\\\.\\pipe\\COVER_%s_%s", connectionOptions.getInfobaseAlias(),
                    debugUri.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_"));
        } else {
            Path tempDir = Files.createTempDirectory("coverage41c");
            Path sock = tempDir.resolve(String.format("%s_%s.sock", connectionOptions.getInfobaseAlias(),
                    debugUri.toString().replaceAll("[^a-zA-Z0-9-_\\.]", "_")));
            pipeName = sock.toString();
        }
        return pipeName;
    }

    public static boolean isProcessStillAlive(Integer pid) {
        String OS = System.getProperty("os.name").toLowerCase();
        String command = null;
        if (OS.indexOf("win") >= 0) {
            logger.debug("Check alive Windows mode. Pid: [{}]", pid);
            command = "cmd /c tasklist /FI \"PID eq " + pid + "\"";
        } else if (OS.indexOf("nix") >= 0 || OS.indexOf("nux") >= 0) {
            logger.debug("Check alive Linux/Unix mode. Pid: [{}]", pid);
            command = "ps -p " + pid;
        } else {
            logger.warn("Unsuported OS: Check alive for Pid: [{}] return false", pid);
            return false;
        }
        return isProcessIdRunning(String.valueOf(pid), command);
    }

    private static boolean isProcessIdRunning(String pid, String command) {
        logger.debug("Command [{}]",command );
        try {
            Runtime rt = Runtime.getRuntime();
            Process pr = rt.exec(command);

            InputStreamReader isReader = new InputStreamReader(pr.getInputStream());
            BufferedReader bReader = new BufferedReader(isReader);
            String strLine = null;
            while ((strLine= bReader.readLine()) != null) {
                if (strLine.contains(" " + pid + " ")) {
                    return true;
                }
            }

            return false;
        } catch (Exception ex) {
            logger.warn("Got exception using system command [{}].", command, ex);
            return true;
        }
    }
}
