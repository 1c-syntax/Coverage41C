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
import com.github._1c_syntax.bsl.types.ConfigurationSource;
import com.github._1c_syntax.bsl.types.MDOType;
import com.github._1c_syntax.bsl.types.MdoReference;
import com.github._1c_syntax.bsl.types.ModuleType;
import com.github._1c_syntax.bsl.support.SupportVariant;
import com.google.common.base.Equivalence;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
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
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Utils {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final Interner<Equivalence.Wrapper<OutputStream>> STATISTICS_OUTPUT_LOCKS = Interners.newWeakInterner();

    private static final String EDT_METADATA_NS = "http://g5.1c.ru/v8/dt/metadata/mdclass";
    private static final String EDT_FORM_NS = "http://g5.1c.ru/v8/dt/form";
    private static final String CONFIGURATOR_METADATA_NS = "http://v8.1c.ru/8.3/MDClasses";
    private static final String CONFIGURATOR_FORM_NS = "http://v8.1c.ru/8.3/xcf/logform";

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

    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    static boolean isUuid(String id) {
        return id != null && UUID_TEXT.matcher(id).matches();
    }

    static String normalizeUuid(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return !lower.equals(id) && isUuid(id) ? lower : id;
    }

    public static String getUriKey(String objectId, String propertyId) {
        return normalizeUuid(objectId) + "/" + normalizeUuid(propertyId);
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

    public static Map<String, URI> readMetadata(MetadataOptions options, Map<URI, Map<BigDecimal, Integer>> coverage,
                                                boolean measuring) throws Exception {
        return readMetadata(options, "", coverage, measuring);
    }

    public static Map<String, URI> readMetadataForConversion(MetadataOptions options,
                                                             Map<URI, Map<BigDecimal, Integer>> coverage,
                                                             File rawInput) throws Exception {
        return readMetadataForConversion(options, "", coverage, rawInput);
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
        return readMetadata(metadataOptions, mainExtension, "", coverageData, measuring, hasUserFilter);
    }

    public static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                                String mainExtension, String mainSourceUrl,
                                                Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                boolean measuring, boolean hasUserFilter) throws Exception {
        // The caller chooses the coverage destination later, in dumpCoverageFile.
        return readMetadata(metadataOptions, mainExtension, mainSourceUrl, coverageData, new ExclusionReport(),
                measuring, hasUserFilter, null, null, null).getModuleUris();
    }

    public static Map<String, URI> readMetadata(MetadataOptions metadataOptions,
                                                String mainExtension, String mainSourceUrl,
                                                Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                boolean measuring, boolean hasUserFilter, File coverageOutput) throws Exception {
        return readMetadata(metadataOptions, mainExtension, mainSourceUrl, coverageData, new ExclusionReport(),
                measuring, hasUserFilter, null, coverageOutput, () -> measuring ? StandardOutputFile.PROCESS_OUTPUT : null)
                .getModuleUris();
    }

    public static Metadata readMeasurementMetadata(MetadataOptions options, Map<URI, Map<BigDecimal, Integer>> coverage,
                                                  boolean hasUserFilter, File coverageFile, Supplier<OutputStream> stdout) throws Exception {
        return readMetadata(options, "", "", coverage, new ExclusionReport(), true, hasUserFilter, null, coverageFile, stdout);
    }

    /** Owns the exclusion report descriptor used to protect embedded dumps during one collection. */
    public static final class MeasurementReports implements Closeable {
        private final ConversionOutput.PublishedReport published = new ConversionOutput.PublishedReport();
        private File report;
        private ProtectedInputs inputs = new ProtectedInputs(Set.of(), Set.of(), null);

        public Map<String, URI> readMetadata(MetadataOptions options, String mainExtension, String mainSourceUrl,
                                             Map<URI, Map<BigDecimal, Integer>> coverage,
                                             boolean hasUserFilter, File coverageFile, Supplier<OutputStream> stdout)
                throws Exception {
            published.close();
            Metadata metadata = loadMetadata(options, mainExtension, mainSourceUrl, coverage, new ExclusionReport(), true, hasUserFilter, null,
                    coverageFile, false, stdout);
            report = metadata.reportFile;
            inputs = metadata.inputs;
            boolean guard = report != null && (coverageFile == null || StandardOutputFile.isAlias(coverageFile))
                    && ConversionOutput.needsFileVerification(metadata.reportOutput);
            metadata.writeExclusionReport(guard ? published : null);
            return metadata.getModuleUris();
        }

        public void dump(Map<URI, Map<BigDecimal, Integer>> coverage, MetadataOptions metadata,
                         OutputOptions options, Supplier<OutputStream> stdout) throws IOException {
            File output = options.getOutputFile();
            if (output == null || StandardOutputFile.isAlias(output)) {
                dumpMeasurement(coverage, metadata, options, stdout, report, published);
                return;
            }
            OutputPaths target = new OutputPaths(output);
            rejectOutputOverwritingInputs(target, inputs.files, inputs.trees, "Coverage output", null);
            ExclusionRules.rejectReportInsideConfigDirectory(inputs.config, output, "-o");
            if (StandardOutputFile.isAlias(report)) {
                if (StandardOutputFile.matches(target.destination)) {
                    throw new IOException("Exclusion report and coverage output must use different files: " + output);
                }
            } else if (report != null) {
                rejectOverlappingOutputs(target, new OutputPaths(report));
            }
            try (OutputStream stream = Files.newOutputStream(target.destination, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                writeCoverage(coverage, metadata, options, stream);
            }
            logCoverageStats(coverage);
        }

        @Override
        public void close() throws IOException {
            published.close();
        }
    }

    public static Map<String, URI> readMetadataForConversion(MetadataOptions metadataOptions,
                                                             String mainExtension,
                                                             Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                             File rawInput) throws Exception {
        return readMetadata(metadataOptions, mainExtension, "", coverageData, new ExclusionReport(),
                false, false, rawInput, null, null).getModuleUris();
    }

    public static Map<String, URI> readMetadataForConversion(MetadataOptions metadataOptions,
                                                             String mainExtension,
                                                             String mainSourceUrl,
                                                             Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                             File rawInput, File coverageOutput) throws Exception {
        return readMetadata(metadataOptions, mainExtension, mainSourceUrl, coverageData, new ExclusionReport(), false, false, rawInput, coverageOutput,
                () -> null).getModuleUris();
    }

    public static Metadata prepareConversion(MetadataOptions metadataOptions,
                                             String mainExtension, String mainSourceUrl,
                                             Map<URI, Map<BigDecimal, Integer>> coverageData,
                                             File rawInput, File coverageOutput) throws Exception {
        return prepareConversion(metadataOptions, mainExtension, mainSourceUrl, coverageData, rawInput, coverageOutput, null);
    }

    public static Metadata prepareConversion(MetadataOptions metadataOptions,
                                             String mainExtension, String mainSourceUrl,
                                             Map<URI, Map<BigDecimal, Integer>> coverageData,
                                             File rawInput, File coverageOutput, OutputStream reportOutput) throws Exception {
        return loadMetadata(metadataOptions, mainExtension, mainSourceUrl, coverageData, new ExclusionReport(),
                false, false, rawInput, coverageOutput, true, () -> reportOutput);
    }

    public static List<String> validateMetadata(MetadataOptions metadataOptions) throws Exception {
        return validateMetadata(metadataOptions, null);
    }

    public static List<String> validateMetadata(MetadataOptions metadataOptions, OutputStream stdout) throws Exception {
        if (metadataOptions.isRawMode()) {
            throw new IllegalArgumentException("Validation requires source files");
        }
        ExclusionReport report = new ExclusionReport();
        metadataOptions.validateSourceFilters("", "");
        Metadata metadata = loadMetadata(metadataOptions, "", "", new HashMap<>(), report, false, false, null, null, true, () -> stdout);
        metadata.writeExclusionReport();
        return report.getWarnings();
    }

    private static Metadata readMetadata(MetadataOptions metadataOptions, String mainExtension, String mainSourceUrl,
                                         Map<URI, Map<BigDecimal, Integer>> coverageData,
                                         ExclusionReport report, boolean measuring, boolean hasUserFilter,
                                         File rawInput, File coverageOutput, Supplier<OutputStream> stdout) throws Exception {
        Metadata metadata = loadMetadata(metadataOptions, mainExtension, mainSourceUrl, coverageData, report,
                measuring, hasUserFilter, rawInput, coverageOutput, false, stdout);
        metadata.writeExclusionReport();
        return metadata;
    }

    private record ProtectedInputs(Set<Path> files, Set<Path> trees, File config) {}

    public static final class Metadata {
        private final Map<String, URI> moduleUris;
        private final ExclusionReport report;
        private final File reportFile;
        private final FreshOutputs freshOutputs;
        private final OutputStream reportOutput;
        private final ProtectedInputs inputs;

        private Metadata(Map<String, URI> moduleUris, ExclusionReport report, File reportFile,
                         FreshOutputs freshOutputs, OutputStream reportOutput, ProtectedInputs inputs) {
            this.moduleUris = moduleUris;
            this.report = report;
            this.reportFile = reportFile;
            this.freshOutputs = freshOutputs;
            this.reportOutput = reportOutput;
            this.inputs = inputs;
        }

        public Map<String, URI> getModuleUris() {
            return moduleUris;
        }

        public File getExclusionReportFile() {
            return reportFile;
        }

        private void writeExclusionReport() throws IOException {
            writeExclusionReport(null);
        }

        private void writeExclusionReport(ConversionOutput.PublishedReport published) throws IOException {
            if (reportFile != null) {
                if (freshOutputs != null) {
                    ConversionOutput.writeNewFile(reportPath(), file -> report.writeJson(file.toFile()));
                } else if (StandardOutputFile.isAlias(reportFile)) {
                    try (OutputStream stdout = reportStdout(reportOutput)) {
                        report.writeJson(stdout);
                    }
                } else if (ConversionOutput.isSpecialFile(reportFile.toPath())) {
                    report.writeJson(reportFile);
                } else if (published != null) {
                    published.write(outputPath(reportFile, true), file -> report.writeJson(file.toFile()));
                } else {
                    ConversionOutput.writeFile(outputPath(reportFile, true), file -> report.writeJson(file.toFile()));
                }
                logger.info("Exclusion report written to {}", reportFile.getAbsolutePath());
            }
        }

        public void writeConversion(Map<URI, Map<BigDecimal, Integer>> coverage, MetadataOptions metadataOptions,
                                    OutputOptions outputOptions) throws IOException {
            File output = outputOptions.getOutputFile();
            boolean coverageToStdout = output == null || StandardOutputFile.isAlias(output);
            boolean reportToStdout = StandardOutputFile.isAlias(reportFile);
            if (coverageToStdout && reportFile == null) {
                OutputStream stdout = reportStdout(reportOutput);
                try (OutputStream stream = stdout) {
                    writeCoverage(coverage, metadataOptions, outputOptions, stream);
                } catch (IOException failure) {
                    throw new IOException("Cannot write coverage to standard output", failure);
                }
                logCoverageStats(coverage);
                return;
            }
            Path coveragePath = coverageToStdout ? null
                    : freshOutputs == null ? outputPath(output, false) : freshOutputs.coverage;
            ConversionOutput.Writer writeCoverage =
                    file -> writeCoverageFile(coverage, metadataOptions, outputOptions, file.toFile());
            ConversionOutput.Writer writeReport = file -> report.writeJson(file.toFile());
            if (StandardOutputFile.isAlias(reportFile)) {
                // Publish the named coverage file first so it can be restored if stdout fails.
                ConversionOutput.write(null, coveragePath, writeReport, writeCoverage, reportOutput, freshOutputs != null);
            } else {
                Path reportPath = reportFile == null ? null : reportPath();
                ConversionOutput.write(coveragePath, reportPath, writeCoverage, writeReport, reportOutput, freshOutputs != null);
            }
            logCoverageStats(coverage);
            if (reportFile != null) {
                logger.info("Exclusion report written to {}", reportFile.getAbsolutePath());
            }
        }

        private Path reportPath() throws IOException {
            if (freshOutputs == null) {
                return outputPath(reportFile, true);
            }
            Files.createDirectories(freshOutputs.report.getParent());
            return freshOutputs.report;
        }

        private static Path outputPath(File file, boolean createParent) throws IOException {
            Path path = file.toPath().toAbsolutePath();
            if (ConversionOutput.isSpecialFile(path)) {
                return path;
            }
            Path destination = resolveFileOutputPath(path);
            if (createParent && path.getParent() != null && !Files.isDirectory(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            return destination;
        }
    }

    private static Metadata loadMetadata(MetadataOptions metadataOptions,
                                                 String mainExtension, String mainSourceUrl,
                                                 Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                 ExclusionReport report, boolean measuring,
                                                 boolean hasUserFilter, File rawInput, File coverageOutput,
                                                 boolean allowFreshOutputs, Supplier<OutputStream> stdoutSupplier) throws Exception {

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
            if (rawInput != null) {
                throw new IllegalArgumentException("Conversion requires source files");
            }
            logger.info("Sources directory not set. Enabling RAW mode");
            rawMode = true;
        } else {
            logger.info("Project directory: {}", metadataOptions.getProjectDirName());
        }

        Map<String, URI> uriListByKey = new HashMap<>();
        Set<Path> inputFiles = new LinkedHashSet<>();
        Set<Path> exportTrees = new LinkedHashSet<>();
        if (rawInput != null) {
            inputFiles.add(rawInput.toPath());
        }

        File configExclusionReportFile = null;
        Path unreadableExternalForms = null;
        if (!rawMode) {
            Map<String, Path> sourceDirectories = metadataOptions.getSourceDirectories(mainExtension);
            Set<String> rulesOutsideScope = new HashSet<>();
            Map<String, Integer> prefixModules = new HashMap<>();
            Map<String, ExclusionRules.ScopePrefix> prefixes = new LinkedHashMap<>();
            Map<String, ExclusionRules.Rule> allRules = new LinkedHashMap<>();
            Set<String> markerSources = new LinkedHashSet<>();
            int modulesInScope = 0;
            boolean filtersScope = false;
            boolean includeUsersReported = false;
            for (Map.Entry<String, Path> source : sourceDirectories.entrySet()) {
                URI projectUri = coverageProjectUri(metadataOptions);

                Path rootPath = source.getValue();
                logger.info("Reading configuration sources from root path: {}", rootPath.toAbsolutePath());

                if (!Files.exists(rootPath)) {
                    throw new IllegalArgumentException("Sources path does not exist: " + rootPath.toAbsolutePath());
                }

                if (Files.isDirectory(rootPath)) {
                    addConfigurationInputs(rootPath, inputFiles);

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
                                metadataOptions.hasMultipleSources(), metadataOptions.getExclusionReportFile() != null);
                        rules.getLoadedFiles().forEach(file -> inputFiles.add(file.toPath()));
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

                    if (conf.getConfigurationSource() == ConfigurationSource.EDT) {
                        addEdtProjectInputs(rootPath, inputFiles, exportTrees);
                    } else {
                        addMetadataDirectories(rootPath, exportTrees);
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
                    inputFiles.add(rootPath);

                    Path unreadableForms = readExternalDataProcessorSources(rootPath, source.getKey(), mainSourceUrl,
                            metadataOptions.hasMultipleSources(), projectUri, uriListByKey, coverageData, report, inputFiles, exportTrees);
                    if (allowFreshOutputs && unreadableForms != null) {
                        unreadableExternalForms = unreadableForms;
                    }
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
        }

        File exclusionReportFile = metadataOptions.getExclusionReportFile() != null
                ? metadataOptions.getExclusionReportFile() : configExclusionReportFile;
        boolean coverageToStdout = coverageOutput == null ? stdoutSupplier != null && (measuring || rawInput != null)
                : StandardOutputFile.isAlias(coverageOutput);
        boolean reportToStdout = StandardOutputFile.isAlias(exclusionReportFile);
        // fd 1 identifies the destination only when the caller actually uses process stdout.
        boolean writesToStdout = coverageToStdout || reportToStdout;
        // The collector's coverageOutput hook supplies coverage only. A JSON report explicitly
        // addressed to /dev/stdout still goes to the process descriptor.
        OutputStream requestedStream = null;
        if (coverageToStdout || !measuring && reportToStdout) {
            // A metadata-only call can name /dev/stdout for JSON before it chooses any coverage stream.
            requestedStream = stdoutSupplier == null ? StandardOutputFile.PROCESS_OUTPUT : stdoutSupplier.get();
        }
        OutputStream reportOutput = measuring && reportToStdout ? StandardOutputFile.PROCESS_OUTPUT : requestedStream;
        boolean usesProcessStdout = writesToStdout && StandardOutputFile.isProcessOutput(reportOutput);
        File pairedOutput = coverageToStdout ? exclusionReportFile : coverageOutput;
        boolean sameOutput = coverageToStdout && reportToStdout
                ? !measuring || StandardOutputFile.isProcessOutput(requestedStream)
                : usesProcessStdout && pairedOutput != null && StandardOutputFile.matches(pairedOutput.toPath());
        if (sameOutput) {
            throw new IllegalArgumentException("Exclusion report and coverage output must use different files: "
                    + exclusionReportFile + " and " + (coverageToStdout ? "standard output" : coverageOutput));
        }
        if (measuring && coverageToStdout) {
            ConversionOutput.checkMeasurementReport(requestedStream, exclusionReportFile);
        }
        File configFile = metadataOptions.getConfigFile();
        if (rawMode && configFile != null) {
            ExclusionRules.configFiles(configFile).forEach(file -> inputFiles.add(file.toPath()));
        }
        // System.out may still be fd 1. Keep the input preflight even when its actual sink is opaque.
        StandardOutputFile.FileIdentity stdout = writesToStdout && (usesProcessStdout || reportOutput == null)
                ? StandardOutputFile.regularFile() : null;
        if (measuring || stdout != null || existingFile(coverageOutput) || existingFile(exclusionReportFile)) {
            uriListByKey.values().stream().map(Path::of).filter(Files::isRegularFile).forEach(inputFiles::add);
        }
        if (stdout != null) {
            rejectStdoutOverwritingInputs(stdout, inputFiles, exportTrees);
        }
        FreshOutputs freshOutputs = validateOutputFiles(coverageToStdout ? null : coverageOutput,
                reportToStdout ? null : exclusionReportFile, configFile, inputFiles, exportTrees, unreadableExternalForms);
        if (!rawMode) {
            logger.info("Configuration sources reading DONE");
        }
        ProtectedInputs protectedInputs = new ProtectedInputs(Set.copyOf(inputFiles), Set.copyOf(exportTrees), configFile);
        return new Metadata(uriListByKey, report, exclusionReportFile, freshOutputs, reportOutput, protectedInputs);
    }

    private static boolean sameExistingFile(Path left, Path right) throws IOException {
        if (File.separatorChar == '\\' && left.getFileSystem() == FileSystems.getDefault()
                && right.getFileSystem() == left.getFileSystem() && !left.toString().equals(right.toString())) {
            Object leftKey = WindowsFiles.key(left, false);
            Object rightKey = WindowsFiles.key(right, false);
            if (leftKey != null && rightKey != null) {
                return leftKey.equals(rightKey);
            }
        }
        return Files.isSameFile(left, right);
    }

    static boolean sameOutputFile(Path report, Path coverage) throws IOException {
        if (Files.exists(report) && Files.exists(coverage)) {
            return sameExistingFile(report, coverage);
        }
        if (report.toString().equals(coverage.toString())) {
            return true;
        }
        Path reportDestination = resolveOutputPath(report);
        Path coverageDestination = resolveOutputPath(coverage);
        if (reportDestination.toString().equals(coverageDestination.toString())) {
            return true;
        }
        if (Files.exists(reportDestination) && Files.exists(coverageDestination)) {
            return sameExistingFile(reportDestination, coverageDestination);
        }
        Path reportDirectory = existingOutputDirectory(reportDestination.getParent());
        Path coverageDirectory = existingOutputDirectory(coverageDestination.getParent());
        return reportDirectory != null && coverageDirectory != null && sameExistingFile(reportDirectory, coverageDirectory)
                && aliasesOnVolume(reportDirectory, reportDirectory.relativize(reportDestination),
                        coverageDirectory.relativize(coverageDestination));
    }

    private static Path existingOutputDirectory(Path directory) {
        while (directory != null && !Files.isDirectory(directory)) {
            directory = directory.getParent();
        }
        return directory;
    }

    // Compare names relative to the same physical directory, even when mounted at different paths.
    private static boolean aliasesOnVolume(Path directory, Path report, Path coverage) throws IOException {
        if (report.toString().equals(coverage.toString())) {
            return true;
        }
        if (report.getNameCount() != coverage.getNameCount()
                || (File.separatorChar == '\\' && WindowsFiles.caseSensitiveDirectory(directory))) {
            return false;
        }
        Path probeDirectory = Files.createTempDirectory(directory, ".coverage41c-path-");
        try {
            for (int index = 0; index < report.getNameCount(); index++) {
                Path reportName = report.getName(index);
                Path coverageName = coverage.getName(index);
                if (reportName.toString().equals(coverageName.toString())) {
                    continue;
                }
                Path probe = Files.createFile(probeDirectory.resolve(reportName));
                try {
                    Path alternative = probeDirectory.resolve(coverageName);
                    if (!Files.exists(alternative) || !sameExistingFile(probe, alternative)) {
                        return false;
                    }
                } finally {
                    Files.deleteIfExists(probe);
                }
            }
            return true;
        } finally {
            Files.deleteIfExists(probeDirectory);
        }
    }

    // The exclusion report creates missing directories and can write through a dangling symlink.
    static Path resolveOutputPath(Path path) throws IOException {
        return resolveOutputPath(path, Set.of());
    }

    static Path resolveOutputPath(Path path, Set<Path> futureDirectories) throws IOException {
        return resolveOutputPath(path, new HashSet<>(), false, futureDirectories);
    }

    private static Path resolveFileOutputPath(Path path) throws IOException {
        Path destination = resolveOutputPath(path, new HashSet<>(), true, Set.of());
        if (Files.isDirectory(destination)) {
            throw new IOException("Output destination must name a file: " + path);
        }
        return destination;
    }

    private static Path resolveOutputPath(Path path, Set<Path> links, boolean fileDestination,
                                          Set<Path> futureDirectories) throws IOException {
        Path name = path.getFileName();
        if (fileDestination && (name == null || name.toString().equals(".") || name.toString().equals(".."))) {
            throw new IOException("Output destination must name a file: " + path);
        }
        try {
            return path.toRealPath();
        } catch (NoSuchFileException missing) {
            // POSIX traversal must reach the preceding directory before interpreting "..".
            // Collapsing a missing directory here would silently redirect an invalid output path.
            boolean goesUp = name.toString().equals("..");
            if (path.getParent() == null || goesUp && futureDirectories.isEmpty()) {
                throw missing;
            }
            Path parent = resolveOutputPath(path.getParent(), links, false, futureDirectories);
            if (goesUp && !Files.isDirectory(parent) && !matchesFutureDirectory(parent, futureDirectories)) {
                throw missing;
            }
            Path candidate = parent.resolve(name).normalize();
            if (!Files.isSymbolicLink(candidate)) {
                return candidate;
            }
            if (!links.add(candidate)) {
                throw new FileSystemLoopException(candidate.toString());
            }
            try {
                return resolveOutputPath(candidate.resolveSibling(Files.readSymbolicLink(candidate)), links,
                        fileDestination, futureDirectories);
            } finally {
                links.remove(candidate);
            }
        }
    }

    private static boolean matchesFutureDirectory(Path parent, Set<Path> futureDirectories) throws IOException {
        for (Path futureDirectory : futureDirectories) {
            if (sameOutputFile(parent, futureDirectory)) {
                return true;
            }
        }
        return false;
    }

    private static boolean existingFile(File file) {
        return file != null && file.isFile();
    }

    // The exporter owns the complete contents of these directories, including auxiliary files
    // that the metadata reader does not expose as model objects.
    private static void addMetadataDirectories(Path root, Set<Path> trees) {
        trees.add(root.resolve("Ext"));
        for (MDOType type : MDOType.values()) {
            String group = type.groupName();
            if (group != null && !group.isEmpty()) {
                trees.add(root.resolve(group));
            }
        }
    }

    private static void addEdtProjectInputs(Path root, Set<Path> inputs, Set<Path> trees) {
        for (String directory : List.of("src", "unknown", "DT-INF", ".settings")) {
            trees.add(root.resolve(directory));
        }
        inputs.add(root.resolve(".project"));
        inputs.add(root.resolve(".classpath"));
    }

    private static void addEdtExternalProjectInputs(Path descriptor, Set<Path> inputs, Set<Path> trees) throws IOException {
        Path declaredProject = findEdtExternalProjectRoot(descriptor.toAbsolutePath());
        if (declaredProject != null) {
            addEdtProjectInputs(declaredProject, inputs, trees);
        }
        Path physicalProject = findEdtExternalProjectRoot(descriptor.toRealPath());
        if (physicalProject != null && !physicalProject.equals(declaredProject)) {
            addEdtProjectInputs(physicalProject, inputs, trees);
        }
    }

    private static boolean addEdtExternalResourceTrees(Element descriptor, Path directory, Set<Path> trees,
                                                        String mainSchema, DocumentBuilder builder) {
        String mainTemplate = mainSchemaTemplateName(getEdtChildText(descriptor, "name").strip(), mainSchema);
        boolean hasDataCompositionSchema = false;
        for (Node child = descriptor.getFirstChild(); child != null; child = child.getNextSibling()) {
            String group;
            switch (xmlFeatureName(child, EDT_METADATA_NS)) {
                case "forms":
                    group = "Forms";
                    break;
                case "commands":
                    group = "Commands";
                    break;
                case "templates":
                    group = "Templates";
                    break;
                default:
                    continue;
            }
            String name = getEdtChildText(child, "name");
            if (!isExternalResourceName(name)) {
                continue;
            }
            Path resource = directory.resolve(group).resolve(name);
            // Protect declared resources without claiming the directory containing the standalone descriptor.
            trees.add(resource);
            if (group.equals("Templates") && getEdtChildText(child, "templateType").equals("DataCompositionSchema")
                    && name.equals(mainTemplate)
                    && isDataCompositionSchema(resource.resolve("Template.dcs"), builder)) {
                hasDataCompositionSchema = true;
            }
        }
        return hasDataCompositionSchema;
    }

    private static String getEdtChildText(Node node, String name) {
        return getXmlChildText(node, name, EDT_METADATA_NS);
    }

    private static String getXmlChildText(Node node, String name, String namespace) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (name.equals(xmlFeatureName(child, namespace))) {
                return child.getTextContent();
            }
        }
        return "";
    }

    private static Path findEdtExternalProjectRoot(Path descriptor) {
        // A standalone descriptor can share a parent with generated reports. The EDT source layout
        // identifies the owning project independently of the base path used in coverage output (-P).
        for (Path group = descriptor.getParent(); group != null; group = group.getParent()) {
            Path sources = group.getParent();
            if (sources != null && Path.of("src").equals(sources.getFileName())
                    && (Path.of("ExternalDataProcessors").equals(group.getFileName())
                    || Path.of("ExternalReports").equals(group.getFileName()))) {
                return sources.getParent();
            }
        }
        return null;
    }

    private static void addConfigurationInputs(Path root, Set<Path> inputs) {
        for (String relativePath : List.of("Configuration.xml", "ConfigDumpInfo.xml",
                "Configuration/Configuration.mdo", "src/Configuration/Configuration.mdo", "Ext/ParentConfigurations.bin",
                "src/Configuration/ParentConfigurations.bin", "src/Configuration/Configuration.distr")) {
            inputs.add(root.resolve(relativePath));
        }
    }

    private static void rejectStdoutOverwritingInputs(StandardOutputFile.FileIdentity stdout, Set<Path> inputs,
                                                      Set<Path> trees) throws IOException {
        for (Path input : inputs) {
            if (Files.exists(input) && stdout.matches(input)) {
                throw new IllegalArgumentException("Standard output would overwrite input file: " + input);
            }
        }
        Set<Object> visited = new HashSet<>();
        for (Path tree : trees) {
            if (Files.isDirectory(tree)) {
                checkExportTree(tree, null, (file, attributes) -> stdout.matches(file), Set.of(), Set.of(), visited,
                        "Standard output", null);
            }
        }
    }

    private static final class FreshOutputs {
        private final Path coverage;
        private final Path report;

        private FreshOutputs(Path coverage, Path report) {
            this.coverage = coverage;
            this.report = report;
        }
    }

    private static final class OutputPaths {
        private final Path requested;
        private final Path destination;
        private final Set<Path> locations;
        private final Set<Path> ancestors = new LinkedHashSet<>();

        private OutputPaths(File file) throws IOException {
            requested = file.toPath().toAbsolutePath();
            destination = resolveFileOutputPath(requested);
            locations = outputLocations(requested);
            locations.add(destination);
            for (Path location : locations) {
                for (Path parent = location.getParent(); parent != null; parent = parent.getParent()) {
                    ancestors.add(parent);
                }
            }
        }
    }

    private static FreshOutputs validateOutputFiles(File coverageOutput, File exclusionReport, File config,
                                                   Set<Path> inputs, Set<Path> exportTrees, Path unreadableExternalForms)
            throws IOException {
        if (coverageOutput == null && exclusionReport == null) {
            return null;
        }
        OutputPaths coverage = coverageOutput == null ? null : new OutputPaths(coverageOutput);
        OutputPaths report = exclusionReport == null ? null : new OutputPaths(exclusionReport);
        // A new inode cannot share a hard link with a hidden input. Every named output must be
        // created exclusively, so the writer cannot reopen a file installed after these checks.
        boolean createOnly = unreadableExternalForms != null
                && (coverage == null || Files.notExists(coverage.destination))
                && (report == null || Files.notExists(report.destination));
        Path toleratedUnreadableForms = createOnly ? unreadableExternalForms : null;
        if (coverage != null) {
            rejectOutputOverwritingInputs(coverage, inputs, exportTrees, "Coverage output",
                    toleratedUnreadableForms);
            ExclusionRules.rejectReportInsideConfigDirectory(config, coverageOutput, "-o");
        }
        if (report != null) {
            rejectOutputOverwritingInputs(report, inputs, exportTrees, "Exclusion report",
                    toleratedUnreadableForms);
            if (coverage != null) {
                rejectOverlappingOutputs(coverage, report);
            }
        }
        return createOnly ? new FreshOutputs(coverage == null ? null : coverage.destination,
                report == null ? null : report.destination) : null;
    }

    private static void rejectOverlappingOutputs(OutputPaths coverage, OutputPaths report) throws IOException {
        if (sameOutputFile(report.requested, coverage.requested)) {
            throw new IllegalArgumentException("Exclusion report and coverage output must use different files: "
                    + report.requested + " and " + coverage.requested);
        }
        if (matchesOutputPath(coverage.requested, report.ancestors)
                || matchesOutputPath(report.requested, coverage.ancestors)) {
            throw new IllegalArgumentException("Exclusion report and coverage output paths overlap: "
                    + report.requested + " and " + coverage.requested);
        }
    }

    private static void rejectOutputOverwritingInputs(OutputPaths output, Set<Path> inputs, Set<Path> trees,
                                                       String outputLabel, Path toleratedUnreadableForms)
            throws IOException {
        boolean outputExists = Files.exists(output.destination);
        for (Path input : inputs) {
            Path inputPath = input.toAbsolutePath();
            boolean inputMissing = Files.notExists(inputPath);
            boolean sameInput = outputExists ? Files.exists(inputPath) && sameExistingFile(output.destination, inputPath)
                    : inputMissing && sameOutputFile(output.destination, inputPath);
            if (sameInput) {
                throw new IllegalArgumentException(outputLabel + " would overwrite input file: " + inputPath);
            }
            if ((inputMissing || Files.isDirectory(inputPath)) && matchesOutputPath(inputPath, output.ancestors)) {
                throw new IllegalArgumentException(outputLabel + " cannot use an input file as an output directory: " + inputPath);
            }
        }
        if (!trees.isEmpty()) {
            rejectOutputInExportTrees(output, trees, outputLabel, toleratedUnreadableForms);
        }
    }

    private static void rejectOutputInExportTrees(OutputPaths paths, Set<Path> trees, String label,
                                                   Path toleratedUnreadableForms) throws IOException {
        Path output = paths.requested;
        Path destination = paths.destination;
        Set<Object> parents = new HashSet<>();
        for (Path parent : paths.ancestors) {
            if (Files.isDirectory(parent)) {
                parents.add(directoryKey(parent));
            }
        }
        BasicFileAttributes target = Files.isRegularFile(destination)
                ? Files.readAttributes(destination, BasicFileAttributes.class) : null;
        ExportFileIdentity identity = target == null ? null : (file, attributes) ->
                target.fileKey() == null || attributes.fileKey() == null ? sameExistingFile(file, destination)
                        : target.fileKey().equals(attributes.fileKey());
        Set<Path> missing = new LinkedHashSet<>();
        for (Path path = destination; path != null && Files.notExists(path);
             path = path.getParent()) {
            missing.add(path);
        }

        Set<Object> visited = new HashSet<>();
        for (Path tree : trees) {
            Path directory = resolveOutputPath(tree.toAbsolutePath());
            boolean present = Files.isDirectory(directory);
            if (paths.locations.stream().anyMatch(path -> path.startsWith(directory))
                    || (present && parents.contains(directoryKey(directory)))) {
                throw new IllegalArgumentException(label + " would overwrite input file in source directory: " + output);
            }
            if (present) {
                checkExportTree(directory, output, identity, parents, missing, visited, label, toleratedUnreadableForms);
            } else if (matchesOutputPath(directory, missing)) {
                throw new IllegalArgumentException(label + " is inside a source directory: " + output);
            }
        }
    }

    // Keep each full spelling before following a link in a directory or in the filename. A link
    // can lead into the export and another link can lead back out to a yet nonexistent report.
    private static Set<Path> outputLocations(Path path) throws IOException {
        Set<Path> locations = new LinkedHashSet<>();
        recordOutputLocation(locations, path);
        Path suffix = path.getFileSystem().getPath("");
        locations.add(traceOutputLinks(path, suffix, locations, new HashSet<>()));
        return locations;
    }

    private static void recordOutputLocation(Set<Path> locations, Path path) {
        // Wait until traceOutputLinks has followed preceding directory links.
        if (!hasParentComponent(path)) {
            locations.add(path.normalize());
        }
    }

    private static Path traceOutputLinks(Path path, Path suffix, Set<Path> locations, Set<Path> links)
            throws IOException {
        if (path.getParent() == null) {
            return path;
        }
        Path name = path.getFileName();
        Path parent = traceOutputLinks(path.getParent(), name.resolve(suffix), locations, links);
        Path candidate = parent.resolve(name).normalize();
        if (!Files.isSymbolicLink(candidate)) {
            return candidate;
        }
        if (!links.add(candidate)) {
            throw new FileSystemLoopException(candidate.toString());
        }
        Path target = candidate.resolveSibling(Files.readSymbolicLink(candidate));
        recordOutputLocation(locations, candidate.resolve(suffix));
        recordOutputLocation(locations, target.resolve(suffix));
        Path resolved = traceOutputLinks(target, suffix, locations, links);
        links.remove(candidate);
        return resolved;
    }

    private static boolean matchesOutputPath(Path input, Set<Path> paths) throws IOException {
        if (paths.contains(input)) {
            return true;
        }
        boolean inputExists = Files.exists(input);
        boolean inputMissing = Files.notExists(input);
        for (Path path : paths) {
            if ((inputExists && Files.notExists(path)) || (inputMissing && Files.exists(path))) {
                continue;
            }
            if (sameOutputFile(input, path)) {
                return true;
            }
        }
        return false;
    }

    private static Object directoryKey(Path path) throws IOException {
        return physicalKey(path, Files.readAttributes(path, BasicFileAttributes.class));
    }

    private static Object physicalKey(Path path, BasicFileAttributes attributes) throws IOException {
        return attributes.fileKey() == null ? path.toRealPath() : attributes.fileKey();
    }

    @FunctionalInterface
    private interface ExportFileIdentity {
        boolean matches(Path file, BasicFileAttributes attributes) throws IOException;
    }

    private static void checkExportTree(Path root, Path output, ExportFileIdentity identity,
                                        Set<Object> parents, Set<Path> missing, Set<Object> visited, String label,
                                        Path toleratedUnreadableForms) throws IOException {
        Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Object key = physicalKey(dir, attrs);
                if (parents.contains(key)) {
                    throw new IllegalArgumentException(label + " is inside a source directory: " + output);
                }
                return visited.add(key) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // FOLLOW_LINKS reports a dangling link itself. A new file or any of its new
                // parent directories could make that entry part of the export.
                if (attrs.isSymbolicLink() && Files.notExists(file) && matchesOutputPath(file, missing)) {
                    throw new IllegalArgumentException(label + " would activate a link in a source directory: " + file);
                }
                if (identity != null && attrs.isRegularFile() && identity.matches(file, attrs)) {
                    throw new IllegalArgumentException(label + " would overwrite input file: " + file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                if (error instanceof FileSystemLoopException) {
                    return FileVisitResult.CONTINUE;
                }
                if (toleratedUnreadableForms != null && error instanceof AccessDeniedException
                        && sameExistingFile(file, toleratedUnreadableForms)) {
                    if (parents.contains(directoryKey(file))) {
                        throw new IllegalArgumentException(label + " is inside a source directory: " + output);
                    }
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                return error == null ? FileVisitResult.CONTINUE : visitFileFailed(directory, error);
            }
        });
    }

    public static DocumentBuilder newInputXmlBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder();
    }

    private static String externalChildProblem(String uuid, String name) {
        if (uuid.isBlank()) {
            return "missing UUID";
        }
        if (!isUuid(uuid)) {
            return "invalid UUID";
        }
        if (name.isBlank()) {
            return "missing name";
        }
        return isExternalResourceName(name) ? null : "invalid name";
    }

    private static String requireExternalObjectUuid(Path descriptor, String uuid) {
        if (uuid.isEmpty()) {
            throw new IllegalArgumentException("Expected an external data processor or report descriptor; "
                    + "pass the configuration directory to -s: " + descriptor);
        }
        if (!isUuid(uuid)) {
            throw new IllegalArgumentException("Invalid UUID in external data processor or report descriptor: " + descriptor);
        }
        return uuid;
    }

    private static Path readExternalDataProcessorSources(Path rootPath,
                                                         String sourceName, String sourceUrl, boolean multipleSources,
                                                         URI projectUri,
                                                         Map<String, URI> uriListByKey,
                                                         Map<URI, Map<BigDecimal, Integer>> coverageData,
                                                         ExclusionReport report, Set<Path> inputFiles, Set<Path> exportTrees)
            throws IOException, ParserConfigurationException, SAXException {

        Path descriptor = resolveExternalDescriptor(rootPath, Path.of(projectUri), inputFiles);
        DocumentBuilder builder = newInputXmlBuilder();
        Document xmlDocument = builder.parse(descriptor.toFile());
        Element descriptorElement = xmlDocument.getDocumentElement();
        String edtSourceKind = EDT_METADATA_NS.equals(xmlNamespace(descriptorElement)) ? simpleXmlName(descriptorElement) : "";

        Map<String, URI> externalDataProcessorUriListByKey;
        Path unreadableForms = null;
        if (isConfiguratorMetadataRoot(descriptorElement)) {
            String objectName = com.google.common.io.Files.getNameWithoutExtension(descriptor.getFileName().toString());
            exportTrees.add(descriptor.resolveSibling(objectName));
            ConfiguratorExternalSource source = readConfiguratorExternalSource(descriptor, descriptorElement,
                    builder, inputFiles, projectUri, report);
            externalDataProcessorUriListByKey = source.modules();
            unreadableForms = source.unreadableForms();
        } else if (edtSourceKind.equals("ExternalDataProcessor") || edtSourceKind.equals("ExternalReport")) {
            addEdtExternalProjectInputs(rootPath, inputFiles, exportTrees);
            externalDataProcessorUriListByKey = readEdtExternalSource(descriptor, xmlDocument, builder,
                    inputFiles, exportTrees, projectUri, report);
        } else if (edtSourceKind.equals("Configuration")) {
            throw new IllegalArgumentException("Pass the configuration directory to -s: " + rootPath);
        } else {
            throw new IllegalArgumentException("Unknown source format: " + rootPath);
        }

        externalDataProcessorUriListByKey.forEach((key, uri) -> {
            uriListByKey.put(multipleSources ? SourceKey.qualify(key, sourceName, sourceUrl) : key, uri);
            if (uri.getPath().endsWith(".bin")) {
                coverageData.put(uri, new HashMap<>());
            } else {
                addCoverageData(coverageData, uri, new ModuleId(modulePath(projectUri, uri), List.of(), Set.of()),
                        ExclusionRules.empty(), report);
            }
        });
        return unreadableForms;
    }

    private record ConfiguratorExternalSource(Map<String, URI> modules, Path unreadableForms) {}

    private static ConfiguratorExternalSource readConfiguratorExternalSource(Path rootPath, Element descriptor,
                                                                   DocumentBuilder builder, Set<Path> inputFiles,
                                                                   URI projectUri, ExclusionReport report) {
        Map<String, URI> uriListByKey = new HashMap<>();
        Path parentPath = rootPath.toAbsolutePath().getParent();
        String externalDataProcessorName = com.google.common.io.Files.getNameWithoutExtension(rootPath.toString());
        Element object = configuratorChild(descriptor, "ExternalReport");
        boolean externalReport = object != null;
        if (!externalReport) {
            object = configuratorChild(descriptor, "ExternalDataProcessor");
        }
        String externalObjectUuid = requireExternalObjectUuid(rootPath, object == null ? "" : object.getAttribute("uuid"));
        Element childObjects = configuratorChild(object, "ChildObjects");
        Path sources = parentPath.resolve(externalDataProcessorName);
        if (externalReport) {
            verifyConfiguratorMainSchema(object, childObjects, builder, rootPath, sources, projectUri, report);
        }
        URI objectModule = registerExternalModule(sources.resolve("Ext"), "ObjectModule", inputFiles, !externalReport);
        if (objectModule != null) {
            uriListByKey.put(getUriKey(externalObjectUuid, ModuleType.ObjectModule, null), objectModule);
        }

        indexConfiguratorExternalCommands(childObjects, sources, inputFiles, uriListByKey, projectUri, report);

        Path formsPath = sources.resolve("Forms");
        ExternalChildIndex formIndex = new ExternalChildIndex(ModuleType.FormModule);
        Set<Path> declaredFormDescriptors = declaredConfiguratorForms(
                childObjects, rootPath, formsPath, formIndex, projectUri, report);
        inputFiles.addAll(declaredFormDescriptors);
        if (!Files.notExists(formsPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // Detect an unreadable input tree before checking whether fresh reports can be safely created.
                Files.newDirectoryStream(formsPath).close();
            } catch (IOException error) {
                report.warn(modulePath(projectUri, formsPath.toUri()) + ": cannot list forms: " + error.getLocalizedMessage());
                return new ConfiguratorExternalSource(uriListByKey, error instanceof AccessDeniedException ? formsPath : null);
            }
        }
        for (Path formDescriptor : declaredFormDescriptors) {
            if (Files.notExists(formDescriptor)) {
                report.warn(modulePath(projectUri, formDescriptor.toUri()) + ": missing form descriptor");
                continue;
            }
            String expectedName = com.google.common.io.Files.getNameWithoutExtension(formDescriptor.getFileName().toString());
            String formUuid;
            try {
                formUuid = readConfiguratorFormUuid(formDescriptor, builder, expectedName);
            } catch (IOException | SAXException | IllegalArgumentException error) {
                report.warn(modulePath(projectUri, formDescriptor.toUri())
                        + ": cannot read form descriptor: " + error.getLocalizedMessage());
                continue;
            }
            Path formDirectory = formsPath.resolve(expectedName).resolve("Ext");
            Path layout = formDirectory.resolve("Form.xml");
            String key = formIndex.registerUuid(formUuid, layout);
            URI module = registerExternalFormModule(formDirectory.resolve("Form"), layout, CONFIGURATOR_FORM_NS,
                    inputFiles, builder, projectUri, report);
            if (module != null) {
                uriListByKey.put(key, module);
            }
        }
        return new ConfiguratorExternalSource(uriListByKey, null);
    }

    private static URI coverageProjectUri(MetadataOptions options) {
        Path project = Path.of(options.getProjectDirName());
        if (hasParentComponent(project) && !options.hasMultipleSources()
                && Files.isRegularFile(project.resolve(options.getSrcDirName()))) {
            // The source descriptor and the reported project root must use the same filesystem traversal.
            try {
                project = project.toRealPath();
            } catch (IOException error) {
                throw new UncheckedIOException("Cannot resolve project directory: " + project, error);
            }
        }
        return project.toUri();
    }

    private static boolean hasParentComponent(Path path) {
        for (Path component : path) {
            if (component.toString().equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveExternalDescriptor(Path input, Path project, Set<Path> inputs) throws IOException {
        Path descriptor = input.toAbsolutePath();
        inputs.add(descriptor);
        boolean resolvePhysical = Files.isSymbolicLink(descriptor) || hasParentComponent(descriptor);
        if (!resolvePhysical && descriptor.startsWith(project)) {
            return descriptor;
        }
        Path physicalDescriptor = descriptor.toRealPath();
        inputs.add(physicalDescriptor);
        // Keep paths relative to the selected project for every spelling of a source inside it.
        if (Files.isDirectory(project)) {
            Path physicalProject = project.toRealPath();
            if (physicalDescriptor.startsWith(physicalProject)) {
                return project.resolve(physicalProject.relativize(physicalDescriptor));
            }
        }
        return resolvePhysical ? physicalDescriptor : descriptor;
    }

    private static Set<Path> declaredConfiguratorForms(Element childObjects, Path descriptor, Path forms,
                                                        ExternalChildIndex index, URI projectUri, ExclusionReport report) {
        Set<Path> declared = new LinkedHashSet<>();
        for (Element form : configuratorChildren(childObjects, "Form")) {
            String name = configuratorText(form);
            if (!isExternalResourceName(name)) {
                report.warn(modulePath(projectUri, descriptor.toUri()) + ": invalid declared form name");
                continue;
            }
            Path formDescriptor = forms.resolve(name + ".xml");
            // The Configurator lists references to separate descriptors. An identical reference selects the same descriptor.
            index.registerName(name, formDescriptor, true);
            declared.add(formDescriptor);
        }
        return declared;
    }

    private static String readConfiguratorFormUuid(Path descriptor, DocumentBuilder builder, String expectedName)
            throws IOException, SAXException {
        Element root = builder.parse(descriptor.toFile()).getDocumentElement();
        if (!isConfiguratorMetadataRoot(root)) {
            throw new IOException("expected a MetaDataObject element in namespace " + CONFIGURATOR_METADATA_NS);
        }
        Element form = configuratorChild(root, "Form");
        String uuid = form == null ? "" : form.getAttribute("uuid");
        String name = configuratorText(configuratorChild(configuratorChild(form, "Properties"), "Name"));
        String problem = externalChildProblem(uuid, name);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        if (!name.equalsIgnoreCase(expectedName)) {
            throw new IllegalArgumentException("expected form name " + expectedName + " but found " + name);
        }
        return uuid;
    }

    private static void indexConfiguratorExternalCommands(Element childObjects, Path sources,
                                                            Set<Path> inputs, Map<String, URI> modules,
                                                            URI projectUri, ExclusionReport report) {
        Path commands = sources.resolve("Commands");
        ExternalChildIndex index = new ExternalChildIndex(ModuleType.CommandModule);
        int number = 0;
        for (Element command : configuratorChildren(childObjects, "Command")) {
            number++;
            String uuid = command.getAttribute("uuid");
            String name = configuratorText(configuratorChild(configuratorChild(command, "Properties"), "Name"));
            String problem = externalChildProblem(uuid, name);
            if (problem != null) {
                report.warn(modulePath(projectUri, commands.toUri()) + ": skipped command #" + number + ": " + problem);
                continue;
            }
            indexExternalCommand(index, uuid, name, commands.resolve(name).resolve("Ext"), inputs, modules);
        }
    }

    private static void indexExternalCommand(ExternalChildIndex index, String uuid, String name, Path directory,
                                             Set<Path> inputs, Map<String, URI> modules) {
        String key = index.register(uuid, name, directory);
        URI module = registerExternalModule(directory, "CommandModule", inputs, false);
        if (module != null) {
            modules.put(key, module);
        }
    }

    private static void readEdtExternalChildModules(Element root, Path descriptor, DocumentBuilder builder,
                                                    Set<Path> inputs, URI projectUri, ExclusionReport report,
                                                    Map<String, URI> modules) {
        Path directory = descriptor.getParent();
        ExternalChildIndex formIndex = new ExternalChildIndex(ModuleType.FormModule);
        ExternalChildIndex commandIndex = new ExternalChildIndex(ModuleType.CommandModule);
        int formNumber = 0;
        int commandNumber = 0;
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            String kind = xmlFeatureName(child, EDT_METADATA_NS);
            boolean form = kind.equals("forms");
            if (!form && !kind.equals("commands")) {
                continue;
            }
            int number = form ? ++formNumber : ++commandNumber;
            String uuid = ((Element) child).getAttribute("uuid");
            String name = getEdtChildText(child, "name");
            String problem = externalChildProblem(uuid, name);
            if (problem != null) {
                report.warn(modulePath(projectUri, descriptor.toUri()) + ": skipped "
                        + (form ? "form" : "command") + " #" + number + ": " + problem);
                continue;
            }

            if (form) {
                Path formDirectory = directory.resolve("Forms").resolve(name);
                Path layout = formDirectory.resolve("Form.form");
                String key = formIndex.register(uuid, name, layout);
                URI module = registerExternalFormModule(formDirectory, layout, EDT_FORM_NS, inputs, builder, projectUri, report);
                if (module != null) {
                    modules.put(key, module);
                }
            } else {
                indexExternalCommand(commandIndex, uuid, name, directory.resolve("Commands").resolve(name), inputs, modules);
            }
        }
    }

    private static void verifyConfiguratorMainSchema(Element externalReport, Element childObjects, DocumentBuilder builder,
                                                      Path descriptor, Path sources, URI projectUri,
                                                      ExclusionReport report) {
        Element properties = configuratorChild(externalReport, "Properties");
        String reference = configuratorText(configuratorChild(properties, "MainDataCompositionSchema"));
        if (reference.isEmpty()) {
            return;
        }
        String objectName = configuratorText(configuratorChild(properties, "Name"));
        String name = mainSchemaTemplateName(objectName, reference);
        if (!name.isEmpty()) {
            for (Element template : configuratorChildren(childObjects, "Template")) {
                if (name.equals(configuratorText(template))
                        && isConfiguratorSchemaTemplate(sources.resolve("Templates"), name, builder)) {
                    return;
                }
            }
        }
        warnMissingMainSchema(report, projectUri, descriptor, reference);
    }

    private static boolean isConfiguratorSchemaTemplate(Path templates, String name, DocumentBuilder builder) {
        Path metadata = templates.resolve(name + ".xml");
        if (!Files.notExists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Element root = builder.parse(metadata.toFile()).getDocumentElement();
                if (!isConfiguratorMetadataRoot(root)) {
                    return false;
                }
                Element properties = configuratorChild(configuratorChild(root, "Template"), "Properties");
                String templateName = configuratorText(configuratorChild(properties, "Name"));
                String templateType = configuratorText(configuratorChild(properties, "TemplateType"));
                if (!name.equalsIgnoreCase(templateName) || !templateType.equals("DataCompositionSchema")) {
                    return false;
                }
            } catch (IOException | SAXException error) {
                return false;
            }
        }
        return isDataCompositionSchema(templates.resolve(name).resolve("Ext/Template.xml"), builder);
    }

    private static boolean isDataCompositionSchema(Path schema, DocumentBuilder builder) {
        if (!Files.isRegularFile(schema)) {
            return false;
        }
        try {
            Element root = builder.parse(schema.toFile()).getDocumentElement();
            return isXmlName(root, "http://v8.1c.ru/8.1/data-composition-system/schema", "DataCompositionSchema");
        } catch (IOException | SAXException error) {
            return false;
        }
    }

    private static String mainSchemaTemplateName(String objectName, String reference) {
        String prefix = "ExternalReport." + objectName + ".Template.";
        if (objectName.isEmpty() || !reference.startsWith(prefix)) {
            return "";
        }
        String name = reference.substring(prefix.length());
        return isExternalResourceName(name) ? name : "";
    }

    private static boolean isExternalResourceName(String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains(":")) {
            return false;
        }
        try {
            return Path.of(name).getNameCount() == 1;
        } catch (InvalidPathException invalid) {
            return false;
        }
    }

    private static void warnMissingMainSchema(ExclusionReport report, URI projectUri, Path descriptor, String reference) {
        report.warn(modulePath(projectUri, descriptor.toUri()) + ": missing main data composition schema: " + reference);
    }

    private static final class ExternalChildIndex {
        private final ModuleType type;
        private final String kind;
        private final Set<String> uuids = new HashSet<>();
        private final Map<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        private ExternalChildIndex(ModuleType type) {
            this.type = type;
            this.kind = type == ModuleType.FormModule ? "form" : "command";
        }

        private String register(String uuid, String name, Path path) {
            String key = registerUuid(uuid, path);
            registerName(name, path, false);
            return key;
        }

        private String registerUuid(String uuid, Path path) {
            String normalizedUuid = normalizeUuid(uuid);
            if (!uuids.add(normalizedUuid)) {
                throw new IllegalArgumentException("Duplicate external " + kind + " UUID " + uuid + " at " + path);
            }
            return getUriKey(normalizedUuid, type, null);
        }

        private void registerName(String name, Path path, boolean allowRepeatedReference) {
            String previous = names.putIfAbsent(name, name);
            if (previous != null && !(allowRepeatedReference && previous.equals(name))) {
                throw new IllegalArgumentException("Duplicate external " + kind + " name " + name
                        + " (already declared as " + previous + ") at " + path);
            }
        }
    }

    private static URI registerExternalFormModule(Path directory, Path layout, String namespace, Set<Path> inputs,
                                                  DocumentBuilder builder, URI projectUri, ExclusionReport report) {
        inputs.add(layout);
        URI module = registerExternalModule(directory, "Module", inputs, false);
        if (module != null) {
            return module;
        }
        if (!Files.isRegularFile(layout)) {
            return directory.resolve("Module.bsl").toUri();
        }
        try {
            Document form = builder.parse(layout.toFile());
            if (!isXmlName(form.getDocumentElement(), namespace, "Form")) {
                throw new IOException("expected a Form element in namespace " + namespace);
            }
            return requiresExternalFormModule(form, namespace) ? directory.resolve("Module.bsl").toUri() : null;
        } catch (IOException | SAXException error) {
            report.warn(modulePath(projectUri, layout.toUri()) + ": cannot read form layout: " + error.getLocalizedMessage());
            return null;
        }
    }

    private static boolean requiresExternalFormModule(Document form, String namespace) {
        NodeList elements = form.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Node element = elements.item(i);
            String tag = xmlFeatureName(element, namespace);
            if ((tag.equals("Event") || tag.equals("Action")) && !element.getTextContent().isBlank()) {
                return true;
            }
            if ((tag.equals("handlers") || tag.equals("handler")) && !getXmlChildText(element, "name", namespace).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConfiguratorMetadataRoot(Element root) {
        return isXmlName(root, CONFIGURATOR_METADATA_NS, "MetaDataObject") || isXmlName(root, "", "MetaDataObject");
    }

    private static Element configuratorChild(Element parent, String name) {
        List<Element> children = configuratorChildren(parent, name);
        return children.isEmpty() ? null : children.get(0);
    }

    private static List<Element> configuratorChildren(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        if (parent != null) {
            String namespace = xmlNamespace(parent);
            for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element && isXmlName((Element) child, namespace, name)) {
                    children.add((Element) child);
                }
            }
        }
        return children;
    }

    private static String configuratorText(Element element) {
        if (element == null || element.getElementsByTagName("*").getLength() != 0) {
            return "";
        }
        return element.getTextContent().strip();
    }

    private static boolean isXmlName(Element element, String namespace, String name) {
        return name.equals(simpleXmlName(element)) && namespace.equals(xmlNamespace(element));
    }

    private static String xmlNamespace(Element element) {
        String tag = element.getTagName();
        int colon = tag.indexOf(':');
        if (colon == 0 || colon != tag.lastIndexOf(':')) {
            return null;
        }
        String attribute = colon < 0 ? "xmlns" : "xmlns:" + tag.substring(0, colon);
        // The shared XML parser leaves namespaces in attribute form. Resolve the closest declaration here.
        for (Node ancestor = element; ancestor instanceof Element; ancestor = ancestor.getParentNode()) {
            Element declaration = (Element) ancestor;
            if (declaration.hasAttribute(attribute)) {
                return declaration.getAttribute(attribute);
            }
        }
        return colon < 0 ? "" : null;
    }

    private static String xmlFeatureName(Node node, String namespace) {
        if (!(node instanceof Element)) {
            return "";
        }
        String actualNamespace = xmlNamespace((Element) node);
        if (namespace.equals(actualNamespace)
                || "".equals(actualNamespace) && node.getNodeName().indexOf(':') < 0) {
            return simpleXmlName(node);
        }
        return "";
    }

    private static String simpleXmlName(Node node) {
        String name = node.getNodeName();
        return name.substring(name.lastIndexOf(':') + 1);
    }


    private static URI registerExternalModule(Path directory, String name, Set<Path> inputs, boolean required) {
        Path source = directory.resolve(name + ".bsl");
        Path binary = directory.resolve(name + ".bin");
        // A protected module can have only the binary file. Reserve both names before they exist.
        inputs.add(source);
        inputs.add(binary);
        if (!required && Files.notExists(source, LinkOption.NOFOLLOW_LINKS)
                && Files.notExists(binary, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return (Files.notExists(source) && Files.isRegularFile(binary) ? binary : source).toUri();
    }

    private static Map<String, URI> readEdtExternalSource(Path rootPath, Document xmlDocument, DocumentBuilder builder,
                                                          Set<Path> inputFiles, Set<Path> exportTrees,
                                                          URI projectUri, ExclusionReport report) {
        Map<String, URI> uriListByKey = new HashMap<>();
        Path parentPath = rootPath.toAbsolutePath().getParent();
        Element descriptor = xmlDocument.getDocumentElement();
        String externalObjectUuid = requireExternalObjectUuid(rootPath, descriptor.getAttribute("uuid"));
        boolean externalReport = simpleXmlName(descriptor).equals("ExternalReport");
        String mainSchema = externalReport ? getEdtChildText(descriptor, "mainDataCompositionSchema").strip() : "";
        boolean hasDataCompositionSchema = addEdtExternalResourceTrees(
                descriptor, parentPath, exportTrees, mainSchema, builder);
        if (!mainSchema.isEmpty() && !hasDataCompositionSchema) {
            warnMissingMainSchema(report, projectUri, rootPath, mainSchema);
        }
        boolean objectModuleRequired = !externalReport;
        URI objectModule = registerExternalModule(parentPath, "ObjectModule", inputFiles, objectModuleRequired);
        if (objectModule != null) {
            uriListByKey.put(getUriKey(externalObjectUuid, ModuleType.ObjectModule, null), objectModule);
        }

        readEdtExternalChildModules(descriptor, rootPath, builder, inputFiles, projectUri, report, uriListByKey);
        return uriListByKey;
    }

    public static void dumpCoverageFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                        MetadataOptions metadataOptions,
                                        OutputOptions outputOptions) throws IOException {
        File output = outputOptions.getOutputFile();
        if (StandardOutputFile.isAlias(output)) {
            writeCoverage(coverageData, metadataOptions, outputOptions, StandardOutputFile.PROCESS_OUTPUT);
        } else {
            writeCoverageFile(coverageData, metadataOptions, outputOptions, output);
        }
        logCoverageStats(coverageData);
    }

    /** Writes stdout reports to a borrowed stream; named outputs still use their file. */
    public static void dumpCoverageFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                        MetadataOptions metadataOptions,
                                        OutputOptions outputOptions, OutputStream stdout) throws IOException {
        dumpMeasurement(coverageData, metadataOptions, outputOptions, () -> stdout, null);
    }

    public static void dumpMeasurement(Map<URI, Map<BigDecimal, Integer>> coverageData, MetadataOptions metadataOptions,
                                       OutputOptions outputOptions, Supplier<OutputStream> coverageOutput, File exclusionReport)
            throws IOException {
        dumpMeasurement(coverageData, metadataOptions, outputOptions, coverageOutput, exclusionReport, null);
    }

    private static void dumpMeasurement(Map<URI, Map<BigDecimal, Integer>> coverageData, MetadataOptions metadataOptions,
                                        OutputOptions outputOptions, Supplier<OutputStream> coverageOutput, File exclusionReport,
                                        ConversionOutput.PublishedReport published) throws IOException {
        File output = outputOptions.getOutputFile();
        if (output != null && !StandardOutputFile.isAlias(output)) {
            writeCoverageFile(coverageData, metadataOptions, outputOptions, output);
        } else {
            OutputStream stdout = coverageOutput.get();
            if (stdout instanceof PrintStream) {
                throw new IllegalArgumentException("Coverage output must propagate IOExceptions, not suppress them in PrintStream");
            }
            ConversionOutput.Content writer = stream -> writeCoverage(coverageData, metadataOptions, outputOptions, stream);
            if (stdout == null) {
                try (OutputStream current = reportStdout(null)) {
                    ConversionOutput.writeSeparate(current, exclusionReport, published, writer);
                }
            } else {
                ConversionOutput.writeSeparate(stdout, exclusionReport, published, writer);
            }
        }
        logCoverageStats(coverageData);
    }

    private static void writeCoverageFile(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                          MetadataOptions metadataOptions,
                                          OutputOptions outputOptions, File outputFile) throws IOException {
        try (OutputStream output = outputFile == null ? reportStdout(null) : new FileOutputStream(outputFile)) {
            writeCoverage(coverageData, metadataOptions, outputOptions, output);
        }
    }

    private static void writeCoverage(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                      MetadataOptions metadataOptions, OutputOptions options,
                                      OutputStream output) throws IOException {
        URI projectUri = coverageProjectUri(metadataOptions);
        try {
            switch (options.getOutputFormat()) {
                case LCOV:
                    writeLcov(coverageData, projectUri, output);
                    break;
                case COBERTURA:
                    writeCobertura(coverageData, projectUri, output);
                    break;
                default:
                    writeGenericCoverage(coverageData, projectUri, output, metadataOptions.isRawMode());
            }
            output.flush();
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Cannot write coverage file: " + e.getLocalizedMessage(), e);
        }
    }

    static OutputStream reportStdout(OutputStream destination) throws IOException {
        if (destination instanceof PrintStream) {
            throw new IllegalArgumentException("Pass the underlying OutputStream so write errors propagate");
        }
        PrintStream captured = destination == null ? System.out : null;
        if (captured != null && captured.checkError()) {
            throw new IOException("Standard output is already in error; retry with getCommandLine(OutputStream)");
        }
        return new FilterOutputStream(captured == null ? destination : captured) {
            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                out.write(bytes, offset, length);
            }

            @Override
            public void close() throws IOException {
                if (captured == null) {
                    flush();
                } else if (captured.checkError()) {
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

    public static void logCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData) {
        writeCoverageStats(coverageData, logger::info);
    }

    /** Writes and flushes a borrowed sink without closing it; the sink must propagate IOExceptions. */
    public static void writeCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                          OutputStream output) throws IOException {
        if (output instanceof PrintStream) {
            throw new IllegalArgumentException("Statistics output must propagate IOExceptions, not suppress them in PrintStream");
        }
        ByteArrayOutputStream report = new ByteArrayOutputStream();
        try (PrintStream rendered = new PrintStream(report)) {
            printCoverageStats(coverageData, rendered);
        }
        writeStatisticsReport(report, output);
    }

    public static void printCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData, PrintStream output) {
        writeCoverageStats(coverageData, output::println);
    }

    private static void writeStatisticsReport(ByteArrayOutputStream report, OutputStream output) throws IOException {
        // Keep each report and flush together for the same sink, using a lock private to Coverage41C.
        var lock = STATISTICS_OUTPUT_LOCKS.intern(Equivalence.identity().wrap(output));
        synchronized (lock) {
            output.write(report.toByteArray());
            output.flush();
        }
    }

    private static void writeCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData, Consumer<String> output) {
        long linesToCover = 0;
        long coveredLinesCount = 0;
        for (Map<BigDecimal, Integer> coverMap : coverageData.values()) {
            linesToCover += linesToCover(coverMap);
            coveredLinesCount += coveredLines(coverMap);
        }
        output.accept("Lines to cover: " + linesToCover);
        output.accept("Covered lines: " + coveredLinesCount);
        if (linesToCover > 0) {
            output.accept("Coverage: " + Math.floorDiv(coveredLinesCount * 10000, linesToCover) / 100. + "%");
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

    private static final Pattern WINDOWS_URI_DRIVE_PATH = Pattern.compile("^/[A-Za-z]:/");

    private static String coberturaPath(URI uri) {
        String path = uri.getPath();
        String authority = uri.getAuthority();
        if (authority != null) {
            return "//" + authority + path;
        }
        // On POSIX, /C:/ is an absolute path to an ordinary directory named C:.
        return File.separatorChar == '\\' && WINDOWS_URI_DRIVE_PATH.matcher(path).find() ? path.substring(1) : path;
    }

    static void writeCobertura(Map<URI, Map<BigDecimal, Integer>> coverageData, URI projectUri,
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
        sourceElement.setTextContent(coberturaPath(projectUri));
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
            String filename = coberturaPath(projectUri.relativize(uri));
            classElement.setAttribute("name", filename);
            classElement.setAttribute("line-rate", lineRate(coveredLines(coverMap), linesToCover(coverMap)));
            classElement.setAttribute("branch-rate", "0.0");
            classElement.setAttribute("complexity", "0");
            classElement.setAttribute("filename", filename);
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
                    writer.printf(Locale.ROOT, "DA:%s,%d\n", lineNumber.toString(), hits);
                }
            });
            writer.printf(Locale.ROOT, "LH:%d\n", coveredLines(coverMap));
            writer.printf(Locale.ROOT, "LF:%d\n", linesToCover(coverMap));
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


    /** Writes and flushes a borrowed sink without closing it; the sink must propagate IOExceptions. */
    public static void writeCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                          MetadataOptions metadataOptions, OutputStream output) throws IOException {
        if (output instanceof PrintStream) {
            throw new IllegalArgumentException("Statistics output must propagate IOExceptions, not suppress them in PrintStream");
        }
        ByteArrayOutputStream report = new ByteArrayOutputStream();
        try (PrintStream rendered = new PrintStream(report, false, System.err.charset())) {
            printCoverageStats(coverageData, metadataOptions, rendered);
        }
        writeStatisticsReport(report, output);
    }

    public static void printCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                          MetadataOptions metadataOptions) {
        printCoverageStats(coverageData, metadataOptions, System.out);
    }

    public static void printCoverageStats(Map<URI, Map<BigDecimal, Integer>> coverageData,
                                          MetadataOptions metadataOptions, PrintStream output) {
        List<Object[]> dataList = new LinkedList<>();
        URI projectUri = coverageProjectUri(metadataOptions);
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
        if (dataList.isEmpty()) {
            at.addRow("Total", 0, 0, 0);
            at.addRule();
        }
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
            pipeName = sock.toAbsolutePath().normalize().toString();
        }
        return pipeName;
    }

    public static boolean isProcessStillAlive(Integer pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
