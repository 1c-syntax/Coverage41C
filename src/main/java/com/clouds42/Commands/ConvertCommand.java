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
package com.clouds42.Commands;

import com.clouds42.BuildConfig;
import com.clouds42.CommandLineOptions.*;
import com.clouds42.ConfiguredCoverage;
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = PipeMessages.CONVERT_COMMAND, mixinStandardHelpOptions = true, version = BuildConfig.APP_VERSION,
        description = "Convert results from internal uuid-based format",
        sortOptions = false)
public class ConvertCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Mixin
    private OutputOptions outputOptions;

    @Mixin
    private ConvertOptions convertOptions;

    @Mixin
    private MetadataOptions metadataOptions;

    @Mixin
    private LoggingOptions loggingOptions;

    @Override
    public Integer call() throws Exception {

        String srcDirExtensionName = convertOptions.getExtensionName();
        metadataOptions.validate(srcDirExtensionName);

        File rawInput = convertOptions.getInputRawXmlFile();
        Map<Element, String> rawFiles = readRawFiles(rawInput);
        Map<URI, Map<BigDecimal, Integer>> coverageData = new HashMap<URI,Map<BigDecimal, Integer>>();

        ConfiguredCoverage.Prepared prepared = ConfiguredCoverage.prepare(metadataOptions, srcDirExtensionName,
                coverageData, outputOptions.getOutputFile(), rawInput, false);
        Map<String, URI> uriListByKey = prepared.modules();

        // without -e and --extension the extension attribute is ignored: -s may point to sources of any extension, as before
        boolean matchExtensions = !srcDirExtensionName.isEmpty() || !metadataOptions.getExtensionSources().isEmpty();
        if (matchExtensions && rawFiles.keySet().stream().noneMatch(file -> file.hasAttribute("extension"))) {
            logger.warn("RAW file doesn't contain modules of extensions: it was made by previous version" +
                    " or extensions code wasn't executed. All modules are treated as modules of --srcDir sources");
            matchExtensions = false;
        }

        // configuration and extensions with sources, other modules are skipped
        Set<String> sourcesExtensionNames = new HashSet<>();
        sourcesExtensionNames.add(srcDirExtensionName);
        metadataOptions.getExtensionSources().forEach(source -> sourcesExtensionNames.add(source.getName()));
        int skippedModulesCount = 0;

        for (Map.Entry<Element, String> entry : rawFiles.entrySet()) {
            Element fileNode = entry.getKey();
            String extensionName = matchExtensions ? fileNode.getAttribute("extension") : srcDirExtensionName;
            boolean hasSources = sourcesExtensionNames.contains(extensionName);
            String fileKey = Utils.getExtensionUriKey(extensionName, entry.getValue());
            URI fileUri = hasSources ? uriListByKey.get(fileKey) : null;
            if (fileUri == null) {
                if (hasSources) {
                    logger.error("Can't find file key: {}", fileKey);
                } else {
                    skippedModulesCount++;
                }
                continue;
            }
            Map<BigDecimal, Integer> coverMap = coverageData.get(fileUri);
            if (!coverMap.isEmpty()) {
                NodeList lineNoNodeList = fileNode.getChildNodes();
                for (int lineNoNodeNumber = 0; lineNoNodeNumber < lineNoNodeList.getLength(); lineNoNodeNumber++) {
                    Node lineNoNode = lineNoNodeList.item(lineNoNodeNumber);
                    if (!lineNoNode.getNodeName().equals("lineToCover")) {
                        continue;
                    }
                    if (!lineNoNode.getAttributes().getNamedItem("covered").getTextContent().equals("true")) {
                        continue;
                    }
                    String number = lineNoNode.getAttributes().getNamedItem("lineNumber").getTextContent();
                    BigDecimal lineNo = readRawLineNumber(number, entry.getValue());
                    if (!coverMap.containsKey(lineNo)) {
                        if (loggingOptions.isVerbose()) {
                            logger.info("Can't find line to cover {} in module {}", lineNo, fileUri);
                            try (Stream<String> lines = Files.lines(Paths.get(fileUri))) {
                                String specific_line_n = lines.skip(lineNo.longValue() - 1).findFirst().get();
                                logger.info(">>> {}", specific_line_n);
                            } catch (Exception e) {
                                logger.error(e.getLocalizedMessage());
                            }
                        }
                    } else {
                        coverMap.put(lineNo, 1);
                    }
                }
            }
        }
        if (skippedModulesCount > 0) {
            logger.info("Skipped {} modules of configuration or extensions without sources", skippedModulesCount);
        }
        prepared.writeReport();
        boolean coverageWritten = Utils.tryDumpCoverageFile(coverageData, metadataOptions, outputOptions);
        if (!coverageWritten) {
            return CommandLine.ExitCode.SOFTWARE;
        }
        logger.info("Convert done");

        return CommandLine.ExitCode.OK;
    }

    private static Map<Element, String> readRawFiles(File input)
            throws IOException, ParserConfigurationException, SAXException {
        Document document;
        try (FileInputStream stream = new FileInputStream(input)) {
            document = Utils.newInputXmlBuilder().parse(stream);
        }
        Element coverage = document.getDocumentElement();
        if (!coverage.getTagName().equals("coverage")) {
            throw new IllegalArgumentException("Expected a RAW coverage XML document");
        }
        Map<Element, String> files = new LinkedHashMap<>();
        for (Node node = coverage.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element file) {
                if (!file.getTagName().equals("file")) {
                    throw new IllegalArgumentException("Expected a file entry in the RAW coverage XML");
                }
                String key = rawModuleKey(file);
                validateRawLines(file, key);
                files.put(file, key);
            }
        }
        return files;
    }

    private static void validateRawLines(Element file, String key) {
        for (Node node = file.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element line)) {
                continue;
            }
            if (!line.getTagName().equals("lineToCover")) {
                throw new IllegalArgumentException("Unexpected RAW element in module " + key + ": " + line.getTagName());
            }
            String covered = line.getAttribute("covered");
            if (!covered.equals("true") && !covered.equals("false")) {
                throw new IllegalArgumentException("Invalid RAW covered value in module " + key + ": " + covered);
            }
            readRawLineNumber(line.getAttribute("lineNumber"), key);
        }
    }

    private static BigDecimal readRawLineNumber(String number, String key) {
        try {
            int line = new BigDecimal(number).intValueExact();
            if (line > 0) {
                return BigDecimal.valueOf(line);
            }
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid RAW line number in module " + key + ": " + number, invalid);
        }
        throw new IllegalArgumentException("Invalid RAW line number in module " + key + ": " + number);
    }

    private static String rawModuleKey(Element file) {
        String path = file.getAttribute("path");
        String key = path.startsWith("/") ? path.substring(1) : path;
        String[] parts = key.split("/", -1);
        // A RAW key has two IDs. Finished Generic Coverage reports contain BSL file paths.
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()
                || parts[1].toLowerCase(Locale.ROOT).endsWith(".bsl")) {
            throw new IllegalArgumentException("Unsupported RAW module path: " + path
                    + ". Expected object ID/module type ID");
        }
        return Utils.getUriKey(parts[0], parts[1]);
    }
}
