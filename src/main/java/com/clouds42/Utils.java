/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2025
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
import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.github._1c_syntax.bsl.mdclasses.CF;
import com.github._1c_syntax.bsl.mdclasses.MDClasses;
import com.github._1c_syntax.bsl.mdo.MD;
import com.github._1c_syntax.bsl.mdo.SettingsStorage;
import com.github._1c_syntax.bsl.parser.BSLLexer;
import com.github._1c_syntax.bsl.parser.BSLTokenizer;
import com.github._1c_syntax.bsl.support.SupportVariant;
import com.github._1c_syntax.bsl.types.ModuleType;
import com.github._1c_syntax.utils.CaseInsensitivePattern;
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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Utils {

  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final Pattern COVER_ON = CaseInsensitivePattern.compile("Cover:(?:вкл|on)");

  private static final Pattern COVER_OFF = CaseInsensitivePattern.compile("Cover:(?:выкл|off)");

  private static final Pattern COVER_AUTO = CaseInsensitivePattern.compile("Cover:(?:авто|auto)");

  public static String getModuleTypeUuid(ModuleType moduleType, MD mdObject) {
    String result = "";
    switch (moduleType) {
      case CommandModule -> result = "078a6af8-d22c-4248-9c33-7e90075a3d2c";
      case ObjectModule -> result = "a637f77f-3840-441d-a1c3-699c8c5cb7e0";
      case ManagerModule -> {
        if (mdObject instanceof SettingsStorage) {
          result = "0c8cad23-bf8c-468e-b49e-12f1927c048b";
        } else {
          result = "d1b64a2c-8078-4982-8190-8f81aefda192";
        }
      }
      case FormModule -> result = "32e087ab-1491-49b6-aba7-43571b41ac2b";
      case RecordSetModule -> result = "9f36fd70-4bf4-47f6-b235-935f73aab43f";
      case ValueManagerModule -> result = "3e58c91f-9aaa-4f42-8999-4baf33907b75";
      case ManagedApplicationModule -> result = "d22e852a-cf8a-4f77-8ccb-3548e7792bea";
      case SessionModule -> result = "9b7bbbae-9771-46f2-9e4d-2489e0ffc702";
      case ExternalConnectionModule -> result = "a4a9c1e2-1e54-4c7f-af06-4ca341198fac";
      case OrdinaryApplicationModule -> result = "a78d9ce3-4e0c-48d5-9863-ae7342eedf94";
      case HTTPServiceModule, WEBServiceModule, CommonModule -> result = "d5963243-262e-4398-b4d7-fb16d06484f6";
      case ApplicationModule, UNKNOWN ->
        logger.info("Couldn't find UUID for module type: {} for object {}", moduleType, mdObject.getName());
      default -> result = "UNKNOWN";
    }
    return result;
  }

  private static String getUriKey(String mdObjUuid, ModuleType moduleType, MD mdObj) {
    return mdObjUuid + "/" + getModuleTypeUuid(moduleType, mdObj);
  }

  public static String getUriKey(String objectId, String propertyId) {
    return objectId + "/" + propertyId;
  }

  private static void addCoverageData(Map<URI, Map<BigDecimal, Integer>> coverageData, URI uri) {
    BSLTokenizer tokenizer;
    try {
      tokenizer = new BSLTokenizer(Files.readString(Path.of(uri)));
    } catch (IOException e) {
      logger.error(e.getLocalizedMessage());
      return;
    }

    int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

    List<Range<Integer>> coverageIgnorance = new ArrayList<>();
    Stack<Integer> coverageIgnoranceStartStack = new Stack<>();
    List<Range<Integer>> coverageAutoIgnorance = new ArrayList<>();
    Stack<Integer> coverageAutoIgnoranceStartStack = new Stack<>();

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
                coverageIgnorance.add(Range.between(coverageIgnoranceStartStack.pop(), commentLine));
              }
              while (!coverageAutoIgnoranceStartStack.empty()) {
                coverageAutoIgnorance.add(Range.between(coverageAutoIgnoranceStartStack.pop(), commentLine));
              }
            }
          }
        }
      }
      while (!coverageIgnoranceStartStack.empty()) {
        coverageIgnorance.add(
          Range.between(coverageIgnoranceStartStack.pop(), linesToCover[linesToCover.length - 1]));
      }
      while (!coverageAutoIgnoranceStartStack.empty()) {
        coverageAutoIgnorance.add(
          Range.between(coverageAutoIgnoranceStartStack.pop(), linesToCover[linesToCover.length - 1]));
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

  public static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                              Map<URI, Map<BigDecimal, Integer>> coverageData) throws Exception {

    boolean rawMode = false;
    if (metadataOptions.isRawMode()) {
      logger.info("Sources directory not set. Enabling RAW mode");
      rawMode = true;
    } else {
      logger.info("Project directory: {}", metadataOptions.getProjectDirName());
    }

    Map<String, URI> uriListByKey = new HashMap<>();

    if (!rawMode) {

      Path rootPath = Path.of(metadataOptions.getProjectDirName())
        .resolve(metadataOptions.getSrcDirName());
      logger.info("Reading configuration sources from root path: {}", rootPath.toAbsolutePath());

      if (Files.isDirectory(rootPath)) {

        var conf = (CF) MDClasses.createConfiguration(rootPath);
        for (var module : conf.getAllModules()) {
          var mdObj = conf.getModulesByObject().getOrDefault(module.getUri(), conf);
          String mdObjUuid = mdObj.getUuid();
          uriListByKey.put(getUriKey(mdObjUuid, module.getModuleType(), mdObj), module.getUri());

          if (metadataOptions.getRemoveSupport() != SupportVariant.NONE) {
            SupportVariant moduleSupportVariant = module.getSupportVariant();
            if (moduleSupportVariant.compareTo(metadataOptions.getRemoveSupport()) <= 0) {
              coverageData.put(module.getUri(), new HashMap<>());
              continue;
            }
          }

          addCoverageData(coverageData, module.getUri());
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
          String uuidExpression = "/MetaDataObject/ExternalDataProcessor/@uuid";
          String externalDataProcessorName = com.google.common.io.Files.getNameWithoutExtension(rootPath.toString());
          String externalDataProcessorUuid = (String) xPath.compile(uuidExpression).evaluate(xmlDocument,
            XPathConstants.STRING);
          uriListByKey.put(getUriKey(externalDataProcessorUuid, ModuleType.ObjectModule, null),
            Paths.get(externalDataprocessorRootXmlFile.getParent(),
              externalDataProcessorName, "Ext", "ObjectModule.bsl").toUri());

          var externalDataProcessorPath = Paths.get(
            externalDataprocessorRootXmlFile.getParent(),
            externalDataProcessorName, "Forms");
          try (Stream<Path> walk = Files.list(externalDataProcessorPath)) {

            List<String> result = walk.map(Path::toString)
              .filter(f -> f.endsWith(".xml")).toList();

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
                logger.error("Can't read form xml: {}", e.getLocalizedMessage());
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
              logger.error("Can't find form name: {}", formUuid);
              continue;
            }
            uriListByKey.put(getUriKey(formUuid, ModuleType.FormModule, null),
              Paths.get(externalDataProcessorPath.toString(),
                formName, "Module.bsl").toUri());
          }
        } else {
          throw new Exception("Unknown source format");
        }

        uriListByKey.forEach((s, uri) -> addCoverageData(coverageData, uri));
      }

      logger.info("Configuration sources reading DONE");
    }

    return uriListByKey;
  }

  public static void dumpCoverageFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                      MetadataOptions metadataOptions,
                                      OutputOptions outputOptions) {
    switch (outputOptions.getOutputFormat()) {
      case GENERIC_COVERAGE -> dumpGenericCoverageFile(coverageData, metadataOptions, outputOptions);
      case LCOV -> dumpLcovFile(coverageData, metadataOptions, outputOptions);
      case COBERTURA -> dumpCoberturaFile(coverageData, metadataOptions, outputOptions);
      default -> logger.info("Unknown format");
    }
  }

  private static void dumpCoberturaFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                        MetadataOptions metadataOptions,
                                        OutputOptions outputOptions) {
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

        bigDecimalsMap.forEach((bigDecimal, hits) -> {
          if (hits >= 0) {
            Element lineElement = doc.createElement("line");
            lineElement.setAttribute("hits", String.valueOf(hits));
            lineElement.setAttribute("number", bigDecimal.toString());
            lineElement.setAttribute("branch", "false");
            classElement.appendChild(lineElement);
          }
        });
      });
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

  private static void dumpGenericCoverageFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
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

  private static void dumpLcovFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                   MetadataOptions metadataOptions,
                                   OutputOptions outputOptions) {

    try {
      OutputStreamWriter outputStream;
      if (outputOptions.getOutputFile() == null) {
        outputStream = new OutputStreamWriter(System.out);
      } else {
        outputStream = new OutputStreamWriter(new FileOutputStream(outputOptions.getOutputFile()), StandardCharsets.UTF_8);
      }
      PrintWriter writer = new PrintWriter(outputStream);

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
        writer.printf("LF:%d\n", bigDecimalsMap.size());
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

      writer.close();
    } catch (Exception e) {
      logger.error(e.getLocalizedMessage());
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
    String pipeName;
    if (isWindows) {
      pipeName = String.format("\\\\.\\pipe\\COVER_%s_%s", connectionOptions.getInfobaseAlias(),
        debugUri.toString().replaceAll("[^a-zA-Z0-9-_.]", "_"));
    } else {
      File tempDirFile = new File("/tmp/coverage41c/");
      tempDirFile.mkdirs();
      Path tempDir = tempDirFile.toPath();
      Path sock = tempDir.resolve(String.format("%s_%s.sock", connectionOptions.getInfobaseAlias(),
        debugUri.toString().replaceAll("[^a-zA-Z0-9-_.]", "_")));
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
