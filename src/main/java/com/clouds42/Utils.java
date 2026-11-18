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
import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.github._1c_syntax.bsl.mdclasses.CF;
import com.github._1c_syntax.bsl.mdclasses.MDClasses;
import com.github._1c_syntax.bsl.mdo.MD;
import com.github._1c_syntax.bsl.mdo.Module;
import com.github._1c_syntax.bsl.mdo.SettingsStorage;
import com.github._1c_syntax.bsl.mdclasses.Configuration;
import com.github._1c_syntax.bsl.types.MdoReference;
import com.github._1c_syntax.bsl.types.ModuleType;
import com.github._1c_syntax.bsl.support.SupportVariant;
import de.vandermeer.asciitable.AsciiTable;
import de.vandermeer.asciitable.CWC_LongestLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
        return mdObjUuid + "/" + getModuleTypeUuid(moduleType, mdObj);
    }

    public static String getUriKey(String objectId, String propertyId) {
        return objectId + "/" + propertyId;
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
        return getRawUri(extensionName, "", uriKey);
    }

    public static URI getRawUri(String extensionName, String sourceUrl, String uriKey) {
        if (extensionName.isEmpty() && sourceUrl.isEmpty()) {
            return URI.create("file:///" + uriKey);
        }
        try {
            return new URI("file", "", "/" + uriKey, sourceUrl.isEmpty() ? null : sourceUrl,
                    extensionName.isEmpty() ? null : extensionName);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String getRawExtensionName(URI rawUri) {
        String extensionName = rawUri.getFragment();
        return extensionName == null ? "" : extensionName;
    }


    private static void addCoverageData(Map<URI, Map<BigDecimal, Integer>> coverageData, URI uri,
                                        ModuleId module, ExclusionRules rules, ExclusionReport report) {
        String moduleText = readModuleText(uri, module, report);
        // A skipped module keeps an empty map, like a module out of scope: its measurements are ignored.
        coverageData.put(uri, moduleText == null
                ? new HashMap<>()
                : CoverMapBuilder.build(moduleText, module, rules, report));
    }

    // A module out of scope or under --removeSupport is read only for its change blocks.
    private static void addModuleOutside(Map<URI, Map<BigDecimal, Integer>> coverageData, URI uri,
                                         ModuleId module, ExclusionRules rules, ExclusionReport report) {
        if (rules.getChangeMarkers().isEmpty()) {
            coverageData.put(uri, new HashMap<>());
            return;
        }
        String moduleText = readModuleText(uri, module, report);
        coverageData.put(uri, moduleText == null
                ? new HashMap<>()
                : CoverMapBuilder.build(moduleText, module, rules, report, true));
    }

    // A module outside includeSubsystems/includePrefix: change blocks and methods named with a prefix.
    private static void addModuleOutsideScope(Map<URI, Map<BigDecimal, Integer>> coverageData, URI uri,
                                              ModuleId module, ExclusionRules rules, ExclusionReport report) {
        String moduleText = readModuleText(uri, module, report);
        coverageData.put(uri, moduleText == null
                ? new HashMap<>()
                : CoverMapBuilder.buildOutsideScope(moduleText, module, rules, report));
    }

    private static String readModuleText(URI uri, ModuleId module, ExclusionReport report) {
        try {
            byte[] content = Files.readAllBytes(Path.of(uri));
            if (isModuleWithoutSourceText(content)) {
                logger.info("Skipping module without source text: {}", module.getPath());
                return null;
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
        } catch (IOException e) {
            report.warn(module.getPath() + ": cannot read module: " + e.getLocalizedMessage());
            return null;
        }
    }

    // Modules supplied without source text are stored as a binary 1C container.
    private static boolean isModuleWithoutSourceText(byte[] content) {
        return content.length >= 4 && content[0] == (byte) 0xFF && content[1] == (byte) 0xFF
                && content[2] == (byte) 0xFF && content[3] == 0x7F;
    }

    private static String modulePath(URI projectUri, URI moduleUri) {
        return projectUri.relativize(moduleUri).getPath();
    }

    private static List<String> moduleRuleSources(ExclusionRules rules, ModuleId module) {
        List<String> sources = new ArrayList<>();
        for (ExclusionRules.Rule rule : rules.getRules()) {
            if (!rule.isLineRule() && rule.matches(module)) {
                sources.add(rule.getSource());
            }
        }
        return sources;
    }

    private static ModuleId moduleId(URI projectUri, Module module, MD owner, Set<String> subsystems) {
        MdoReference reference = owner.getMdoReference();
        String moduleType = "." + module.getModuleType().name();
        return new ModuleId(modulePath(projectUri, module.getUri()),
                List.of(reference.getMdoRef() + moduleType, reference.getMdoRefRu() + moduleType),
                subsystems);
    }

    public static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                                Map<URI, Map<BigDecimal, Integer>> coverageData) throws Exception {
        return readMetadata(metadataOptions, "", coverageData, false);
    }

    // measuring: start applies includeUsers itself; convert and validate only report that the key is not for them.
    public static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                                String mainExtension,
                                                Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                boolean measuring) throws Exception {
        boolean hasUserFilter = measuring && metadataOptions.isRawMode() && metadataOptions.getConfigFile() != null
                && IncludeUsers.load(metadataOptions.getConfigFile(), Collections.emptyList()).isActive();
        return readMetadata(metadataOptions, mainExtension, coverageData, measuring, hasUserFilter);
    }

    public static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                                String mainExtension,
                                                Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                boolean measuring, boolean hasUserFilter) throws Exception {
        return readMetadata(metadataOptions, mainExtension, coverageData, new ExclusionReport(),
                measuring, hasUserFilter, null);
    }

    public static Map<String, URI> readMetadataForConversion(MetadataOptions metadataOptions,
                                                             String mainExtension,
                                                             Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                             File rawInput) throws Exception {
        return readMetadata(metadataOptions, mainExtension, coverageData, new ExclusionReport(),
                false, false, rawInput);
    }

    public static List<String> validateMetadata(MetadataOptions metadataOptions) throws Exception {
        if (metadataOptions.isRawMode()) {
            throw new IllegalArgumentException("Validation requires source files");
        }
        ExclusionReport report = new ExclusionReport();
        metadataOptions.validateSourceFilters("", "");
        readMetadata(metadataOptions, "", new HashMap<>(), report, false, false, null);
        return report.getWarnings();
    }

    private static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                                 String mainExtension,
                                                 Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                 ExclusionReport report, boolean measuring,
                                                 boolean hasUserFilter, File rawInput) throws Exception {

        boolean rawMode = false;
        if (metadataOptions.isRawMode()) {
            if (metadataOptions.getConfigFile() != null) {
                // A config with includeUsers is useful to start without sources: it filters measures by user,
                // the source rules wait for convert. convert itself still needs the sources.
                if (!measuring || !hasUserFilter) {
                    throw new IllegalArgumentException("Coverage configuration requires source files");
                }
                logger.info("RAW mode: source rules of {} are applied by convert", metadataOptions.getConfigFile());
            }
            if (metadataOptions.getExclusionReportFile() != null) {
                throw new IllegalArgumentException("Exclusion report requires source files");
            }
            logger.info("Sources directory not set. Enabling RAW mode");
            rawMode = true;
        } else {
            logger.info("Project directory: {}", metadataOptions.getProjectDirName());
        }

        Map<String, URI> uriListByKey = new HashMap<>();

        if (!rawMode) {
            Map<String, Path> sourceDirectories = metadataOptions.getSourceDirectories(mainExtension);
            File configExclusionReportFile = null;
            Set<String> rulesOutsideScope = new HashSet<>();
            Map<String, Integer> prefixModules = new HashMap<>();
            Map<String, ExclusionRules.ScopePrefix> prefixes = new LinkedHashMap<>();
            Map<String, ExclusionRules.Rule> allRules = new LinkedHashMap<>();
            Set<String> markerSources = new LinkedHashSet<>();
            int modulesInScope = 0;
            boolean filtersScope = false;
            boolean includeUsersReported = false;
            for (Map.Entry<String, Path> source : sourceDirectories.entrySet()) {
                URI projectUri = Path.of(metadataOptions.getProjectDirName()).toUri();

                Path rootPath = source.getValue();
                logger.info("Reading configuration sources from root path: {}", rootPath.toAbsolutePath());

                if (!Files.exists(rootPath)) {
                    throw new IllegalArgumentException("Sources path does not exist: " + rootPath.toAbsolutePath());
                }

                if (Files.isDirectory(rootPath)) {

                    String sourceName = source.getKey().isEmpty() ? "<main>" : source.getKey();
                    String sourceError = "Cannot read configuration metadata for source '" + sourceName
                            + "': " + rootPath.toAbsolutePath()
                            + "; expected readable Configuration.xml or src/Configuration/Configuration.mdo";
                    CF conf;
                    try {
                        if (!(MDClasses.createConfiguration(rootPath) instanceof CF configuration)
                                || configuration == Configuration.EMPTY) {
                            throw new IllegalArgumentException(sourceError);
                        }
                        conf = configuration;
                    } catch (RuntimeException error) {
                        if (metadataOptions.hasMultipleSources()) {
                            throw new IllegalArgumentException(sourceError, error);
                        }
                        throw error;
                    }

                    ExclusionRules rules = ExclusionRules.empty();
                    if (metadataOptions.getConfigFile() != null) {
                        ExclusionRules.rejectReportInsideConfigDirectory(metadataOptions.getConfigFile(),
                                metadataOptions.getExclusionReportFile(), "--exclusion-report");
                        rules = ExclusionRules.load(metadataOptions.getConfigFile(),
                                Path.of(metadataOptions.getProjectDirName()), conf, report,
                                metadataOptions.hasMultipleSources());
                        configExclusionReportFile = rules.getExclusionReportFile();
                        rules.getIncludeSources().forEach(report::addScopeSource);
                        if (!rules.getPrefixes().isEmpty()) {
                            report.setPrefixScope(rules.getPrefixScope());
                        }
                        if (rules.filtersScope()) {
                            logger.info("Filtering coverage by {} subsystem(s) and {} prefix(es)",
                                    rules.getIncludedSubsystems().size(), rules.getPrefixes().size());
                        }
                        if (!measuring && !rules.getIncludeUsersSources().isEmpty() && !includeUsersReported) {
                            logger.info("{}: applies only to start, measures are not filtered here",
                                    String.join(", ", rules.getIncludeUsersSources()));
                            includeUsersReported = true;
                        }
                    }

                    // Module rules that hit only modules outside the scope are a precaution, not a typo.
                    rules.getPrefixes().forEach(prefix -> prefixes.putIfAbsent(prefix.getSource(), prefix));
                    rules.getRules().forEach(rule -> allRules.putIfAbsent(rule.getSource(), rule));
                    rules.getChangeMarkers().forEach(marker -> markerSources.add(marker.getSource()));
                    filtersScope |= rules.filtersScope();
                    int sourceModulesInScope = 0;
                    Map<URI, MD> owners = conf.getModulesByObject();
                    Map<MdoReference, Set<String>> subsystemsByObject = rules.needsSubsystemMembership()
                            ? SubsystemFilter.membership(conf) : Map.of();
                    for (Module module : conf.getAllModules()) {
                        if (!Files.exists(Path.of(module.getUri()))) {
                            logger.info("Skipping module without source text: {}", module.getUri());
                            continue;
                        }
                        MD mdObj = owners.get(module.getUri());

                        String mdObjUuid = mdObj.getUuid();

                        String key = getUriKey(mdObjUuid, module.getModuleType(), mdObj);
                        if (metadataOptions.hasMultipleSources()) {
                            key = SourceKey.qualify(key, source.getKey(), "");
                        }
                        URI previous = uriListByKey.putIfAbsent(key, module.getUri());
                        if (previous != null && !previous.equals(module.getUri())) {
                            throw new IllegalArgumentException("Duplicate module key in source " + source.getKey() + ": " + key);
                        }
                        if (metadataOptions.hasMultipleSources() && coverageData.containsKey(module.getUri())) {
                            throw new IllegalArgumentException("Module belongs to more than one source: " + module.getUri());
                        }

                        if (module.isProtected()) {
                            // Keep the module's source identity for legacy RAW ambiguity checks.
                            logger.info("Skipping module without source text: {}", module.getUri());
                            coverageData.put(module.getUri(), new HashMap<>());
                            continue;
                        }

                        ModuleId moduleId = moduleId(projectUri, module, mdObj,
                                SubsystemFilter.subsystemsOf(mdObj.getMdoReference(), subsystemsByObject));
                        for (ExclusionRules.ScopePrefix prefix : rules.getPrefixes()) {
                            if (prefix.matches(moduleId)) {
                                prefixModules.merge(prefix.getSource(), 1, Integer::sum);
                            }
                        }
                        boolean inScope = rules.inScope(moduleId);
                        if (!inScope && !rules.scopesRoutines()) {
                            report.addModuleOutOfScope();
                            rulesOutsideScope.addAll(moduleRuleSources(rules, moduleId));
                            addModuleOutside(coverageData, module.getUri(), moduleId, rules, report);
                            continue;
                        }
                        if (inScope) {
                            modulesInScope++;
                            sourceModulesInScope++;
                        }

                        if (metadataOptions.getRemoveSupport() != SupportVariant.NONE) {
                            SupportVariant moduleSupportVariant = module.getSupportVariant();
                            if (moduleSupportVariant.compareTo(metadataOptions.getRemoveSupport()) <= 0) {
                                if (!inScope) {
                                    report.addModuleOutOfScope();
                                }
                                rulesOutsideScope.addAll(moduleRuleSources(rules, moduleId));
                                addModuleOutside(coverageData, module.getUri(), moduleId, rules, report);
                                continue;
                            }
                        }

                        if (inScope) {
                            addCoverageData(coverageData, module.getUri(), moduleId, rules, report);
                            continue;
                        }
                        // prefixScope.routines: a module outside scope is partly in it when it has prefixed methods.
                        addModuleOutsideScope(coverageData, module.getUri(), moduleId, rules, report);
                        if (report.moduleHasPrefixRoutines(moduleId.getPath())) {
                            modulesInScope++;
                            sourceModulesInScope++;
                        } else {
                            report.addModuleOutOfScope();
                            rulesOutsideScope.addAll(moduleRuleSources(rules, moduleId));
                        }

                    }
                    if (metadataOptions.hasMultipleSources() && rules.filtersScope() && sourceModulesInScope == 0) {
                        String name = source.getKey().isEmpty() ? "<main>" : source.getKey();
                        report.info("Source '" + name + "' has no modules in coverage scope: "
                                + rootPath.toAbsolutePath() + "; only changeMarkers blocks can remain");
                    }

                } else {

                    if (metadataOptions.getConfigFile() != null) {
                        throw new IllegalArgumentException("Coverage configuration is not supported for external data processors");
                    }

                    readExternalDataProcessorSources(rootPath, projectUri, uriListByKey, coverageData, report);
                }

            }
            report.verifyExactSubsystems();
            report.warnUnmatchedScopes();
            for (ExclusionRules.ScopePrefix prefix : prefixes.values()) {
                int modules = prefixModules.getOrDefault(prefix.getSource(), 0);
                ExclusionReport.PrefixRoutinesEntry routines = report.prefixRoutines(prefix.getSource());
                boolean matchesRoutines = report.hasPrefixRoutines(prefix.getSource());
                if (modules == 0 && !matchesRoutines) {
                    report.warn(prefix.getSource() + ": rule does not match anything");
                }
                if (modules > 0) {
                    report.info(prefix.getSource() + ": " + prefix.getPrefix() + " matches " + modules
                            + " module(s)");
                }
                if (matchesRoutines) {
                    report.info(prefix.getSource() + ": " + prefix.getPrefix() + " matches " + routines.getRoutines()
                            + " method(s) in " + routines.getRoutineModules() + " module(s)");
                }
            }
            for (String source : markerSources) {
                if (!report.hasChangeBlocks(source)) {
                    report.warn(source + ": rule does not match anything");
                }
            }
            if (filtersScope && modulesInScope == 0) {
                report.warn("Coverage scope is empty: no module matches includeSubsystems or includePrefix");
            }

            for (ExclusionRules.Rule rule : allRules.values()) {
                if (report.hasEntry(rule.getSource())) {
                    continue;
                }
                if (rulesOutsideScope.contains(rule.getSource())) {
                    logger.info("{}: rule matches only modules outside coverage scope", rule.getSource());
                } else {
                    report.warn(rule.getSource() + ": rule does not match anything");
                }
            }

            report.logSummary();
            File exclusionReportFile = metadataOptions.getExclusionReportFile() != null
                    ? metadataOptions.getExclusionReportFile() : configExclusionReportFile;
            if (exclusionReportFile != null) {
                Path destination = exclusionReportFile.toPath().toAbsolutePath();
                if (rawInput != null) {
                    rejectReportOverwritingInput(destination, rawInput);
                }
                if (metadataOptions.getConfigFile() != null) {
                    for (File input : ExclusionRules.configFiles(metadataOptions.getConfigFile())) {
                        rejectReportOverwritingInput(destination, input);
                    }
                }
                report.writeJson(exclusionReportFile);
                logger.info("Exclusion report written to {}", exclusionReportFile.getAbsolutePath());
            }
            logger.info("Configuration sources reading DONE");
        }

        return uriListByKey;
    }

    private static void rejectReportOverwritingInput(Path report, File input) throws IOException {
        Path inputPath = input.toPath().toAbsolutePath();
        if (report.equals(inputPath)
                || (Files.exists(report) && Files.exists(inputPath) && Files.isSameFile(report, inputPath))) {
            throw new IllegalArgumentException("Exclusion report would overwrite input file: " + inputPath);
        }
    }

    private static void readExternalDataProcessorSources(Path rootPath,
                                                         URI projectUri,
                                                         Map<String, URI> uriListByKey,
                                                         Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                         ExclusionReport report)
            throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {

        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document xmlDocument = builder.parse(rootPath.toFile());
        String documentRootTagName = xmlDocument.getDocumentElement().getTagName();

        Map<String, URI> externalDataProcessorUriListByKey;
        if (documentRootTagName.equals("MetaDataObject")) {
            externalDataProcessorUriListByKey = readConfiguratorExternalDataProcessor(rootPath, xmlDocument, builder);
        } else if (documentRootTagName.equals("mdclass:ExternalDataProcessor")) {
            externalDataProcessorUriListByKey = readEdtExternalDataProcessor(rootPath, xmlDocument);
        } else {
            throw new IllegalArgumentException("Unknown source format: " + rootPath);
        }

        externalDataProcessorUriListByKey.forEach((key, uri) -> {
            uriListByKey.put(key, uri);
            addCoverageData(coverageData, uri, new ModuleId(modulePath(projectUri, uri), List.of(), Set.of()),
                    ExclusionRules.empty(), report);
        });
    }

    private static Map<String, URI> readConfiguratorExternalDataProcessor(Path rootPath,
                                                                          Document xmlDocument,
                                                                          DocumentBuilder builder)
            throws XPathExpressionException {

        Map<String, URI> uriListByKey = new HashMap<>();
        XPath xPath = XPathFactory.newInstance().newXPath();
        Path parentPath = rootPath.toAbsolutePath().getParent();
        String externalDataProcessorName = com.google.common.io.Files.getNameWithoutExtension(rootPath.toString());
        String externalDataProcessorUuid = (String) xPath.compile("/MetaDataObject/ExternalDataProcessor/@uuid")
                .evaluate(xmlDocument, XPathConstants.STRING);
        uriListByKey.put(getUriKey(externalDataProcessorUuid, ModuleType.ObjectModule, null),
                parentPath.resolve(Paths.get(externalDataProcessorName, "Ext", "ObjectModule.bsl")).toUri());

        Path formsPath = parentPath.resolve(Paths.get(externalDataProcessorName, "Forms"));
        if (!Files.isDirectory(formsPath)) {
            return uriListByKey;
        }
        XPathExpression formUuidExpression = xPath.compile("/MetaDataObject/Form/@uuid");
        XPathExpression formNameExpression = xPath.compile("/MetaDataObject/Form/Properties/Name/text()");
        try (Stream<Path> walk = Files.list(formsPath)) {
            walk.filter(formXmlFile -> formXmlFile.toString().endsWith(".xml"))
                    .forEach(formXmlFile -> {
                        try {
                            Document formXmlDocument = builder.parse(formXmlFile.toFile());
                            String formUuid = (String) formUuidExpression.evaluate(formXmlDocument, XPathConstants.STRING);
                            String formName = (String) formNameExpression.evaluate(formXmlDocument, XPathConstants.STRING);
                            uriListByKey.put(getUriKey(formUuid, ModuleType.FormModule, null),
                                    formsPath.resolve(Paths.get(formName, "Ext", "Form", "Module.bsl")).toUri());
                        } catch (Exception e) {
                            logger.error("Can't read form xml: {}", e.getLocalizedMessage());
                        }
                    });
        } catch (IOException e) {
            logger.error("Can't read forms of external data processor: {}", e.getLocalizedMessage());
        }
        return uriListByKey;
    }

    private static Map<String, URI> readEdtExternalDataProcessor(Path rootPath, Document xmlDocument)
            throws XPathExpressionException {

        Map<String, URI> uriListByKey = new HashMap<>();
        XPath xPath = XPathFactory.newInstance().newXPath();
        Path parentPath = rootPath.toAbsolutePath().getParent();
        String externalDataProcessorUuid = (String) xPath.compile("/ExternalDataProcessor/@uuid")
                .evaluate(xmlDocument, XPathConstants.STRING);
        uriListByKey.put(getUriKey(externalDataProcessorUuid, ModuleType.ObjectModule, null),
                parentPath.resolve("ObjectModule.bsl").toUri());

        Path formsPath = parentPath.resolve("Forms");
        NodeList formsNodeList = (NodeList) xPath.compile("/ExternalDataProcessor/forms")
                .evaluate(xmlDocument, XPathConstants.NODESET);
        for (int nodeNumber = 0; nodeNumber < formsNodeList.getLength(); nodeNumber++) {
            Node formsNode = formsNodeList.item(nodeNumber);
            String formUuid = formsNode.getAttributes().getNamedItem("uuid").getTextContent();
            String formName = getEdtFormName(formsNode);
            if (formName.isEmpty()) {
                logger.error("Can't find form name: {}", formUuid);
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
                                        OutputOptions outputOptions) throws IOException {
        URI projectUri = Path.of(metadataOptions.getProjectDirName()).toUri();
        File outputFile = outputOptions.getOutputFile();
        try (OutputStream output = outputFile == null ? standardOutput() : new FileOutputStream(outputFile)) {
            switch (outputOptions.getOutputFormat()) {
                case LCOV:
                    writeLcov(coverageData, projectUri, output);
                    break;
                case COBERTURA:
                    writeCobertura(coverageData, projectUri, output);
                    break;
                default:
                    writeGenericCoverage(coverageData, projectUri, output, metadataOptions.isRawMode());
            }
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Cannot write coverage file: " + e.getLocalizedMessage(), e);
        }
        logCoverageStats(coverageData);
    }

    // start can dump coverage several times, so standard output stays open.
    private static OutputStream standardOutput() {
        PrintStream stream = System.out;
        return new FilterOutputStream(stream) {
            @Override
            public void write(byte[] bytes, int offset, int length) {
                stream.write(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                if (stream.checkError()) {
                    throw new IOException("Cannot write coverage to standard output");
                }
            }
        };
    }

    private static long linesToCover(Map<BigDecimal, Integer> coverMap) {
        return coverMap.values().stream().filter(value -> value >= 0).count();
    }

    private static long coveredLines(Map<BigDecimal, Integer> coverMap) {
        return coverMap.values().stream().filter(value -> value > 0).count();
    }

    private static void logCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData) {
        long linesToCover = 0;
        long coveredLinesCount = 0;
        for (Map<BigDecimal, Integer> coverMap : coverageData.values()) {
            linesToCover += linesToCover(coverMap);
            coveredLinesCount += coveredLines(coverMap);
        }
        logger.info("Lines to cover: {}", linesToCover);
        logger.info("Covered lines: {}", coveredLinesCount);
        if (linesToCover > 0) {
            logger.info("Coverage: {}%", Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100.);
        }
    }

    private static String lineRate(long covered, long toCover) {
        return toCover > 0 ? String.valueOf(Math.floorDiv(covered * 100, toCover) / 100.) : "0.0";
    }

    private static void writeXml(Document doc, OutputStream output) throws TransformerException {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(doc), new StreamResult(output));
    }

    private static void writeCobertura(Map<URI, Map<BigDecimal, Integer>> coverageData, URI projectUri,
                                       OutputStream output) throws ParserConfigurationException, TransformerException {
        long linesToCover = 0;
        long coveredLinesCount = 0;
        for (Map<BigDecimal, Integer> coverMap : coverageData.values()) {
            linesToCover += linesToCover(coverMap);
            coveredLinesCount += coveredLines(coverMap);
        }

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element mainRootElement = doc.createElement("coverage");
        String lineRate = lineRate(coveredLinesCount, linesToCover);
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

        coverageData.forEach((uri, coverMap) -> {
            if (coverMap.isEmpty()) {
                return;
            }

            Element classElement = doc.createElement("class");
            classElement.setAttribute("name", projectUri.relativize(uri).getPath());
            classElement.setAttribute("line-rate", lineRate(coveredLines(coverMap), linesToCover(coverMap)));
            classElement.setAttribute("branch-rate", "0.0");
            classElement.setAttribute("complexity", "0");
            classElement.setAttribute("filename", projectUri.relativize(uri).getPath());
            classesElement.appendChild(classElement);

            classElement.appendChild(doc.createElement("methods"));
            Element linesElement = doc.createElement("lines");
            classElement.appendChild(linesElement);

            coverMap.forEach((lineNumber, hits) -> {
                if (hits >= 0) {
                    Element lineElement = doc.createElement("line");
                    lineElement.setAttribute("hits", String.valueOf(hits));
                    lineElement.setAttribute("number", lineNumber.toString());
                    lineElement.setAttribute("branch", "false");
                    linesElement.appendChild(lineElement);
                }
            });
        });
        writeXml(doc, output);
    }

    private static void writeGenericCoverage(Map<URI, Map<BigDecimal, Integer>> coverageData, URI projectUri,
                                             OutputStream output, boolean raw) throws ParserConfigurationException, TransformerException {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element mainRootElement = doc.createElement("coverage");
        mainRootElement.setAttribute("version", "1");
        if (raw) {
            mainRootElement.setAttribute("sourceIdentity", "true");
        }
        doc.appendChild(mainRootElement);
        coverageData.forEach((uri, coverMap) -> {
            if (coverMap.isEmpty()) {
                return;
            }
            Element fileElement = doc.createElement("file");
            fileElement.setAttribute("path", projectUri.relativize(uri).getPath());
            String extension = getRawExtensionName(uri);
            if (!extension.isEmpty()) {
                fileElement.setAttribute("extension", extension);
            }
            if (uri.getQuery() != null) {
                fileElement.setAttribute("sourceUrl", uri.getQuery());
            }
            coverMap.forEach((lineNumber, hits) -> {
                if (hits >= 0) {
                    Element lineElement = doc.createElement("lineToCover");
                    lineElement.setAttribute("covered", Boolean.toString(hits > 0));
                    lineElement.setAttribute("lineNumber", lineNumber.toString());
                    fileElement.appendChild(lineElement);
                }
            });
            mainRootElement.appendChild(fileElement);
        });
        writeXml(doc, output);
    }

    private static void writeLcov(Map<URI, Map<BigDecimal, Integer>> coverageData, URI projectUri,
                                  OutputStream output) throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        coverageData.forEach((uri, coverMap) -> {
            if (coverMap.isEmpty()) {
                return;
            }
            writer.println("TN:");
            writer.printf("SF:%s\n", projectUri.relativize(uri).getPath());
            coverMap.forEach((lineNumber, hits) -> {
                if (hits >= 0) {
                    writer.printf("DA:%s,%d\n", lineNumber.toString(), hits);
                }
            });
            writer.printf("LH:%d\n", coveredLines(coverMap));
            writer.printf("LF:%d\n", linesToCover(coverMap));
            writer.println("end_of_record");
        });
        writer.flush();
        if (writer.checkError()) {
            throw new IOException("Cannot write coverage file");
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


    public static void printCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                          MetadataOptions metadataOptions) {
        printCoverageStats(coverageData, metadataOptions, System.out);
    }

    public static void printCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                          MetadataOptions metadataOptions, PrintStream output) {
        List<Object[]> dataList = new LinkedList<>();
        URI projectUri = Path.of(metadataOptions.getProjectDirName()).toUri();
        coverageData.forEach((uri, bigDecimalsMap) -> {
            long linesToCover = linesToCover(bigDecimalsMap);
            if (linesToCover == 0) {
                return;
            }
            String path = projectUri.relativize(uri).getPath();
            long coveredLinesCount = coveredLines(bigDecimalsMap);
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
        output.println(rend);
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
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
