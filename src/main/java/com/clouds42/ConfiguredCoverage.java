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

import com.clouds42.CommandLineOptions.MetadataOptions;
import com.github._1c_syntax.bsl.mdclasses.CF;
import com.github._1c_syntax.bsl.mdclasses.Configuration;
import com.github._1c_syntax.bsl.mdclasses.MDClasses;
import com.github._1c_syntax.bsl.mdo.ChildrenOwner;
import com.github._1c_syntax.bsl.mdo.MD;
import com.github._1c_syntax.bsl.mdo.Module;
import com.github._1c_syntax.bsl.support.SupportVariant;
import com.github._1c_syntax.bsl.types.ConfigurationSource;
import com.github._1c_syntax.bsl.types.MDOType;
import com.github._1c_syntax.bsl.types.MdoReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads optional coverage rules and prepares their diagnostic report. */
public final class ConfiguredCoverage {
    private static final Logger logger = LoggerFactory.getLogger(ConfiguredCoverage.class);

    private ConfiguredCoverage() {}

    public static final class Prepared {
        private final Map<String, URI> modules;
        private final ExclusionReport report;
        private final File reportFile;

        private Prepared(Map<String, URI> modules, ExclusionReport report, File reportFile) {
            this.modules = modules;
            this.report = report;
            this.reportFile = reportFile;
        }

        public Map<String, URI> modules() {
            return modules;
        }

        public void writeReport() throws IOException {
            if (reportFile != null) {
                report.writeJson(reportFile);
                logger.info("Exclusion report written to {}", reportFile.getAbsolutePath());
            }
        }
    }

    public static Prepared prepare(MetadataOptions options, String extension, Map<URI, Map<BigDecimal, Integer>> data,
                                   File coverageFile, File rawInput, boolean rawUserFilter) throws Exception {
        File config = options.getConfigFile();
        File report = options.getExclusionReportFile();
        if (config == null && report == null) {
            return new Prepared(Utils.readMetadata(options, extension, data), null, null);
        }
        if (!options.isRawMode()) {
            return read(options, extension, data, coverageFile, rawInput);
        }
        if (report != null || !rawUserFilter) {
            throw new IllegalArgumentException("Coverage rules and the exclusion report require source files");
        }
        // The user filter is applied during collection; convert applies source rules when sources are available.
        rejectInputOutput(config, coverageFile);
        return new Prepared(Utils.readMetadata(options, extension, data), null, null);
    }

    public static List<String> validate(MetadataOptions options) throws Exception {
        options.validate("");
        if (options.isRawMode()) {
            throw new IllegalArgumentException("Validation requires source files");
        }
        Prepared prepared = read(options, "", new HashMap<>(), null, null);
        prepared.writeReport();
        return prepared.report.getWarnings();
    }

