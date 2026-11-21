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
import org.w3c.dom.Text;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import javax.xml.parsers.DocumentBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
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
    private final OutputStream reportOutput;

    public ConvertCommand() {
        this(null);
    }

    public ConvertCommand(OutputStream reportOutput) {
        this.reportOutput = reportOutput;
    }


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

    private Map<String, String> selectedSourceUrls() throws IOException {
        String url = filterOptions.getExternalDataProcessorUrl();
        if (!metadataOptions.hasMultipleSources()) {
            return Map.of(filterOptions.getExtensionName(), url);
        }
        Map<String, String> selected = new HashMap<>();
        metadataOptions.getSourceDirectories(filterOptions.getExtensionName()).forEach((extension, path) ->
                selected.put(extension, Files.isRegularFile(path) ? url : ""));
        return selected;
    }

    private String rawFileKey(Node fileNode, boolean sourceIdentity, boolean includesExtensionAttributes,
                              boolean singleProcessorSelected, Map<String, URI> sources) {
        String key = rawPath(fileNode);
        if (SourceKey.isQualified(key)) {
            return key;
        }
        String extension = attribute(fileNode, "extension");
        if (sourceIdentity || fileNode.getAttributes().getNamedItem("sourceUrl") != null) {
            return SourceKey.qualify(key, extension, attribute(fileNode, "sourceUrl"));
        }

        boolean multipleSources = metadataOptions.hasMultipleSources();
        String selectedExtension = filterOptions.getExtensionName();
        if (!includesExtensionAttributes && multipleSources && !metadataOptions.usesNamedSources()) {
            // Historical RAW files without extension attributes belong to the tree passed via -s.
            extension = selectedExtension;
        } else if (!includesExtensionAttributes
                || (!multipleSources && !singleProcessorSelected && selectedExtension.isEmpty())) {
            return key;
        }

        String selectedUrl = filterOptions.getExternalDataProcessorUrl();
        if (!selectedUrl.isEmpty() && extension.equals(selectedExtension)) {
            // Legacy RAW omitted processor URLs. Recover only an exact key of the selected descriptor.
            String selectedKey = SourceKey.qualify(key, extension, selectedUrl);
            if (sources.containsKey(singleProcessorSelected ? key : selectedKey)) {
                return selectedKey;
            }
        }
        return SourceKey.qualify(key, extension, "");
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
            Element file = (Element) files.item(index);
            if (file.hasAttribute("extension") && hasRecordedLines(file)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRecordedLines(Element file) {
        return file.getElementsByTagName("lineToCover").getLength() > 0;
    }

    // Source selection is based on recorded lines. Empty file entries carry no measurements.
    private void verifySelectedSource(NodeList files, Map<String, URI> sources,
                                      boolean sourceIdentity, boolean includesExtensionAttributes,
                                      boolean singleProcessorSelected, Map<String, String> selectedUrls) {
        boolean selectedSourceFound = false;
        Set<String> otherSources = new TreeSet<>();
        for (int index = 0; index < files.getLength(); index++) {
            Element file = (Element) files.item(index);
            if (!hasRecordedLines(file)) {
                continue;
            }
            String key = rawFileKey(file, sourceIdentity, includesExtensionAttributes, singleProcessorSelected, sources);
            boolean qualified = SourceKey.isQualified(key);
            if (qualified && !SourceKey.matchesSource(key, selectedUrls)) {
                otherSources.add(SourceKey.selectionHint(key, metadataOptions.usesNamedSources()));
            } else if (qualified || resolveFileKey(key, sources) != null) {
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

    private static boolean isRawElement(Element element, String tag) {
        // RAW has no default namespace. Empty namespaces on enclosing elements are checked first.
        return tag.equals(element.getTagName()) && element.getAttribute("xmlns").isEmpty();
    }

    private static Element validateRawReport(Document document) {
        Element coverage = document.getDocumentElement();
        if (!isRawElement(coverage, "coverage")) {
            throw new IllegalArgumentException("Expected a RAW coverage XML report");
        }
        boolean sourceIdentity = "true".equals(coverage.getAttribute("sourceIdentity"));
        for (Node node = coverage.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (isNonBlankText(node)) {
                throw new IllegalArgumentException("Unexpected text in the RAW coverage report");
            }
            if (!(node instanceof Element file)) {
                continue;
            }
            if (!isRawElement(file, "file") || file.getAttribute("path").isBlank()) {
                throw new IllegalArgumentException("Expected a file with a path in the RAW coverage report");
            }
            String moduleKey = SourceKey.normalizeRawKey(rawPath(file),
                    sourceIdentity || file.hasAttribute("extension") || file.hasAttribute("sourceUrl"));
            file.setAttribute("path", moduleKey);
            validateRawFileLines(file);
        }
        return coverage;
    }

    private static void validateRawFileLines(Element file) {
        for (Node child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isNonBlankText(child)) {
                throw new IllegalArgumentException("Unexpected text in the RAW coverage file entry");
            }
            if (!(child instanceof Element line)) {
                continue;
            }
            String covered = line.getAttribute("covered");
            if (!isRawElement(line, "lineToCover") || !("true".equals(covered) || "false".equals(covered))) {
                throw new IllegalArgumentException("Invalid line entry in the RAW coverage report");
            }
            for (Node content = line.getFirstChild(); content != null; content = content.getNextSibling()) {
                if (content instanceof Element || isNonBlankText(content)) {
                    throw new IllegalArgumentException("Unexpected content in the RAW coverage line entry");
                }
            }
            parseRawLineNumber(line.getAttribute("lineNumber"));
        }
    }

    private static boolean isNonBlankText(Node node) {
        return node instanceof Text text && !text.getData().isBlank();
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

        Utils.Metadata metadata;
        File rawInput = convertOptions.getInputRawXmlFile();
        try (FileInputStream fileIS = new FileInputStream(rawInput)) {
            DocumentBuilder builder = Utils.newInputXmlBuilder();
            Element coverage = validateRawReport(builder.parse(fileIS));

            metadata = Utils.prepareConversion(metadataOptions,
                    filterOptions.getExtensionName(), filterOptions.getExternalDataProcessorUrl(),
                    coverageData, rawInput, outputOptions.getOutputFile(), reportOutput);
            Map<String, URI> uriListByKey = metadata.getModuleUris();
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
            boolean singleProcessorSelected = !metadataOptions.hasMultipleSources()
                    && !filterOptions.getExternalDataProcessorUrl().isEmpty()
                    && Files.isRegularFile(Paths.get(metadataOptions.getProjectDirName())
                    .resolve(metadataOptions.getSrcDirName()));
            Map<String, String> selectedUrls = selectedSourceUrls();
            verifySelectedSource(fileNodeList, uriListByKey, sourceIdentity, includesExtensions, singleProcessorSelected,
                    selectedUrls);
            for(int fileNodeNumber = 0; fileNodeNumber < fileNodeList.getLength(); fileNodeNumber++) {
                Node fileNode = fileNodeList.item(fileNodeNumber);
                String fileKey = rawFileKey(fileNode, sourceIdentity, includesExtensions,
                        singleProcessorSelected, uriListByKey);
                if (SourceKey.isQualified(fileKey) && !SourceKey.matchesSource(fileKey, selectedUrls)) {
                    continue;
                }
                // Empty legacy records need no source attribution, even if their key is ambiguous.
                if (ambiguousLegacyKeys.contains(fileKey) && !hasRecordedLines((Element) fileNode)) {
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
        metadata.writeConversion(coverageData, metadataOptions, outputOptions);
        logger.info("Convert done");

        return CommandLine.ExitCode.OK;
    }
}
