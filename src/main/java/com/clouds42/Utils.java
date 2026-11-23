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
package com.clouds42;

import com.clouds42.CommandLineOptions.ConnectionOptions;
import com.clouds42.CommandLineOptions.ExtensionSource;
import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.github._1c_syntax.bsl.mdclasses.CF;
import com.github._1c_syntax.bsl.mdclasses.MDClasses;
import com.github._1c_syntax.bsl.mdo.MD;
import com.github._1c_syntax.bsl.mdo.Module;
import com.github._1c_syntax.bsl.mdo.SettingsStorage;
import com.github._1c_syntax.bsl.parser.BSLLexer;
import com.github._1c_syntax.bsl.parser.BSLTokenizer;
import com.github._1c_syntax.bsl.types.ModuleType;
import com.github._1c_syntax.bsl.support.SupportVariant;
import de.vandermeer.asciitable.AsciiTable;
import de.vandermeer.asciitable.CWC_LongestLine;
import org.antlr.v4.runtime.Token;
import org.apache.commons.lang3.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Utils {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static String getModuleTypeUuid(ModuleType moduleType, MD mdObject) {
        return switch (moduleType) {
            case CommandModule -> "078a6af8-d22c-4248-9c33-7e90075a3d2c";
            case ObjectModule -> "a637f77f-3840-441d-a1c3-699c8c5cb7e0";
            case ManagerModule -> mdObject instanceof SettingsStorage
                    ? "0c8cad23-bf8c-468e-b49e-12f1927c048b"
                    : "d1b64a2c-8078-4982-8190-8f81aefda192";
            case FormModule -> "32e087ab-1491-49b6-aba7-43571b41ac2b";
            case RecordSetModule -> "9f36fd70-4bf4-47f6-b235-935f73aab43f";
            case ValueManagerModule -> "3e58c91f-9aaa-4f42-8999-4baf33907b75";
            case ManagedApplicationModule -> "d22e852a-cf8a-4f77-8ccb-3548e7792bea";
            case SessionModule -> "9b7bbbae-9771-46f2-9e4d-2489e0ffc702";
            case ExternalConnectionModule -> "a4a9c1e2-1e54-4c7f-af06-4ca341198fac";
            case OrdinaryApplicationModule -> "a78d9ce3-4e0c-48d5-9863-ae7342eedf94";
            case HTTPServiceModule, WEBServiceModule, CommonModule -> "d5963243-262e-4398-b4d7-fb16d06484f6";
            case ApplicationModule, UNKNOWN -> {
                logger.info("Couldn't find UUID for module type: {} for object {}", moduleType, mdObject.getName());
                yield "UNKNOWN";
            }
            default -> "UNKNOWN";
        };
    }

    private static String getUriKey(String mdObjUuid, ModuleType moduleType, MD mdObj) {
        return getUriKey(mdObjUuid, getModuleTypeUuid(moduleType, mdObj));
    }

    public static String getUriKey(String objectId, String propertyId) {
        return normalizeModuleId(objectId) + "/" + normalizeModuleId(propertyId);
    }

    private static String normalizeModuleId(String id) {
        return id != null && id.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                ? id.toLowerCase(Locale.ROOT) : id;
    }

    /**
     * Ключ модуля с учётом расширения: модуль сопоставляется только с деревом исходников своего расширения
     *
     * @param extensionName - имя расширения, пустое - конфигурация (или внешняя обработка)
     * @param uriKey        - ключ модуля из {@link #getUriKey(String, String)}
     * @return для конфигурации - uriKey без изменений, для расширения - uriKey с именем расширения
     */
    public static String getExtensionUriKey(String extensionName, String uriKey) {
        if (extensionName.isEmpty()) {
            return uriKey;
        }
        return extensionName + "/" + uriKey;
    }

    /**
     * URI модуля в RAW режиме: file:///objectId/propertyId, имя расширения хранится во фрагменте URI.
     * Путь без фрагмента пишется в атрибут path сырого файла, как и раньше
     */
    public static URI getRawUri(String extensionName, String uriKey) {
        if (extensionName.isEmpty()) {
            return URI.create("file:///" + uriKey);
        }
        try {
            return new URI("file", "", "/" + uriKey, null, extensionName);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String getRawExtensionName(URI rawUri) {
        String extensionName = rawUri.getFragment();
        return extensionName == null ? "" : extensionName;
    }


    private static final Pattern COVER_ON = Pattern.compile(
            "Cover:(?:вкл|on)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern COVER_OFF = Pattern.compile(
            "Cover:(?:выкл|off)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Pattern COVER_AUTO = Pattern.compile(
            "Cover:(?:авто|auto)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static void addCoverageData(Map<URI, Map<BigDecimal, Integer>> coverageData, URI uri) {
        BSLTokenizer tokenizer;
        try {
            tokenizer = LinesToCoverage.createTokenizer(Files.readString(Path.of(uri)));
        } catch (IOException e) {
            logger.error(e.getLocalizedMessage());
            return;
        }

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        List<Range<Integer>> coverageIgnorance = new ArrayList<>();
        Stack<Integer> coverageIgnoranceStartStack = new Stack();
        List<Range<Integer>> coverageAutoIgnorance = new ArrayList<>();
        Stack<Integer> coverageAutoIgnoranceStartStack = new Stack();

        if (linesToCover.length > 0) {

            List<Token> allTokens = tokenizer.getTokens();
            List<Token> comments = allTokens.stream()
                    .filter(token -> token.getType() == BSLLexer.LINE_COMMENT)
                    .toList();

            for (Token comment : comments) {
                int commentLine = comment.getLine();
                Matcher offMatcher = COVER_OFF.matcher(comment.getText());
                if (offMatcher.find()) {
                    coverageIgnoranceStartStack.push(commentLine);
                } else {
                    Matcher autoMatcher = COVER_AUTO.matcher(comment.getText());
                    if (autoMatcher.find()) {
                        coverageAutoIgnoranceStartStack.push(commentLine);
                    } else {
                        Matcher onMatcher = COVER_ON.matcher(comment.getText());
                        if (onMatcher.find()) {
                            while (!coverageIgnoranceStartStack.empty()) {
                                coverageIgnorance.add(Range.of(coverageIgnoranceStartStack.pop(), commentLine));
                            }
                            while (!coverageAutoIgnoranceStartStack.empty()) {
                                coverageAutoIgnorance.add(Range.of(coverageAutoIgnoranceStartStack.pop(), commentLine));
                            }
                        }
                    }
                }
            }
            while (!coverageIgnoranceStartStack.empty()) {
                coverageIgnorance.add(
                        Range.of(coverageIgnoranceStartStack.pop(), linesToCover[linesToCover.length - 1]));
            }
            while (!coverageAutoIgnoranceStartStack.empty()) {
                coverageAutoIgnorance.add(
                        Range.of(coverageAutoIgnoranceStartStack.pop(), linesToCover[linesToCover.length - 1]));
            }

            linesToCover = Arrays.stream(linesToCover).filter(i ->
              coverageIgnorance.stream().noneMatch(integerRange -> integerRange.contains(i))).toArray();
        }

        Map<BigDecimal, Integer> coverMap = new HashMap<>();
        for (int lineNumber : linesToCover) {
            int countValue = 0;
            if (coverageAutoIgnorance.stream().anyMatch(integerRange -> integerRange.contains(lineNumber))) {
                countValue = -1;
            }
            coverMap.put(new BigDecimal(lineNumber), countValue);
        }
        coverageData.put(uri, coverMap);
    }

    /**
     * Читает исходники основного дерева (-P/-s) и деревьев расширений (--extension)
     *
     * @param srcDirExtensionName - расширение, которому принадлежит основное дерево (-e), пустое - конфигурация
     * @return URI модулей по ключу {@link #getExtensionUriKey(String, String)}
     */
    public static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                                String srcDirExtensionName,
                                                Map<URI, Map<BigDecimal, Integer>> coverageData)
            throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {

        boolean rawMode = false;
        if (metadataOptions.isRawMode()) {
            logger.info("Sources directory not set. Enabling RAW mode");
            rawMode = true;
        } else {
            logger.info("Project directory: {}", metadataOptions.getProjectDirName());
        }

        Map<String, URI> uriListByKey = new HashMap<>();

        if (!rawMode) {

            Path projectPath = Path.of(metadataOptions.getProjectDirName());
            readSources(projectPath.resolve(metadataOptions.getSrcDirName()), srcDirExtensionName,
                    metadataOptions, uriListByKey, coverageData);
            for (ExtensionSource extensionSource : metadataOptions.getExtensionSources()) {
                readSources(projectPath.resolve(extensionSource.getPath()), extensionSource.getName(),
                        metadataOptions, uriListByKey, coverageData);
            }

            logger.info("Configuration sources reading DONE");
        }

        return uriListByKey;
    }

    private static void readSources(Path rootPath,
                                    String extensionName,
                                    MetadataOptions metadataOptions,
                                    Map<String, URI> uriListByKey,
                                    Map<URI, Map<BigDecimal, Integer>> coverageData)
            throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {

        if (extensionName.isEmpty()) {
            logger.info("Reading configuration sources from root path: {}", rootPath.toAbsolutePath());
        } else {
            logger.info("Reading extension {} sources from root path: {}", extensionName, rootPath.toAbsolutePath());
        }

        if (Files.isDirectory(rootPath)) {
            readConfigurationSources(rootPath, extensionName, metadataOptions.getRemoveSupport(),
                    uriListByKey, coverageData);
        } else {
            readExternalDataProcessorSources(rootPath, extensionName, uriListByKey, coverageData);
        }
    }

    private static void readConfigurationSources(Path rootPath,
                                                 String extensionName,
                                                 SupportVariant removeSupport,
                                                 Map<String, URI> uriListByKey,
                                                 Map<URI, Map<BigDecimal, Integer>> coverageData) {

        if (!(MDClasses.createConfiguration(rootPath) instanceof CF conf)) {
            throw new IllegalArgumentException("Unknown source format: " + rootPath);
        }

        Map<URI, MD> moduleOwners = conf.getModulesByObject();
        for (Module module : conf.getAllModules()) {
            if (module.isProtected() || !Files.exists(Path.of(module.getUri()))) {
                // no source code: module is protected with password (Module.bin) or empty (not exported)
                continue;
            }
            MD mdObj = moduleOwners.get(module.getUri());

            uriListByKey.put(getExtensionUriKey(extensionName, getUriKey(mdObj.getUuid(), module.getModuleType(), mdObj)),
                    module.getUri());

            if (isSupportRemoved(module, removeSupport)) {
                coverageData.put(module.getUri(), new HashMap<>());
            } else {
                addCoverageData(coverageData, module.getUri());
            }
        }
    }

    private static boolean isSupportRemoved(Module module, SupportVariant removeSupport) {
        return removeSupport != SupportVariant.NONE
                && module.getSupportVariant().compareTo(removeSupport) <= 0;
    }

    static void readExternalDataProcessorSources(Path rootPath,
                                                         String extensionName,
                                                         Map<String, URI> uriListByKey,
                                                         Map<URI, Map<BigDecimal, Integer>> coverageData)
            throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {

        discoverExternalDataProcessorSources(rootPath, ignored -> {}, logger::error).forEach((key, uri) -> {
            uriListByKey.put(getExtensionUriKey(extensionName, key), uri);
            addCoverageData(coverageData, uri);
        });
    }

    public static DocumentBuilder newInputXmlBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder();
    }

    static Map<String, URI> discoverExternalDataProcessorSources(Path rootPath, Consumer<URI> formDescriptor,
                                                                  Consumer<String> reportProblem)
            throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
        DocumentBuilder builder = newInputXmlBuilder();
        Document xmlDocument = builder.parse(rootPath.toFile());
        return switch (xmlDocument.getDocumentElement().getTagName()) {
            case "MetaDataObject" -> readConfiguratorExternalDataProcessor(rootPath, xmlDocument, builder,
                    formDescriptor, reportProblem);
            case "mdclass:ExternalDataProcessor" -> readEdtExternalDataProcessor(rootPath, xmlDocument, reportProblem);
            default -> throw new IllegalArgumentException("Unknown source format: " + rootPath);
        };
    }

    private static Map<String, URI> readConfiguratorExternalDataProcessor(Path rootPath, Document xmlDocument,
                                                                           DocumentBuilder builder,
                                                                           Consumer<URI> formDescriptor,
                                                                           Consumer<String> reportProblem)
            throws XPathExpressionException {

        Map<String, URI> uriListByKey = new HashMap<>();
        XPath xPath = XPathFactory.newInstance().newXPath();
        Path parentPath = rootPath.toAbsolutePath().getParent();
        String externalDataProcessorName = com.google.common.io.Files.getNameWithoutExtension(rootPath.toString());
        String externalDataProcessorUuid = (String) xPath.compile("/MetaDataObject/ExternalDataProcessor/@uuid")
                .evaluate(xmlDocument, XPathConstants.STRING);
        requireProcessorId(externalDataProcessorUuid, rootPath);
        uriListByKey.put(getUriKey(externalDataProcessorUuid, ModuleType.ObjectModule, null),
                parentPath.resolve(Paths.get(externalDataProcessorName, "Ext", "ObjectModule.bsl")).toUri());

        Path formsPath = parentPath.resolve(Paths.get(externalDataProcessorName, "Forms"));
        if (Files.notExists(formsPath)) {
            return uriListByKey;
        }
        XPathExpression formUuidExpression = xPath.compile("/MetaDataObject/Form/@uuid");
        XPathExpression formNameExpression = xPath.compile("/MetaDataObject/Form/Properties/Name/text()");
        try (Stream<Path> walk = Files.list(formsPath)) {
            walk.filter(formXmlFile -> formXmlFile.toString().endsWith(".xml"))
                    .forEach(formXmlFile -> {
                        formDescriptor.accept(formXmlFile.toUri());
                        try {
                            Document formXmlDocument = builder.parse(formXmlFile.toFile());
                            String formUuid = (String) formUuidExpression.evaluate(formXmlDocument, XPathConstants.STRING);
                            String formName = (String) formNameExpression.evaluate(formXmlDocument, XPathConstants.STRING);
                            if (formUuid.isBlank() || formName.isBlank()) {
                                throw new IllegalArgumentException("Missing form ID or name");
                            }
                            uriListByKey.put(getUriKey(formUuid, ModuleType.FormModule, null),
                                    formsPath.resolve(Paths.get(formName, "Ext", "Form", "Module.bsl")).toUri());
                        } catch (IOException | SAXException | XPathExpressionException | IllegalArgumentException e) {
                            reportProblem.accept("Cannot read form XML " + formXmlFile + ": " + e.getLocalizedMessage());
                        }
                    });
        } catch (IOException | UncheckedIOException e) {
            reportProblem.accept("Cannot read forms in " + formsPath + ": " + e.getLocalizedMessage());
        }
        return uriListByKey;
    }

    private static void requireProcessorId(String id, Path descriptor) {
        if (id.isBlank()) {
            throw new IllegalArgumentException("Missing external data processor ID: " + descriptor);
        }
    }

    private static Map<String, URI> readEdtExternalDataProcessor(Path rootPath, Document xmlDocument,
                                                               Consumer<String> reportProblem)
            throws XPathExpressionException {

        Map<String, URI> uriListByKey = new HashMap<>();
        XPath xPath = XPathFactory.newInstance().newXPath();
        Path parentPath = rootPath.toAbsolutePath().getParent();
        String externalDataProcessorUuid = xmlDocument.getDocumentElement().getAttribute("uuid");
        requireProcessorId(externalDataProcessorUuid, rootPath);
        uriListByKey.put(getUriKey(externalDataProcessorUuid, ModuleType.ObjectModule, null),
                parentPath.resolve("ObjectModule.bsl").toUri());

        Path formsPath = parentPath.resolve("Forms");
        NodeList formsNodeList = (NodeList) xPath.compile("/*/forms")
                .evaluate(xmlDocument, XPathConstants.NODESET);
        for (int nodeNumber = 0; nodeNumber < formsNodeList.getLength(); nodeNumber++) {
            Element formsNode = (Element) formsNodeList.item(nodeNumber);
            String formUuid = formsNode.getAttribute("uuid");
            String formName = getEdtFormName(formsNode);
            if (formUuid.isBlank() || formName.isBlank()) {
                reportProblem.accept("Missing form ID or name in " + rootPath + ": " + formUuid + " " + formName);
                continue;
            }
            uriListByKey.put(getUriKey(formUuid, ModuleType.FormModule, null),
                    formsPath.resolve(Paths.get(formName, "Module.bsl")).toUri());
        }
        return uriListByKey;
    }

    private static String getEdtFormName(Node formsNode) {
        NodeList childNodes = formsNode.getChildNodes();
        for (int childNodeNumber = 0; childNodeNumber < childNodes.getLength(); childNodeNumber++) {
            Node childNode = childNodes.item(childNodeNumber);
            if (childNode.getNodeName().equals("name")) {
                return childNode.getTextContent();
            }
        }
        return "";
    }

    public static void dumpCoverageFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                        MetadataOptions metadataOptions,
                                        OutputOptions outputOptions) {
        tryDumpCoverageFile(coverageData, metadataOptions, outputOptions);
    }

    public static boolean tryDumpCoverageFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                               MetadataOptions metadataOptions, OutputOptions outputOptions) {
        if (outputOptions.getOutputFormat() == OutputOptions.OutputFormat.GENERIC_COVERAGE) {
            return dumpGenericCoverageFile(coverageData, metadataOptions, outputOptions);
        }
        if (outputOptions.getOutputFormat() == OutputOptions.OutputFormat.LCOV) {
            return dumpLcovFile(coverageData, metadataOptions, outputOptions);
        }
        if (outputOptions.getOutputFormat() == OutputOptions.OutputFormat.COBERTURA) {
            return dumpCoberturaFile(coverageData, metadataOptions, outputOptions);
        }
        logger.info("Unknown format");
        return false;
    }

    private static boolean dumpCoberturaFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                             MetadataOptions metadataOptions, OutputOptions outputOptions) {
        DocumentBuilderFactory icFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder icBuilder;
        try {
            long linesToCover = 0;
            long coveredLinesCount = 0;
            for (Map<BigDecimal, Integer> bigDecimalMap : coverageData.values()) {
                linesToCover += bigDecimalMap.values().stream().filter(value -> value >= 0).count();
                coveredLinesCount += bigDecimalMap.values().stream().filter(value -> value > 0).count();
            }
            logger.info("Lines to cover: {}", linesToCover);
            logger.info("Covered lines: {}", coveredLinesCount);
            if (linesToCover > 0) {
                logger.info("Coverage: {}%", Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100.);
            }

            URI projectUri = Path.of(metadataOptions.getProjectDirName()).toUri();
            icBuilder = icFactory.newDocumentBuilder();
            Document doc = icBuilder.newDocument();
            Element mainRootElement = doc.createElement("coverage");
            String lineRate = "0.0";
            if (linesToCover > 0) {
                lineRate = String.valueOf(Math.floorDiv(coveredLinesCount * 100, linesToCover) / 100.);
            }
            mainRootElement.setAttribute("line-rate", lineRate);
            mainRootElement.setAttribute("branch-rate", "0.0");
            mainRootElement.setAttribute("lines-covered", String.valueOf(coveredLinesCount));
            mainRootElement.setAttribute("lines-valid", String.valueOf(linesToCover));
            mainRootElement.setAttribute("branches-covered", "0");
            mainRootElement.setAttribute("branches-valid", "0");
            mainRootElement.setAttribute("complexity", "0");
            mainRootElement.setAttribute("version", "0");
            mainRootElement.setAttribute("timestamp", String.valueOf(Instant.now().getEpochSecond()));
            doc.appendChild(mainRootElement);

            Element sourcesElement = doc.createElement("sources");
            mainRootElement.appendChild(sourcesElement);

            Element sourceElement = doc.createElement("source");
            String[] projectDirArray = projectUri.getPath().split("/");
            if (projectDirArray.length > 2) {
                sourceElement.setTextContent("/builds/" + projectDirArray[projectDirArray.length - 2] + "/" + projectDirArray[projectDirArray.length - 1] + "/");
            } else {
                sourcesElement.setTextContent(projectUri.getPath());
            }
            sourcesElement.appendChild(sourceElement);

            Element packagesElement = doc.createElement("packages");
            mainRootElement.appendChild(packagesElement);

            Element packageElement = doc.createElement("package");
            packageElement.setAttribute("name", "Main");
            packageElement.setAttribute("line-rate", lineRate);
            packageElement.setAttribute("branch-rate", "0.0");
            packageElement.setAttribute("complexity", "0");
            packagesElement.appendChild(packageElement);

            Element classesElement = doc.createElement("classes");
            packageElement.appendChild(classesElement);

            coverageData.forEach((uri, bigDecimalsMap) -> {
                if (bigDecimalsMap.isEmpty()) {
                    return;
                }

                long fileLinesToCover = bigDecimalsMap.values().stream().filter(value -> value >= 0).count();
                long fileCoveredLinesCount = bigDecimalsMap.values().stream().filter(value -> value > 0).count();
                String fileLineRate = "0.0";
                if (fileLinesToCover > 0) {
                    fileLineRate = String.valueOf(Math.floorDiv(fileCoveredLinesCount * 100, fileLinesToCover) / 100.);
                }

                Element classElement = doc.createElement("class");
                classElement.setAttribute("name", projectUri.relativize(uri).getPath());
                classElement.setAttribute("line-rate", fileLineRate);
                classElement.setAttribute("branch-rate", "0.0");
                classElement.setAttribute("complexity", "0");
                classElement.setAttribute("filename", projectUri.relativize(uri).getPath());
                classesElement.appendChild(classElement);

                Element methodsElement = doc.createElement("methods");
                classElement.appendChild(methodsElement);
                Element linesElement = doc.createElement("lines");
                classElement.appendChild(linesElement);

                bigDecimalsMap.forEach((bigDecimal, hits) -> {
                    if (hits >= 0) {
                        Element lineElement = doc.createElement("line");
                        lineElement.setAttribute("hits", String.valueOf(hits));
                        lineElement.setAttribute("number", bigDecimal.toString());
                        lineElement.setAttribute("branch", "false");
                        linesElement.appendChild(lineElement);
                    }
                });
            });
            writeCoverageXml(doc, outputOptions.getOutputFile());
            return true;
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage());
            return false;
        }
    }

    private static boolean dumpGenericCoverageFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
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
                String extensionName = getRawExtensionName(uri);
                if (!extensionName.isEmpty()) {
                    // RAW mode: module of extension, no attribute - configuration
                    fileElement.setAttribute("extension", extensionName);
                }
                bigDecimalsMap.forEach((bigDecimal, integer) -> {
                    if (integer >= 0) {
                        Element lineElement = doc.createElement("lineToCover");
                        lineElement.setAttribute("covered", Boolean.toString(integer > 0));
                        lineElement.setAttribute("lineNumber", bigDecimal.toString());
                        fileElement.appendChild(lineElement);
                    }
                });
                mainRootElement.appendChild(fileElement);
            });
            long linesToCover = 0;
            long coveredLinesCount = 0;
            for (Map<BigDecimal, Integer> bigDecimalMap : coverageData.values()) {
                linesToCover += bigDecimalMap.values().stream().filter(value -> value >= 0).count();
                coveredLinesCount += bigDecimalMap.values().stream().filter(value -> value > 0).count();
            }
            logger.info("Lines to cover: {}", linesToCover);
            logger.info("Covered lines: {}", coveredLinesCount);
            if (linesToCover > 0) {
                logger.info("Coverage: {}%", Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100.);
            }
            writeCoverageXml(doc, outputOptions.getOutputFile());
            return true;
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage());
            return false;
        }
    }

    private static void writeCoverageXml(Document document, File destination) throws TransformerException, IOException {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        DOMSource source = new DOMSource(document);
        if (destination == null) {
            PrintStream stdout = System.out;
            requireUsableStdout(stdout);
            transformer.transform(source, new StreamResult(stdout));
            requireUsableStdout(stdout);
        } else {
            try (OutputStream output = new FileOutputStream(destination)) {
                transformer.transform(source, new StreamResult(output));
            }
        }
    }

    private static void requireUsableStdout(PrintStream stdout) throws IOException {
        boolean error = stdout.checkError();
        if (error || Thread.currentThread().isInterrupted()) {
            throw new IOException("Cannot write coverage to standard output: I/O error or interrupted write");
        }
    }

    public static String normalizeXml(String xmlString) {
        String result = "";
        if (!xmlString.startsWith("<")) {
            return xmlString;
        }
        Pattern p = Pattern.compile("<\\w\\S*");
        Matcher m = p.matcher(xmlString);
        String replaceFromTag = "";
        if (m.find()) {
            replaceFromTag = xmlString.substring(m.start() + 1, m.end());
        }
        if (replaceFromTag.isEmpty()) {
            return result;
        }
        result = xmlString;
        String tag = "</" + replaceFromTag + ">";
        int indx = xmlString.indexOf(tag);
        if (indx != -1) {
            result = result.substring(0, indx + tag.length());
        }

        indx = result.indexOf("/>");
        if (indx != -1) {
            String candidate = result.substring(0, indx + 2);
            m = p.matcher(candidate);
            String candidateTag = "";
            while (m.find()) {
                candidateTag = result.substring(m.start() + 1, m.end());
            }

            if (replaceFromTag.equalsIgnoreCase(candidateTag)) {
                result = candidate;
            }

        }

        return result;
    }

    private static boolean dumpLcovFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                     MetadataOptions metadataOptions,
                                     OutputOptions outputOptions) {

        try {
            PrintStream stdout = outputOptions.getOutputFile() == null ? System.out : null;
            OutputStreamWriter outputStream;
            if (stdout != null) {
                requireUsableStdout(stdout);
                outputStream = new OutputStreamWriter(stdout);
            } else {
                outputStream = new OutputStreamWriter(new FileOutputStream(outputOptions.getOutputFile()), StandardCharsets.UTF_8);
            }
            PrintWriter writer = new PrintWriter(outputStream);
            try {
                URI projectUri = Path.of(metadataOptions.getProjectDirName()).toUri();

                coverageData.forEach((uri, bigDecimalsMap) -> {
                    if (bigDecimalsMap.isEmpty()) {
                        return;
                    }
                    writer.println("TN:");
                    writer.printf("SF:%s\n", projectUri.relativize(uri).getPath());
                    bigDecimalsMap.forEach((bigDecimal, integer) -> {
                        if (integer >= 0) {
                            writer.printf("DA:%s,%d\n", bigDecimal.toString(), integer);
                        }
                    });
                    writer.printf("LH:%d\n", bigDecimalsMap.values().stream().filter(aInteger -> aInteger > 0).count());
                    writer.printf("LF:%d\n", bigDecimalsMap.values().stream().filter(hits -> hits >= 0).count());
                    writer.println("end_of_record");
                });
                long linesToCover = 0;
                long coveredLinesCount = 0;
                for (Map<BigDecimal, Integer> bigDecimalMap : coverageData.values()) {
                    linesToCover += bigDecimalMap.values().stream().filter(value -> value >= 0).count();
                    coveredLinesCount += bigDecimalMap.values().stream().filter(value -> value > 0).count();
                }
                logger.info("Lines to cover: {}", linesToCover);
                logger.info("Covered lines: {}", coveredLinesCount);
                if (linesToCover > 0) {
                    logger.info("Coverage: {}%", Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100.);
                }
            } finally {
                if (stdout == null) {
                    writer.close();
                } else {
                    writer.flush();
                }
            }
            if (writer.checkError() || (stdout != null && (stdout.checkError() || Thread.currentThread().isInterrupted()))) {
                logger.error("Cannot write coverage: {}", stdout == null ? outputOptions.getOutputFile() : "standard output");
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage());
            return false;
        }
    }

    public static void printCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                          MetadataOptions metadataOptions) {
        List<Object[]> dataList = new LinkedList<>();
        URI projectUri = Path.of(metadataOptions.getProjectDirName()).toUri();
        coverageData.forEach((uri, bigDecimalsMap) -> {
            if (bigDecimalsMap.isEmpty()) {
                return;
            }
            String path = projectUri.relativize(uri).getPath();
            long linesToCover = bigDecimalsMap.values().stream().filter(aInteger -> aInteger >= 0).count();
            long coveredLinesCount = bigDecimalsMap.values().stream().filter(aInteger -> aInteger > 0).count();
            double coverage = Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100.;
            Object[] dataRow = {
                    path,
                    linesToCover,
                    coveredLinesCount,
                    coverage};
            dataList.add(dataRow);
        });
        dataList.sort(Comparator.comparing(objects -> ((Double) objects[3])));
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
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        URI debugUri = URI.create(connectionOptions.getDebugServerUrl());
        String pipeId = connectionOptions.getInfobaseAlias() + "_"
                + debugUri.toString().replaceAll("[^a-zA-Z0-9-_.]", "_");
        String pipeName;
        if (isWindows) {
            pipeName = "\\\\.\\pipe\\COVER_" + pipeId;
        } else {
            File tempDirFile = new File("/tmp/coverage41c/");
            tempDirFile.mkdirs();
            Path tempDir = tempDirFile.toPath();
            Path sock = tempDir.resolve(pipeId + ".sock");
            pipeName = sock.toString();
        }
        return pipeName;
    }

    public static boolean isProcessStillAlive(Integer pid) {
        String OS = System.getProperty("os.name").toLowerCase();
        String command;
        if (OS.contains("win")) {
            logger.debug("Check alive Windows mode. Pid: [{}]", pid);
            command = "cmd /c tasklist /FI \"PID eq " + pid + "\"";
        } else if (OS.contains("nix") || OS.contains("nux")) {
            logger.debug("Check alive Linux/Unix mode. Pid: [{}]", pid);
            command = "ps -p " + pid;
        } else {
            logger.warn("Unsuported OS: Check alive for Pid: [{}] return false", pid);
            return false;
        }
        return isProcessIdRunning(String.valueOf(pid), command);
    }

    private static boolean isProcessIdRunning(String pid, String command) {
        logger.debug("Command [{}]", command);
        try {
            Runtime rt = Runtime.getRuntime();
            Process pr = rt.exec(command);

            InputStreamReader isReader = new InputStreamReader(pr.getInputStream());
            BufferedReader bReader = new BufferedReader(isReader);
            String strLine;
            while ((strLine = bReader.readLine()) != null) {
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