    private static Prepared read(MetadataOptions options, String mainExtension,
                                 Map<URI, Map<BigDecimal, Integer>> coverageData, File coverageFile, File rawInput)
            throws Exception {
        File config = options.getConfigFile();
        File explicitReport = options.getExclusionReportFile();
        Path project = Path.of(options.getProjectDirName());
        URI projectUri = project.toUri();
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put(mainExtension, project.resolve(options.getSrcDirName()));
        options.getExtensionSources().forEach(source ->
                roots.put(source.getName(), project.resolve(source.getPath())));
        List<Path> previousRoots = new ArrayList<>();
        for (Path root : roots.values()) {
            for (Path previous : previousRoots) {
                if (Files.exists(root) && Files.exists(previous) && Files.isSameFile(root, previous)) {
                    throw new IllegalArgumentException("Source paths refer to the same file or directory: "
                            + previous + " and " + root);
                }
            }
            previousRoots.add(root);
        }
        boolean multipleSources = roots.size() > 1;

        ExclusionReport report = new ExclusionReport();
        Map<String, URI> modules = new HashMap<>();
        Set<URI> inputFiles = new HashSet<>();
        Set<String> rulesOutsideScope = new HashSet<>();
        Map<String, Integer> prefixModules = new HashMap<>();
        Map<String, ExclusionRules.ScopePrefix> prefixes = new LinkedHashMap<>();
        Map<String, ExclusionRules.Rule> allRules = new LinkedHashMap<>();
        Set<String> markerSources = new LinkedHashSet<>();
        int modulesInScope = 0;
        boolean filtersScope = false;
        File reportFile = explicitReport;

        for (var source : roots.entrySet()) {
            String extension = source.getKey();
            Path root = source.getValue();
            if (!Files.isDirectory(root)) {
                if (config != null) {
                    throw new IllegalArgumentException("Coverage rules require a configuration or extension directory: " + root);
                }
                readProcessor(root, extension, projectUri, modules, coverageData, report, inputFiles);
                continue;
            }
            if (!(MDClasses.createConfiguration(root) instanceof CF configuration)
                    || configuration == Configuration.EMPTY) {
                throw new IllegalArgumentException("Cannot read configuration metadata: " + root.toAbsolutePath());
            }
            ExclusionRules rules = config == null ? ExclusionRules.empty()
                    : ExclusionRules.load(config, project, configuration, report, multipleSources, explicitReport != null);
            if (reportFile == null) {
                reportFile = rules.getExclusionReportFile();
            }
            collectMetadataInputs(root, configuration, inputFiles);
            rules.getIncludeSources().forEach(report::addScopeSource);
            if (!rules.getPrefixes().isEmpty()) {
                report.setPrefixScope(rules.getPrefixScope());
            }
            rules.getPrefixes().forEach(prefix -> prefixes.putIfAbsent(prefix.getSource(), prefix));
            rules.getRules().forEach(rule -> allRules.putIfAbsent(rule.getSource(), rule));
            rules.getChangeMarkers().forEach(marker -> markerSources.add(marker.getSource()));
            filtersScope |= rules.filtersScope();

            Map<URI, MD> owners = configuration.getModulesByObject();
            Map<MdoReference, Set<String>> membership = rules.needsSubsystemMembership()
                    ? SubsystemFilter.membership(configuration) : Map.of();
            int sourceModulesInScope = 0;
            for (Module module : configuration.getAllModules()) {
                URI uri = module.getUri();
                if (!Files.exists(Path.of(uri))) {
                    continue;
                }
                inputFiles.add(uri);
                if (module.isProtected()) {
                    continue;
                }
                MD owner = owners.get(uri);
                String key = Utils.getExtensionUriKey(extension, Utils.getUriKey(owner.getUuid(),
                        Utils.getModuleTypeUuid(module.getModuleType(), owner)));
                modules.put(key, uri);
                ModuleId id = moduleId(projectUri, module, owner,
                        SubsystemFilter.subsystemsOf(owner.getMdoReference(), membership));
                for (ExclusionRules.ScopePrefix prefix : rules.getPrefixes()) {
                    if (prefix.matches(id)) {
                        prefixModules.merge(prefix.getSource(), 1, Integer::sum);
                    }
                }
                boolean inScope = rules.inScope(id);
                boolean supportRemoved = options.getRemoveSupport() != SupportVariant.NONE
                        && module.getSupportVariant().compareTo(options.getRemoveSupport()) <= 0;
                if (supportRemoved || (!inScope && !rules.scopesRoutines())) {
                    if (!inScope) {
                        report.addModuleOutOfScope();
                    }
                    rulesOutsideScope.addAll(moduleRuleSources(rules, id));
                    addModuleOutside(coverageData, uri, id, rules, report);
                } else if (inScope) {
                    addCoverageData(coverageData, uri, id, rules, report);
                } else {
                    addModuleOutsideScope(coverageData, uri, id, rules, report);
                    inScope = report.moduleInScopeByPrefixRoutines(id.getPath());
                    if (!inScope) {
                        report.addModuleOutOfScope();
                        rulesOutsideScope.addAll(moduleRuleSources(rules, id));
                    }
                }
                if (inScope) {
                    sourceModulesInScope++;
                }
            }
            modulesInScope += sourceModulesInScope;
            if (multipleSources && rules.filtersScope() && sourceModulesInScope == 0) {
                String name = extension.isEmpty() ? "<main>" : extension;
                report.info("Source '" + name + "' has no modules in coverage scope: " + root.toAbsolutePath()
                        + "; only changeMarkers blocks can remain");
            }
        }

        report.verifyExactSubsystems();
        report.warnUnmatchedScopes();
        for (ExclusionRules.ScopePrefix prefix : prefixes.values()) {
            int count = prefixModules.getOrDefault(prefix.getSource(), 0);
            boolean matchesRoutines = report.hasPrefixRoutines(prefix.getSource());
            if (count == 0 && !matchesRoutines) {
                report.warn(prefix.getSource() + ": rule does not match anything");
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
            if (!report.hasEntry(rule.getSource()) && !rulesOutsideScope.contains(rule.getSource())) {
                report.warn(rule.getSource() + ": rule does not match anything");
            }
        }
        inputFiles.addAll(modules.values());
        checkOutputs(config, rawInput, coverageFile, reportFile, inputFiles);
        for (Path root : roots.values()) {
            if (Files.isRegularFile(root)) {
                rejectSame(root.toFile(), coverageFile);
                rejectSame(root.toFile(), reportFile);
            } else {
                for (Path descriptor : List.of(root.resolve("Configuration.xml"),
                        root.resolve("src/Configuration/Configuration.mdo"))) {
                    if (Files.isRegularFile(descriptor)) {
                        rejectSame(descriptor.toFile(), coverageFile);
                        rejectSame(descriptor.toFile(), reportFile);
                    }
                }
            }
        }
        report.logSummary();
        return new Prepared(modules, report, reportFile);
    }

    private static void readProcessor(Path root, String extension, URI projectUri, Map<String, URI> modules,
                                      Map<URI, Map<BigDecimal, Integer>> coverageData, ExclusionReport report,
                                      Set<URI> inputFiles) throws Exception {
        Map<String, URI> processorModules = Utils.discoverExternalDataProcessorSources(
                root, inputFiles::add, report::warn);
        processorModules.forEach((key, uri) -> modules.put(Utils.getExtensionUriKey(extension, key), uri));
        ExclusionRules rules = ExclusionRules.empty();
        for (URI uri : new LinkedHashSet<>(processorModules.values())) {
            if (Files.notExists(Path.of(uri))) {
                // An external processor may be exported without an empty object or form module.
                coverageData.put(uri, new HashMap<>());
            } else {
                ModuleId id = new ModuleId(modulePath(projectUri, uri), List.of(), Set.of());
                addCoverageData(coverageData, uri, id, rules, report);
            }
        }
    }

    private static void collectMetadataInputs(Path root, CF configuration, Set<URI> inputs)
            throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
        boolean edt = configuration.getConfigurationSource() == ConfigurationSource.EDT;
        Path metadataRoot = edt ? root.resolve("src") : root;
        if (edt) {
            inputs.add(root.resolve("src/Configuration/Configuration.mdo").toUri());
            Path support = root.resolve("src/Configuration/Configuration.distr");
            inputs.add((Files.exists(support) ? support
                    : root.resolve("src/Configuration/ParentConfigurations.bin")).toUri());
        } else {
            for (String path : List.of("Configuration.xml", "ConfigDumpInfo.xml", "Ext/ParentConfigurations.bin",
                    "Ext/HomePageWorkArea.xml", "Ext/CommandInterface.xml")) {
                inputs.add(root.resolve(path).toUri());
            }
        }
        // A report in an export directory is not itself metadata. Use the named files of the loaded objects.
        Set<MDOType> childDescriptors = Set.of(MDOType.FORM, MDOType.COMMAND, MDOType.TEMPLATE,
                MDOType.RECALCULATION, MDOType.EXTERNAL_DATA_SOURCE_TABLE, MDOType.EXTERNAL_DATA_SOURCE_CUBE,
                MDOType.EXTERNAL_DATA_SOURCE_FUNCTION, MDOType.EXTERNAL_DATA_SOURCE_CUBE_DIMENSION_TABLE);
        collectReferencedDescriptors(edt ? metadataRoot.resolve("Configuration/Configuration.mdo")
                        : root.resolve("Configuration.xml"), metadataRoot, edt,
                new HashSet<>(MDOType.valuesWithoutChildren()), inputs);
        Set<MDOType> nestedDescriptors = new HashSet<>(childDescriptors);
        nestedDescriptors.add(MDOType.SUBSYSTEM);
        for (MD object : configuration.getPlainChildren()) {
            MDOType type = object.getMdoType();
            if (!MDOType.valuesWithoutChildren().contains(type) && !childDescriptors.contains(type)) {
                continue;
            }
            Path folder = metadataObjectFolder(metadataRoot, object.getMdoReference());
            if (edt) {
                collectEdtMetadataInputs(root, folder, object, inputs);
            } else {
                collectDesignerMetadataInputs(folder, object, inputs);
            }
            collectReferencedChildDescriptors(folder, object, edt, nestedDescriptors, inputs);
        }
    }

