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
import com.github._1c_syntax.bsl.mdo.MD;
import com.github._1c_syntax.bsl.mdo.Module;
import com.github._1c_syntax.bsl.support.SupportVariant;
import com.github._1c_syntax.bsl.types.ConfigurationSource;
import com.github._1c_syntax.bsl.types.MDOType;
import com.github._1c_syntax.bsl.types.MdoReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        boolean multipleSources = roots.size() > 1;

        ExclusionReport report = new ExclusionReport();
        Map<String, URI> modules = new HashMap<>();
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
                readProcessor(root, extension, projectUri, modules, coverageData, report);
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
            rejectMetadataReport(root, configuration.getConfigurationSource(), coverageFile);
            rejectMetadataReport(root, configuration.getConfigurationSource(), reportFile);
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
                if (module.isProtected() || !Files.exists(Path.of(uri))) {
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
                    inScope = report.moduleHasPrefixRoutines(id.getPath());
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
        checkOutputs(config, rawInput, coverageFile, reportFile, new HashSet<>(modules.values()));
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
                                      Map<URI, Map<BigDecimal, Integer>> coverageData, ExclusionReport report)
            throws Exception {
        Map<URI, Map<BigDecimal, Integer>> processorModules = new HashMap<>();
        Utils.readExternalDataProcessorSources(root, extension, modules, processorModules);
        ExclusionRules rules = ExclusionRules.empty();
        for (URI uri : processorModules.keySet()) {
            ModuleId id = new ModuleId(modulePath(projectUri, uri), List.of(), Set.of());
            addCoverageData(coverageData, uri, id, rules, report);
        }
    }

    private static void rejectMetadataReport(Path root, ConfigurationSource format, File report) throws IOException {
        // A new file cannot alias existing metadata. Check existing reports by file identity, including hard links.
        if (report == null || !report.isFile()) {
            return;
        }
        Path output = report.toPath().toRealPath();
        if (format == ConfigurationSource.EDT) {
            rejectMetadataIn(root.resolve("src"), output, Set.of("mdo", "form", "xml"));
        } else {
            Path dumpInfo = root.resolve("ConfigDumpInfo.xml");
            if (Files.isRegularFile(dumpInfo)) {
                rejectSame(dumpInfo.toFile(), report);
            }
            Set<String> metadataDirectories = new HashSet<>();
            metadataDirectories.add("Ext");
            MDOType.valuesWithoutChildren().forEach(type -> metadataDirectories.add(type.groupName()));
            metadataDirectories.remove("");
            for (String directory : metadataDirectories) {
                rejectMetadataIn(root.resolve(directory), output, Set.of("xml"));
            }
        }
    }

    private static void rejectMetadataIn(Path directory, Path output, Set<String> extensions) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        Files.walkFileTree(directory, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path input, BasicFileAttributes attributes) throws IOException {
                        String name = input.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        if (attributes.isRegularFile() && dot >= 0
                                && extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))
                                && Files.isSameFile(input, output)) {
                            throw new IllegalArgumentException("Report output would overwrite metadata: " + input);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                        if (error instanceof FileSystemLoopException) {
                            return FileVisitResult.CONTINUE;
                        }
                        throw error;
                    }
                });
    }

    private static void checkOutputs(File config, File raw, File coverage, File report, Set<URI> sources)
            throws IOException {
        rejectSame(coverage, report);
        rejectSame(raw, coverage);
        rejectSame(raw, report);
        rejectInputOutput(config, coverage);
        rejectInputOutput(config, report);
        File existingCoverage = coverage != null && coverage.exists() ? coverage : null;
        File existingReport = report != null && report.exists() ? report : null;
        if (existingCoverage != null || existingReport != null) {
            for (URI source : sources) {
                File input = Path.of(source).toFile();
                rejectSame(input, existingCoverage);
                rejectSame(input, existingReport);
            }
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

}
