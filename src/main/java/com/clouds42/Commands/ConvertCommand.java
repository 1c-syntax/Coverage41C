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
import com.clouds42.CoverageHits;
import com.clouds42.PipeMessages;
import com.clouds42.SourceKey;
import com.clouds42.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileInputStream;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    @Mixin
    private FilterOptions filterOptions;

    private final Map<String, URI> legacySources = new HashMap<>();
    private final Set<String> ambiguousLegacyKeys = new HashSet<>();

    private URI resolveFileKey(String key, Map<String, URI> sources) {
        if (SourceKey.isQualified(key)) {
            String legacy = SourceKey.legacy(key);
            return sources.get(metadataOptions.hasMultipleSources() ? key : legacy);
        }
        if (!metadataOptions.hasMultipleSources()) {
            return sources.get(key);
        }
        if (ambiguousLegacyKeys.contains(key)) {
            throw new IllegalArgumentException("Ambiguous legacy module key: " + key
                    + "; collect RAW data again with a version that preserves source identity");
        }
        return legacySources.get(key);
    }

    private boolean matchesSelectedSource(String key) {
        return key.equals(SourceKey.qualify(SourceKey.legacy(key), filterOptions.getExtensionName(),
                filterOptions.getExternalDataProcessorUrl()));
    }

    private String rawFileKey(Node fileNode, boolean sourceIdentity, boolean includesExtensionAttributes) {
        String key = rawPath(fileNode);
        if (SourceKey.isQualified(key)) {
            return key;
        }
        String url = attribute(fileNode, "sourceUrl");
        if (sourceIdentity || !url.isEmpty()) {
            return SourceKey.qualify(key, attribute(fileNode, "extension"), url);
        }
        if (includesExtensionAttributes && (metadataOptions.hasMultipleSources()
                || !filterOptions.getExtensionName().isEmpty())) {
            return SourceKey.qualify(key, attribute(fileNode, "extension"), "");
        }
        if (metadataOptions.hasMultipleSources() && !metadataOptions.usesNamedSources()) {
            // Historical RAW files without extension attributes belong to the tree passed via -s.
            return SourceKey.qualify(key, filterOptions.getExtensionName(), "");
        }
        return key;
    }

    private static String rawPath(Node fileNode) {
        String path = attribute(fileNode, "path");
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String attribute(Node fileNode, String name) {
        Node attribute = fileNode.getAttributes().getNamedItem(name);
        return attribute == null ? "" : attribute.getTextContent();
    }

    private static boolean includesExtensionAttributes(NodeList files) {
        for (int index = 0; index < files.getLength(); index++) {
            if (files.item(index).getAttributes().getNamedItem("extension") != null) {
                return true;
            }
        }
        return false;
    }

    // A mixed RAW file can contain other sources. Check every record before rejecting the selection.
    private void verifySelectedSource(NodeList files, Map<String, URI> sources,
                                      boolean sourceIdentity, boolean includesExtensionAttributes) {
        if (metadataOptions.hasMultipleSources()) {
            return;
        }
        boolean selectedSourceFound = false;
        Set<String> otherSources = new TreeSet<>();
        for (int index = 0; index < files.getLength(); index++) {
            String key = rawFileKey(files.item(index), sourceIdentity, includesExtensionAttributes);
            if (SourceKey.isQualified(key)) {
                if (matchesSelectedSource(key)) {
                    selectedSourceFound = true;
                } else {
                    otherSources.add(SourceKey.selectionHint(key));
                }
            } else if (sources.containsKey(key)) {
                selectedSourceFound = true;
            }
        }
        if (!otherSources.isEmpty() && !selectedSourceFound) {
            throw new IllegalArgumentException("No RAW measurements for the selected source. Select the matching source: "
                    + String.join("; ", otherSources));
        }
        otherSources.forEach(hint -> logger.warn("Ignoring RAW source. "
                + "To convert that source use {} with its sources directory", hint));
    }

    private static Element validateRawReport(Document document) {
        Element coverage = document.getDocumentElement();
        if (!"coverage".equals(coverage.getTagName())) {
            throw new IllegalArgumentException("Expected a RAW coverage XML report");
        }
        boolean sourceIdentity = "true".equals(coverage.getAttribute("sourceIdentity"));
        for (Node node = coverage.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element file)) {
                continue;
            }
            if (!"file".equals(file.getTagName()) || file.getAttribute("path").isBlank()) {
                throw new IllegalArgumentException("Expected a file with a path in the RAW coverage report");
            }
            SourceKey.validateRawKey(rawPath(file),
                    sourceIdentity || file.hasAttribute("extension") || file.hasAttribute("sourceUrl"));
            for (Node child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element line)) {
                    continue;
                }
                String covered = line.getAttribute("covered");
                if (!"lineToCover".equals(line.getTagName()) || !("true".equals(covered) || "false".equals(covered))
                        || line.getElementsByTagName("*").getLength() != 0) {
                    throw new IllegalArgumentException("Invalid line entry in the RAW coverage report");
                }
                parseRawLineNumber(line.getAttribute("lineNumber"));
            }
        }
        return coverage;
    }

    private static BigDecimal parseRawLineNumber(String number) {
        try {
            return CoverageHits.lineNumber(new BigDecimal(number));
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid RAW line number: " + number, e);
        }
    }

    @Override
    public Integer call() throws Exception {

        metadataOptions.validateSourceFilters(filterOptions.getExtensionName(), filterOptions.getExternalDataProcessorUrl());
        Map<URI, Map<BigDecimal, Integer>> coverageData = new HashMap<>();

        File rawInput = convertOptions.getInputRawXmlFile();
        try (FileInputStream fileIS = new FileInputStream(rawInput)) {
            DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
            builderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = builderFactory.newDocumentBuilder();
            Element coverage = validateRawReport(builder.parse(fileIS));

            Map<String, URI> uriListByKey = Utils.readMetadataForConversion(metadataOptions,
                    filterOptions.getExtensionName(), coverageData, rawInput);
            legacySources.clear();
            ambiguousLegacyKeys.clear();
            if (metadataOptions.hasMultipleSources()) {
                uriListByKey.forEach((key, uri) -> {
                    String legacy = SourceKey.legacy(key);
                    if (legacySources.putIfAbsent(legacy, uri) != null) {
                        ambiguousLegacyKeys.add(legacy);
                    }
                });
            }

            NodeList fileNodeList = coverage.getElementsByTagName("file");
            boolean includesExtensions = includesExtensionAttributes(fileNodeList);
            boolean sourceIdentity = "true".equals(coverage.getAttribute("sourceIdentity"));
            verifySelectedSource(fileNodeList, uriListByKey, sourceIdentity, includesExtensions);
            for(int fileNodeNumber = 0; fileNodeNumber < fileNodeList.getLength(); fileNodeNumber++) {
                Node fileNode = fileNodeList.item(fileNodeNumber);
                String fileKey = rawFileKey(fileNode, sourceIdentity, includesExtensions);
                if (!metadataOptions.hasMultipleSources() && SourceKey.isQualified(fileKey)
                        && !matchesSelectedSource(fileKey)) {
                    continue;
                }
                URI fileUri = resolveFileKey(fileKey, uriListByKey);
                if (fileUri == null) {
                    logger.error("Can't find file key: {}", fileKey);
                    continue;
                }
                Map<BigDecimal, Integer> coverMap = coverageData.get(fileUri);
                if (!coverMap.isEmpty()) {
                    NodeList lineNoNodeList = fileNode.getChildNodes();
                    for (int lineNoNodeNumber = 0; lineNoNodeNumber < lineNoNodeList.getLength(); lineNoNodeNumber++) {
                        if (!(lineNoNodeList.item(lineNoNodeNumber) instanceof Element entry)
                                || !"lineToCover".equals(entry.getTagName())
                                || !"true".equals(entry.getAttribute("covered"))) {
                            continue;
                        }
                        BigDecimal lineNo = parseRawLineNumber(entry.getAttribute("lineNumber"));
                        if (!coverMap.containsKey(lineNo)) {
                            if (loggingOptions.isVerbose()) {
                                logger.info("Can't find line to cover {} in module {}", lineNo, fileUri);
                                try (Stream<String> lines = Files.lines(Paths.get(fileUri))) {
                                    lines.skip(lineNo.longValue() - 1).findFirst()
                                            .ifPresent(line -> logger.info(">>> {}", line));
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
        }
        Utils.dumpCoverageFile(coverageData, metadataOptions, outputOptions);
        logger.info("Convert done");

        return CommandLine.ExitCode.OK;
    }
}