    private static void collectReferencedChildDescriptors(Path folder, MD object, boolean edt,
                                                          Set<MDOType> types, Set<URI> inputs)
            throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
        if (object instanceof ChildrenOwner && (!edt || object.getMdoType() == MDOType.SUBSYSTEM)) {
            Path descriptor = edt ? folder.resolve(object.getName() + ".mdo")
                    : folder.resolveSibling(object.getName() + ".xml");
            if (Files.isRegularFile(descriptor)) {
                collectReferencedDescriptors(descriptor, folder, edt, types, inputs);
            }
        }
    }

    private static void collectReferencedDescriptors(Path descriptor, Path folder, boolean edt,
                                                     Set<MDOType> types, Set<URI> inputs)
            throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
        // MDClasses skips unreadable objects; the parent still names their input descriptors.
        var document = Utils.newInputXmlBuilder().parse(descriptor.toFile());
        String expression = edt ? "/*/*" : "/MetaDataObject/*/ChildObjects/*";
        NodeList references = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate(expression, document, XPathConstants.NODESET);
        for (int index = 0; index < references.getLength(); index++) {
            var reference = references.item(index);
            MDOType type = MDOType.valueByName(reference.getNodeName());
            if (!types.contains(type)) {
                continue;
            }
            String name = reference.getTextContent().trim();
            int separator = name.indexOf('.');
            if (edt && separator > 0 && MDOType.valueByName(name.substring(0, separator)) == type) {
                name = name.substring(separator + 1);
            }
            Path child = folder.resolve(type.groupName()).resolve(name);
            Path file = edt ? child.resolve(name + ".mdo") : child.resolveSibling(name + ".xml");
            inputs.add(file.toUri());
            if (edt && !Files.exists(file) && (type == MDOType.INTERFACE || type == MDOType.STYLE)) {
                inputs.add(folder.getParent().resolve("unknown").resolve(type.groupName())
                        .resolve(name + ".xml").toUri());
            }
        }
    }

    private static Path metadataObjectFolder(Path root, MdoReference object) {
        String[] reference = object.getMdoRef().split("\\.");
        Path folder = root;
        for (int index = 0; index + 1 < reference.length; index += 2) {
            folder = folder.resolve(MDOType.valueByName(reference[index]).groupName()).resolve(reference[index + 1]);
        }
        return folder;
    }

    private static void collectEdtMetadataInputs(Path root, Path folder, MD object, Set<URI> inputs) {
        MDOType type = object.getMdoType();
        // EDT embeds forms, commands and templates in their owner's .mdo.
        if (type != MDOType.FORM && type != MDOType.COMMAND && type != MDOType.TEMPLATE) {
            Path descriptor = folder.resolve(object.getName() + ".mdo");
            inputs.add(descriptor.toUri());
            if (!Files.exists(descriptor) && (type == MDOType.INTERFACE || type == MDOType.STYLE)) {
                inputs.add(root.resolve("unknown").resolve(type.groupName())
                        .resolve(object.getName() + ".xml").toUri());
            }
        }
        if (type == MDOType.FORM || type == MDOType.COMMON_FORM) {
            inputs.add(folder.resolve("Form.form").toUri());
        }
    }

    private static void collectDesignerMetadataInputs(Path folder, MD object, Set<URI> inputs) {
        inputs.add(folder.resolveSibling(object.getName() + ".xml").toUri());
        List<String> auxiliaryFiles = switch (object.getMdoType()) {
            case FORM, COMMON_FORM -> List.of("Form.xml");
            case ROLE -> List.of("Rights.xml");
            case SCHEDULED_JOB -> List.of("Schedule.xml");
            case COMMON_TEMPLATE, TEMPLATE -> List.of("Template.xml");
            case XDTO_PACKAGE -> List.of("Package.xml");
            case WS_REFERENCE -> List.of("WSDefinition.xml");
            case SUBSYSTEM -> List.of("CommandInterface.xml");
            case CATALOG, CHART_OF_ACCOUNTS, CHART_OF_CHARACTERISTIC_TYPES, CHART_OF_CALCULATION_TYPES ->
                    List.of("Predefined.xml");
            default -> List.of();
        };
        for (String name : auxiliaryFiles) {
            inputs.add(folder.resolve("Ext").resolve(name).toUri());
        }
    }

    private static void checkOutputs(File config, File raw, File coverage, File report, Set<URI> sources)
            throws IOException {
        rejectSame(coverage, report);
        rejectSame(raw, coverage);
        rejectSame(raw, report);
        rejectInputOutput(config, coverage);
        rejectInputOutput(config, report);
        for (URI source : sources) {
            File input = Path.of(source).toFile();
            rejectSame(input, coverage);
            rejectSame(input, report);
        }
    }

    private static void rejectInputOutput(File config, File output) throws IOException {
        if (config == null || output == null) {
            return;
        }
        ExclusionRules.rejectReportInsideConfigDirectory(config, output, "Report output");
        for (File file : ExclusionRules.configFiles(config)) {
            rejectSame(file, output);
        }
    }

    private static void rejectSame(File input, File output) throws IOException {
        if (input != null && output != null && sameFile(input.toPath(), output.toPath())) {
            throw new IllegalArgumentException("Report output would overwrite an input or another report: " + output);
        }
    }

    static boolean sameFile(Path first, Path second) throws IOException {
        return resolveOutput(first).equals(resolveOutput(second))
                || Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
    }

    static Path resolveOutput(Path path) throws IOException {
        return resolveOutput(path.toAbsolutePath(), new HashSet<>());
    }

    private static Path resolveOutput(Path path, Set<Path> links) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Path link = path.getParent().toRealPath().resolve(path.getFileName());
            if (!links.add(link)) {
                throw new FileSystemLoopException(link.toString());
            }
            return resolveOutput(link.resolveSibling(Files.readSymbolicLink(link)), links);
        }
        try {
            return path.toRealPath();
        } catch (NoSuchFileException missing) {
            if (path.getParent() == null) {
                throw missing;
            }
            return resolveOutput(path.getParent(), links).resolve(path.getFileName()).normalize();
        }
    }

    static void addCoverageData(Map<URI, Map<BigDecimal, Integer>> coverageData, URI uri,
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

}
