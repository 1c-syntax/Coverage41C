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
import com.clouds42.CommandLineOptions.OutputOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExternalDataProcessorTest {

    private static final Path EXTERNAL_PROCESSOR = Path.of("src/test/resources/externalprocessor");
    private static final String PROCESSOR_URL = "file:///processor.epf";
    private static final String PROCESSOR_KEY = "8fe68a1c-d99b-4417-9ee7-27dfaf75ded0/a637f77f-3840-441d-a1c3-699c8c5cb7e0";
    private static final String OBJECT_MODULE = "Процедура ПроцедураМодуляОбработки() Экспорт\n"
            + "\tНичегоНеДелать(); // Cover:off\n"
            + "КонецПроцедуры\n";
    private static final String FORM_MODULE = "&НаКлиенте\n"
            + "Процедура Команда1(Команда)\n"
            + "\tКоманда1НаСервере(); // Cover:off\n"
            + "КонецПроцедуры\n";

    @TempDir
    Path tempDir;

    @Test
    void convertsConfiguratorExternalReport() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");

        Map<String, Map<Integer, Boolean>> coverage = convertExternalSource(
                descriptor.getFileName().toString(), "-P", tempDir.toString());
        assertEquals(Map.of(2, true, 3, false), coverage.get("ExternalReport/Ext/ObjectModule.bsl"));
        assertEquals(Map.of(2, true, 3, false), coverage.get("ExternalReport/Forms/Форма/Ext/Form/Module.bsl"));
    }

    @Test
    void convertsEdtExternalReport() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        Files.writeString(descriptor, "<mdclass:ExternalReport xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
                + " uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\"><name>ExternalReport</name>"
                + "<forms uuid=\"7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88\"><name>Форма</name></forms>"
                + "</mdclass:ExternalReport>");
        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));

        Map<String, Map<Integer, Boolean>> coverage = convertExternalSource(
                descriptor.getFileName().toString(), "-P", tempDir.toString());
        assertEquals(Map.of(2, true, 3, false), coverage.get("ObjectModule.bsl"));
        assertEquals(Map.of(2, true, 3, false), coverage.get("Forms/Форма/Module.bsl"));
    }

    @Test
    void convertsExternalProcessorFromCurrentDirectoryWithoutProjectOption() throws Exception {
        Path sources = Files.createTempDirectory(Path.of("").toAbsolutePath(), "Coverage41CExternal");
        String name = sources.getFileName().toString();
        Path descriptor = sources.resolveSibling(name + ".xml");
        try {
            Files.createFile(descriptor);
            try {
                writeConfiguratorExternalSource(descriptor, "ExternalDataProcessor");
                Map<String, Map<Integer, Boolean>> coverage = convertExternalSource(descriptor.getFileName().toString());
                assertEquals(Map.of(2, true, 3, false), coverage.get(name + "/Ext/ObjectModule.bsl"),
                        coverage.toString());
                assertEquals(Map.of(2, true, 3, false), coverage.get(name + "/Forms/Форма/Ext/Form/Module.bsl"),
                        coverage.toString());
            } finally {
                Files.delete(descriptor);
            }
        } finally {
            deleteDirectory(sources);
        }
    }

    @Test
    void rejectsDoctypeInExternalReportDescriptor() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Files.writeString(descriptor, "<!DOCTYPE MetaDataObject>" + Files.readString(descriptor));
        Path raw = Files.writeString(tempDir.resolve("raw.xml"), "<coverage version=\"1\"/>");
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.CONVERT_COMMAND,
                "-c", raw.toString(), "-s", descriptor.toString(), "-o", output.toString()), stderr.toString());
        assertTrue(stderr.toString().contains("DOCTYPE"), stderr.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsDoctypeInExternalReportFormDescriptor() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path form = tempDir.resolve("ExternalReport/Forms/Форма.xml");
        JsonNode cleanReport = validateExternalSource(tempDir, "ExternalReport.xml", true, CommandLine.ExitCode.OK);
        assertEquals(0, cleanReport.path("warnings").size());
        assertEquals(4, cleanReport.path("totalLines").asInt());

        Files.writeString(form, "<!DOCTYPE MetaDataObject>" + Files.readString(form));
        assertExternalFormDiagnostic(tempDir, "ExternalReport.xml", "ExternalReport/Forms/Форма.xml", "DOCTYPE");

        Map<String, Map<Integer, Boolean>> coverage = convertExternalSource(
                descriptor.getFileName().toString(), "-P", tempDir.toString());
        assertEquals(Map.of(2, true, 3, false), coverage.get("ExternalReport/Ext/ObjectModule.bsl"));
        assertFalse(coverage.containsKey("ExternalReport/Forms/Форма/Ext/Form/Module.bsl"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void strictValidationPrintsInvalidExternalFormDiagnosticInJsonStdout() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path form = tempDir.resolve("ExternalReport/Forms/Форма.xml");
        Files.writeString(form, "<!DOCTYPE MetaDataObject>" + Files.readString(form));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream standardOutput = System.out;
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(new StringWriter(), PipeMessages.VALIDATE_COMMAND,
                    "-s", descriptor.toString(), "--strict", "--exclusion-report", "/dev/stdout"));
        } finally {
            System.setOut(standardOutput);
        }

        JsonNode warnings = new ObjectMapper().readTree(output.toByteArray()).path("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).asText().contains("ExternalReport/Forms/Форма.xml"), warnings.toString());
        assertTrue(warnings.get(0).asText().contains("DOCTYPE"), warnings.toString());
    }

    @Test
    void validatesFailureToListExternalForms() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path forms = tempDir.resolve("ExternalReport/Forms");
        deleteDirectory(forms);
        Files.writeString(forms, "not a directory");

        assertExternalFormDiagnostic(tempDir, "ExternalReport.xml", "ExternalReport/Forms", "cannot list forms");
    }

    @Test
    void validatesMissingDeclaredConfiguratorFormDescriptor() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Files.delete(tempDir.resolve("ExternalReport/Forms/Форма.xml"));
        assertTrue(Files.isRegularFile(tempDir.resolve("ExternalReport/Forms/Форма/Ext/Form/Module.bsl")));

        assertExternalFormDiagnostic(tempDir, "ExternalReport.xml", "ExternalReport/Forms/Форма.xml",
                "missing form descriptor");
        assertEquals(Map.of("ExternalReport/Ext/ObjectModule.bsl", Map.of(2, true, 3, false)),
                convertExternalSource(descriptor.toString()));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void freshConversionCannotWriteThroughAParentRetargetedIntoTheExport() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path export = tempDir.resolve("ExternalReport");
        Path forms = export.resolve("Forms");
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path coverageFile = outside.resolve("coverage.xml");
        var metadata = CommandLine.populateCommand(new MetadataOptions(), "-s", descriptor.toString());
        var output = CommandLine.populateCommand(new OutputOptions(), "-o", coverageFile.toString());
        Map<URI, Map<BigDecimal, Integer>> coverage = new HashMap<>();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(forms);
        try {
            Files.setPosixFilePermissions(forms, PosixFilePermissions.fromString("---------"));
            assumeTrue(!Files.isReadable(forms), "Forms permissions are ineffective for the current user");
            var conversion = Utils.prepareConversion(metadata, "", "", coverage, null, coverageFile.toFile());
            Files.delete(outside);
            Files.createSymbolicLink(outside, export);

            assertThrows(IllegalArgumentException.class, () -> conversion.writeConversion(coverage, metadata, output));
            assertFalse(Files.exists(export.resolve("coverage.xml")));
        } finally {
            Files.setPosixFilePermissions(forms, permissions);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void freshCoverageCanBeWrittenAfterFormDirectoryBecomesUnreadable() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path forms = tempDir.resolve("ExternalReport/Forms");
        Path coverageFile = tempDir.resolve("coverage.xml");
        var metadata = CommandLine.populateCommand(new MetadataOptions(), "-s", descriptor.toString());
        var output = CommandLine.populateCommand(new OutputOptions(), "-o", coverageFile.toString());
        Map<URI, Map<BigDecimal, Integer>> coverage = new HashMap<>();
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(forms);
        try (Utils.MeasurementReports reports = new Utils.MeasurementReports()) {
            reports.readMetadata(metadata, "", "", coverage, false, coverageFile.toFile(), () -> null);
            Files.setPosixFilePermissions(forms, PosixFilePermissions.fromString("---------"));
            assumeTrue(!Files.isReadable(forms), "Forms permissions are ineffective for the current user");

            reports.dump(coverage, metadata, output, () -> null);
            assertTrue(Files.size(coverageFile) > 0);
            byte[] original = Files.readAllBytes(coverageFile);
            assertThrows(IOException.class, () -> reports.dump(coverage, metadata, output, () -> null));
            assertArrayEquals(original, Files.readAllBytes(coverageFile));
        } finally {
            Files.setPosixFilePermissions(forms, permissions);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void writesFreshValidationReportDespiteUnreadableFormsButProtectsExistingInputs() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path forms = tempDir.resolve("ExternalReport/Forms");
        Path formDescriptor = forms.resolve("Форма.xml");
        Path existingOutput = Files.createLink(tempDir.resolve("linked-source.json"), formDescriptor);
        byte[] original = Files.readAllBytes(formDescriptor);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(forms);
        Files.setPosixFilePermissions(forms, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(forms), "Forms permissions are ineffective for the current user");
            JsonNode diagnostic = validateExternalSource(tempDir, "ExternalReport.xml", true, CommandLine.ExitCode.SOFTWARE);
            assertEquals(1, diagnostic.path("warnings").size());
            assertTrue(diagnostic.path("warnings").get(0).asText().contains("cannot list forms"), diagnostic.toString());
            assertEquals(2, diagnostic.path("totalLines").asInt());
            assertEquals(diagnostic, validateExternalSource(tempDir, "ExternalReport.xml", false, CommandLine.ExitCode.OK));

            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                    "-s", descriptor.toString(), "--exclusion-report", existingOutput.toString()), stderr.toString());
            assertArrayEquals(original, Files.readAllBytes(existingOutput));
        } finally {
            Files.setPosixFilePermissions(forms, permissions);
        }
        assertArrayEquals(original, Files.readAllBytes(formDescriptor));
    }

    private JsonNode assertExternalFormDiagnostic(Path project, String descriptor, String location, String detail)
            throws IOException {
        JsonNode strict = validateExternalSource(project, descriptor, true, CommandLine.ExitCode.SOFTWARE);
        JsonNode warnings = strict.path("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).asText().contains(location), warnings.toString());
        assertTrue(warnings.get(0).asText().contains(detail), warnings.toString());
        assertEquals(2, strict.path("totalLines").asInt());

        JsonNode lenient = validateExternalSource(project, descriptor, false, CommandLine.ExitCode.OK);
        assertEquals(strict, lenient);
        return lenient;
    }

    private static void writeConfiguratorExternalSource(Path descriptor, String kind) throws IOException {
        String name = descriptor.getFileName().toString().replaceFirst("\\.xml$", "");
        String metadata = "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\">";
        Files.writeString(descriptor, metadata + "<" + kind + " uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\">"
                + "<Properties><Name>" + name + "</Name></Properties><ChildObjects><Form>Форма</Form></ChildObjects>"
                + "</" + kind + "></MetaDataObject>");
        Path sources = descriptor.resolveSibling(name);
        writeExternalModule(sources.resolve("Ext/ObjectModule.bsl"));
        writeExternalModule(sources.resolve("Forms/Форма/Ext/Form/Module.bsl"));
        Files.writeString(sources.resolve("Forms/Форма.xml"), metadata
                + "<Form uuid=\"7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88\"><Properties>"
                + "<Name>Форма</Name></Properties></Form></MetaDataObject>");
    }

    private static void writeExternalModule(Path module) throws IOException {
        Files.createDirectories(module.getParent());
        Files.writeString(module, OBJECT_MODULE.replace(" // Cover:off", ""));
    }

    private Map<String, Map<Integer, Boolean>> convertExternalSource(String descriptor, String... projectOptions)
            throws Exception {
        Path raw = tempDir.resolve("report-raw.xml");
        Files.writeString(raw, "<coverage version=\"1\">"
                + "<file path=\"/5b8f36b6-34dc-45b7-8b1b-c999e4300d22/a637f77f-3840-441d-a1c3-699c8c5cb7e0\">"
                + "<lineToCover covered=\"true\" lineNumber=\"2\"/></file>"
                + "<file path=\"/7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88/32e087ab-1491-49b6-aba7-43571b41ac2b\">"
                + "<lineToCover covered=\"true\" lineNumber=\"2\"/></file></coverage>");
        Path output = tempDir.resolve("report-coverage.xml");
        List<String> args = new ArrayList<>(List.of(PipeMessages.CONVERT_COMMAND, "-s", descriptor,
                "-c", raw.toString(), "-o", output.toString()));
        args.addAll(Arrays.asList(projectOptions));
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.OK, execute(stderr, args.toArray(new String[0])), stderr.toString());
        return MultipleSourcesTest.coverage(output);
    }

    @Test
    void rejectsExclusionReportAtSourceDescriptor() throws Exception {
        Path descriptor = copyProcessorDescriptor();
        Path output = tempDir.resolve("coverage.xml");

        assertDescriptorOutputRejected(descriptor, descriptor, output, "Exclusion report");
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsExclusionReportThroughSymlinkToSourceDescriptor() throws Exception {
        Path descriptor = copyProcessorDescriptor();
        Path report = tempDir.resolve("report.json");
        try {
            Files.createSymbolicLink(report, descriptor);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a symbolic link: " + error.getMessage());
        }
        Path output = tempDir.resolve("coverage.xml");

        assertDescriptorOutputRejected(descriptor, report, output, "Exclusion report");
        assertTrue(Files.isSymbolicLink(report));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsExclusionReportThroughHardLinkToSourceDescriptor() throws Exception {
        Path descriptor = copyProcessorDescriptor();
        Path report = tempDir.resolve("report.json");
        try {
            Files.createLink(report, descriptor);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a hard link: " + error.getMessage());
        }
        Path output = tempDir.resolve("coverage.xml");

        assertDescriptorOutputRejected(descriptor, report, output, "Exclusion report");
        assertTrue(Files.isSameFile(descriptor, report));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsCoverageOutputAtSourceDescriptorBeforeWritingExclusionReport() throws Exception {
        Path descriptor = copyProcessorDescriptor();
        Path report = tempDir.resolve("report.json");

        assertDescriptorOutputRejected(descriptor, report, descriptor, "Coverage output");
        assertFalse(Files.exists(report));
    }

    @Test
    void rejectsCoverageOutputLinkedToExternalFormDescriptor() throws Exception {
        assertExternalSourceFileProtected("ВнешняяОбработка/Forms/Форма.xml");
    }

    @Test
    void rejectsCoverageOutputLinkedToExternalFormModule() throws Exception {
        assertExternalSourceFileProtected("ВнешняяОбработка/Forms/Форма/Ext/Form/Module.bsl");
    }

    private void assertExternalSourceFileProtected(String relativeInput) throws Exception {
        Path descriptor = copyProcessorDescriptor();
        Path input = descriptor.resolveSibling(relativeInput);
        Path output = tempDir.resolve("result.xml");
        try {
            Files.createLink(output, input);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a hard link: " + error.getMessage());
        }
        byte[] original = Files.readAllBytes(input);
        Path report = tempDir.resolve("exclusions.json");

        assertDescriptorOutputRejected(descriptor, report, output, "Coverage output");
        assertArrayEquals(original, Files.readAllBytes(input));
        assertFalse(Files.exists(report));
    }

    private Path copyProcessorDescriptor() throws IOException {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("configurator"), project);
        return project.resolve("ВнешняяОбработка.xml");
    }

    private void assertDescriptorOutputRejected(Path descriptor, Path report, Path output, String outputLabel)
            throws IOException {
        byte[] original = Files.readAllBytes(descriptor);
        Path raw = tempDir.resolve("raw.xml");
        Files.writeString(raw, "<coverage><file path=\"/" + PROCESSOR_KEY
                + "\"><lineToCover covered=\"true\" lineNumber=\"2\"/></file></coverage>");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.CONVERT_COMMAND,
                "-P", descriptor.getParent().toString(), "-s", descriptor.getFileName().toString(),
                "-c", raw.toString(), "--exclusion-report", report.toString(), "-o", output.toString()),
                stderr.toString());
        assertTrue(stderr.toString().contains(outputLabel + " would overwrite input file"), stderr.toString());
        assertArrayEquals(original, Files.readAllBytes(descriptor));
    }

    @Test
    void markersWorkInConfiguratorFormat() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("configurator"), project);
        Files.writeString(project.resolve("ВнешняяОбработка/Ext/ObjectModule.bsl"), OBJECT_MODULE);
        Files.writeString(project.resolve("ВнешняяОбработка/Forms/Форма/Ext/Form/Module.bsl"), FORM_MODULE);

        assertEquals(Set.of("ВнешняяОбработка/Ext/ObjectModule.bsl:2 → Cover:off",
                        "ВнешняяОбработка/Forms/Форма/Ext/Form/Module.bsl:3 → Cover:off"),
                exclusionSources(project, "ВнешняяОбработка.xml"));
    }

    @Test
    void convertsConfiguratorProcessorAndExtensionTogether() throws Exception {
        Path project = copyProcessorWithExtension();

        assertProcessorConversion(project, "", PROCESSOR_KEY, "", true);
        assertProcessorConversion(project, "", PROCESSOR_KEY, "", true, "-x", PROCESSOR_URL);
        assertProcessorConversion(project, "", PROCESSOR_KEY, "extension=\"B\"", true,
                "-x", PROCESSOR_URL, "-e", "B");
    }

    @Test
    void keepsExplicitSourceIdentityDuringProcessorConversion() throws Exception {
        Path project = copyProcessorWithExtension();

        assertProcessorConversion(project, "sourceIdentity=\"true\"", PROCESSOR_KEY, "",
                false, "-x", PROCESSOR_URL);
        assertProcessorConversion(project, "", PROCESSOR_KEY, "sourceUrl=\"file:///different.epf\"",
                false, "-x", PROCESSOR_URL);
        assertProcessorConversion(project, "", PROCESSOR_KEY, "sourceUrl=\"\"",
                false, "-x", PROCESSOR_URL);
        assertProcessorConversion(project, "", "source-v1/-/-/" + PROCESSOR_KEY, "",
                false, "-x", PROCESSOR_URL);
        assertProcessorConversion(project, "", PROCESSOR_KEY, "",
                false, "-x", PROCESSOR_URL, "-e", "B");
    }

    @Test
    void singleProcessorIgnoresForeignLegacyExtensionWithTheSameModuleKey() throws Exception {
        assertSingleProcessorConversion("""
                <file path="/%1$s"><lineToCover covered="true" lineNumber="2"/></file>
                <file path="/%1$s" extension="A"><lineToCover covered="true" lineNumber="3"/></file>
                """.formatted(PROCESSOR_KEY));
    }

    @Test
    void singleProcessorUsesSelectedLegacyExtension() throws Exception {
        assertSingleProcessorConversion("""
                <file path="/%1$s" extension="B"><lineToCover covered="true" lineNumber="2"/></file>
                <file path="/%1$s" extension="A"><lineToCover covered="true" lineNumber="3"/></file>
                """.formatted(PROCESSOR_KEY), "-e", "B");
    }

    @Test
    void singleProcessorAcceptsBareLegacyKeyWithSelectedExtension() throws Exception {
        assertSingleProcessorConversion("""
                <file path="/%s"><lineToCover covered="true" lineNumber="2"/></file>
                """.formatted(PROCESSOR_KEY), "-e", "B");
    }

    @Test
    void singleProcessorKeepsExplicitSourceIdentity() throws Exception {
        assertSingleProcessorConversion("""
                <file path="/%1$s" extension="B" sourceUrl="%2$s">
                    <lineToCover covered="true" lineNumber="2"/>
                </file>
                <file path="/%1$s" extension="B" sourceUrl="file:///different.epf">
                    <lineToCover covered="true" lineNumber="3"/>
                </file>
                <file path="/source-v1/Qg/-/%1$s">
                    <lineToCover covered="true" lineNumber="3"/>
                </file>
                """.formatted(PROCESSOR_KEY, PROCESSOR_URL), "-e", "B");
    }

    @Test
    void rejectsProcessorDescriptorAsNamedOrExtensionSource() {
        for (String option : List.of("--source", "--extension")) {
            StringWriter stderr = new StringWriter();

            int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND,
                    "-P", EXTERNAL_PROCESSOR.resolve("configurator").toAbsolutePath().toString(),
                    option, "A=ВнешняяОбработка.xml");

            assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode, option);
            assertTrue(stderr.toString().contains("directory:"), stderr.toString());
        }
    }

    @Test
    void markersWorkInEdtFormat() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("edt/pc"), project);
        Path processor = project.resolve("src/ExternalDataProcessors/ВнешняяОбработка");
        Files.writeString(processor.resolve("ObjectModule.bsl"), OBJECT_MODULE);
        Files.writeString(processor.resolve("Forms/Форма/Module.bsl"), FORM_MODULE);

        assertEquals(Set.of("src/ExternalDataProcessors/ВнешняяОбработка/ObjectModule.bsl:2 → Cover:off",
                        "src/ExternalDataProcessors/ВнешняяОбработка/Forms/Форма/Module.bsl:3 → Cover:off"),
                exclusionSources(project, "src/ExternalDataProcessors/ВнешняяОбработка/ВнешняяОбработка.mdo"));
    }

    @Test
    void readsConfiguratorProcessorWithoutForms() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("configurator"), project);
        Files.writeString(project.resolve("ВнешняяОбработка/Ext/ObjectModule.bsl"), OBJECT_MODULE);
        deleteDirectory(project.resolve("ВнешняяОбработка/Forms"));
        Path descriptor = project.resolve("ВнешняяОбработка.xml");
        Files.writeString(descriptor, Files.readString(descriptor).replace("<Form>Форма</Form>", ""));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        Set<String> sources;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            sources = exclusionSources(project, "ВнешняяОбработка.xml");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(Set.of("ВнешняяОбработка/Ext/ObjectModule.bsl:2 → Cover:off"), sources);
        String output = log.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("Exception"), output);
    }

    @Test
    void skipsBrokenFormDescriptionOfConfiguratorProcessor() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("configurator"), project);
        Files.writeString(project.resolve("ВнешняяОбработка/Ext/ObjectModule.bsl"), OBJECT_MODULE);
        Files.writeString(project.resolve("ВнешняяОбработка/Forms/Форма.xml"), "<MetaDataObject>");

        JsonNode report = assertExternalFormDiagnostic(project, "ВнешняяОбработка.xml",
                "ВнешняяОбработка/Forms/Форма.xml", "cannot read form descriptor");
        assertEquals(Set.of("ВнешняяОбработка/Ext/ObjectModule.bsl:2 → Cover:off"), exclusionSources(report));
    }

    @Test
    void skipsEdtFormWithoutName() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("edt/pc"), project);
        Path processor = project.resolve("src/ExternalDataProcessors/ВнешняяОбработка");
        Files.writeString(processor.resolve("ObjectModule.bsl"), OBJECT_MODULE);
        Path descriptor = processor.resolve("ВнешняяОбработка.mdo");
        Files.writeString(descriptor, Files.readString(descriptor).replace("<name>Форма</name>", ""));

        JsonNode report = assertExternalFormDiagnostic(project, project.relativize(descriptor).toString(),
                "ВнешняяОбработка.mdo", "missing name");
        assertEquals(Set.of("src/ExternalDataProcessors/ВнешняяОбработка/ObjectModule.bsl:2 → Cover:off"),
                exclusionSources(report));
    }

    @Test
    void skipsEdtFormWithoutUuid() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        writeEdtExternalReportDescriptor(descriptor);
        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        Files.writeString(descriptor, Files.readString(descriptor).replaceFirst("<forms uuid=\"[^\"]+\"", "<forms"));

        assertExternalFormDiagnostic(tempDir, "ExternalReport.mdo", "ExternalReport.mdo", "missing UUID");
    }

    @Test
    void rejectsUnknownSourceFormat() throws Exception {
        Files.writeString(tempDir.resolve("Обработка.xml"), "<Something/>");
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-P", tempDir.toAbsolutePath().toString(), "-s", "Обработка.xml");

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Unknown source format"), stderr.toString());
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(file);
            }
        }
    }

    @Test
    void rejectsCoverageConfiguration() throws Exception {
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"exclude\":{\"lines\":[\"А\"]}}");
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-P", EXTERNAL_PROCESSOR.resolve("configurator").toAbsolutePath().toString(),
                "-s", "ВнешняяОбработка.xml",
                "--config", config.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Coverage configuration is not supported for external data processors"),
                stderr.toString());
    }

    private Set<String> exclusionSources(Path project, String sources) throws IOException {
        return exclusionSources(validateExternalSource(project, sources, true, CommandLine.ExitCode.OK));
    }

    private static Set<String> exclusionSources(JsonNode report) {
        Set<String> result = new TreeSet<>();
        for (JsonNode entry : report.get("entries")) {
            result.add(entry.get("source").asText());
        }
        return result;
    }

    private JsonNode validateExternalSource(Path project, String source, boolean strict, int expectedExitCode)
            throws IOException {
        Path report = tempDir.resolve("exclusions.json");
        Files.deleteIfExists(report);
        List<String> args = new ArrayList<>(List.of(PipeMessages.VALIDATE_COMMAND, "-P", project.toString(),
                "-s", source, "--exclusion-report", report.toString()));
        if (strict) {
            args.add("--strict");
        }
        StringWriter stderr = new StringWriter();
        assertEquals(expectedExitCode, execute(stderr, args.toArray(new String[0])), stderr.toString());
        return new ObjectMapper().readTree(report.toFile());
    }

    private Path copyProcessorWithExtension() throws IOException {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("configurator"), project);
        TestUtils.copyDirectory(Path.of("src/test/resources/extensions/src/cfe/Доработки"), project.resolve("A"));
        return project;
    }

    private void assertSingleProcessorConversion(String rawFiles, String... selection) throws Exception {
        Path project = EXTERNAL_PROCESSOR.resolve("configurator").toAbsolutePath();
        Path raw = tempDir.resolve("single-raw.xml");
        Files.writeString(raw, "<coverage version=\"1\">\n" + rawFiles + "</coverage>\n");
        Path output = tempDir.resolve("single-coverage.xml");
        StringWriter stderr = new StringWriter();
        List<String> arguments = new ArrayList<>(List.of(PipeMessages.CONVERT_COMMAND,
                "-P", project.toString(), "-s", "ВнешняяОбработка.xml", "-x", PROCESSOR_URL,
                "-c", raw.toString(), "-o", output.toString()));
        arguments.addAll(Arrays.asList(selection));

        assertEquals(CommandLine.ExitCode.OK, execute(stderr, arguments.toArray(String[]::new)), stderr.toString());
        assertEquals(Map.of(2, true, 3, false),
                MultipleSourcesTest.coverage(output).get("ВнешняяОбработка/Ext/ObjectModule.bsl"));
    }

    private void assertProcessorConversion(Path project, String coverageAttributes, String processorKey,
                                           String processorAttributes, boolean processorCovered,
                                           String... selection) throws Exception {
        Path raw = tempDir.resolve("raw.xml");
        Files.writeString(raw, """
                <coverage version="1" %s>
                    <file path="/%s" %s>
                        <lineToCover covered="true" lineNumber="2"/>
                    </file>
                    <file extension="A" path="/888c0948-df1b-462b-809c-2a3dbef0b13f/d5963243-262e-4398-b4d7-fb16d06484f6">
                        <lineToCover covered="true" lineNumber="5"/>
                    </file>
                </coverage>
                """.formatted(coverageAttributes, processorKey, processorAttributes));
        Path output = tempDir.resolve("coverage.xml");
        Files.deleteIfExists(output);
        StringWriter stderr = new StringWriter();
        List<String> arguments = new ArrayList<>(List.of(PipeMessages.CONVERT_COMMAND,
                "-P", project.toString(), "-s", "ВнешняяОбработка.xml", "--extension", "A=A",
                "-c", raw.toString(), "-o", output.toString()));
        arguments.addAll(Arrays.asList(selection));

        assertEquals(CommandLine.ExitCode.OK, execute(stderr, arguments.toArray(String[]::new)), stderr.toString());
        Map<String, Map<Integer, Boolean>> coverage = MultipleSourcesTest.coverage(output);
        assertEquals(Map.of(2, processorCovered, 3, false), coverage.get("ВнешняяОбработка/Ext/ObjectModule.bsl"),
                coverageAttributes + " " + processorKey + " " + processorAttributes + " " + Arrays.toString(selection));
        assertEquals(true, coverage.get("A/CommonModules/Калькулятор/Ext/Module.bsl").get(5));
    }

    @Test
    void protectsEdtExternalProcessorProjectFiles() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("edt/pc"), project);
        Path descriptor = project.resolve("src/ExternalDataProcessors/ВнешняяОбработка/ВнешняяОбработка.mdo");
        Path raw = Files.writeString(tempDir.resolve("raw.xml"), "<coverage version=\"1\"/>");
        Path output = tempDir.resolve("coverage.xml");
        Path report = tempDir.resolve("exclusions.json");
        for (String name : List.of(".project", "DT-INF/PROJECT.PMF", ".settings/org.eclipse.core.resources.prefs")) {
            Path input = project.resolve(name);
            byte[] original = Files.readAllBytes(input);
            try {
                Files.createLink(output, input);
            } catch (IOException | UnsupportedOperationException error) {
                assumeTrue(false, () -> "Cannot create a hard link: " + error.getMessage());
            }
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.CONVERT_COMMAND,
                    "-P", project.toString(), "-s", project.relativize(descriptor).toString(),
                    "-c", raw.toString(), "-o", output.toString()), name + ": " + stderr);
            assertTrue(stderr.toString().contains("would overwrite input file"), stderr.toString());
            assertArrayEquals(original, Files.readAllBytes(input), name);
            Files.delete(output);

            Files.createLink(report, input);
            stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                    "-s", descriptor.toString(), "--exclusion-report", report.toString()), name + ": " + stderr);
            assertTrue(stderr.toString().contains("would overwrite input file"), stderr.toString());
            assertArrayEquals(original, Files.readAllBytes(input), name);
            Files.delete(report);
        }
    }

    @Test
    void protectsStandaloneEdtTemplateFromValidateAndConvert() throws Exception {
        Path descriptor = tempDir.resolve("export/ExternalReport.mdo");
        Path template = writeStandaloneEdtDcsReport(descriptor);
        byte[] original = Files.readAllBytes(template);
        Path report = tempDir.resolve("exclusions.json");

        assertValidateOutputRejected(descriptor, template);
        assertDescriptorOutputRejected(descriptor, report, template, "Coverage output");
        assertDescriptorOutputRejected(descriptor, template, tempDir.resolve("coverage.xml"), "Exclusion report");
        assertArrayEquals(original, Files.readAllBytes(template));
        assertFalse(Files.exists(report));
        assertFalse(Files.exists(tempDir.resolve("coverage.xml")));
    }

    @Test
    void protectsOtherDeclaredEdtResources() throws Exception {
        Path descriptor = tempDir.resolve("export/ExternalReport.mdo");
        writeStandaloneEdtDcsReport(descriptor);
        String metadata = Files.readString(descriptor).replace("</mdclass:ExternalReport>",
                "<forms uuid=\"7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88\"><name>Settings</name></forms>"
                        + "<commands uuid=\"e49bfc5e-1d10-482c-8d99-65f6cb86821b\"><name>Run</name></commands>"
                        + "</mdclass:ExternalReport>");
        Files.writeString(descriptor, metadata);

        for (String resource : List.of("Forms/Settings/Details.xml", "Commands/Run/CommandModule.bsl")) {
            Path input = descriptor.resolveSibling(resource);
            Files.createDirectories(input.getParent());
            Files.writeString(input, "<resource/>");
            assertValidateOutputRejected(descriptor, input);
            assertEquals("<resource/>", Files.readString(input));
        }
    }

    @Test
    void protectsStandaloneEdtTemplateThroughAliases() throws Exception {
        Path descriptor = tempDir.resolve("export/ExternalReport.mdo");
        Path template = writeStandaloneEdtDcsReport(descriptor);
        byte[] original = Files.readAllBytes(template);
        Path source = tempDir.resolve("Shortcut.mdo");
        Path directory = template.getParent();
        Path physicalDirectory = tempDir.resolve("template-files");
        Files.move(directory, physicalDirectory);
        Path hardLink = tempDir.resolve("hard-link.json");
        Path symbolicLink = tempDir.resolve("symbolic-link.json");
        try {
            Files.createSymbolicLink(source, descriptor);
            Files.createSymbolicLink(directory, physicalDirectory);
            Files.createLink(hardLink, template);
            Files.createSymbolicLink(symbolicLink, template);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create an alias: " + error.getMessage());
        }
        for (Path output : List.of(hardLink, symbolicLink, physicalDirectory.resolve("new-report.json"))) {
            assertValidateOutputRejected(source, output);
            assertDescriptorOutputRejected(source, output, tempDir.resolve("coverage.xml"), "Exclusion report");
        }

        assertArrayEquals(original, Files.readAllBytes(template));
        assertFalse(Files.exists(physicalDirectory.resolve("new-report.json")));
        assertFalse(Files.exists(tempDir.resolve("coverage.xml")));
    }

    @Test
    void protectsDeclaredEdtTemplatePathsWhileAllowingSiblingReports() throws Exception {
        Path descriptor = tempDir.resolve("export/ExternalReport.mdo");
        Path template = writeStandaloneEdtDcsReport(descriptor);
        Files.delete(template);
        Files.delete(template.getParent());

        assertValidateOutputRejected(descriptor, template);
        assertFalse(Files.exists(template.getParent()));
        for (Path neighbor : List.of(descriptor.resolveSibling("exclusions.json"),
                template.getParent().resolveSibling("analysis/exclusions.json"))) {
            Files.createDirectories(neighbor.getParent());
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                    "-s", descriptor.toString(), "--exclusion-report", neighbor.toString()), stderr.toString());
            assertTrue(Files.isRegularFile(neighbor));
        }
    }

    @Test
    void rejectsDuplicateExternalFormUuidsBeforePublishing() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        Files.writeString(edt, Files.readString(edt).replace("</mdclass:ExternalReport>",
                "<forms uuid=\"7B4E9BE1-F638-4A2A-9CC1-41BDB5E73C88\"><name>Second</name></forms>"
                        + "</mdclass:ExternalReport>"));
        Files.writeString(configurator, Files.readString(configurator).replace("</ChildObjects>",
                "<Form>Second</Form></ChildObjects>"));
        Path configuratorForms = tempDir.resolve("ExternalReport/Forms");
        Files.writeString(configuratorForms.resolve("Second.xml"), Files.readString(configuratorForms.resolve("Форма.xml"))
                .replace("Форма", "Second").replace("7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88",
                        "7B4E9BE1-F638-4A2A-9CC1-41BDB5E73C88"));
        Path edtSecond = tempDir.resolve("Forms/Second/Form.form");
        Path configuratorSecond = configuratorForms.resolve("Second/Ext/Form.xml");
        Files.createDirectories(edtSecond.getParent());
        Files.createDirectories(configuratorSecond.getParent());
        Files.writeString(edtSecond, "<form:Form xmlns:form=\"http://g5.1c.ru/v8/dt/form\"/>");
        Files.writeString(configuratorSecond, "<Form xmlns=\"http://v8.1c.ru/8.3/xcf/logform\"/>");

        writeExternalModule(edtSecond.resolveSibling("Module.bsl"));
        writeExternalModule(configuratorSecond.resolveSibling("Form/Module.bsl"));

        Path raw = Files.writeString(tempDir.resolve("duplicate-raw.xml"), "<coverage version=\"1\">"
                + rawRecord("7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88/32e087ab-1491-49b6-aba7-43571b41ac2b", "", 2)
                + "</coverage>");
        Path report = Files.writeString(tempDir.resolve("exclusions.json"), "{}");
        Path output = Files.writeString(tempDir.resolve("coverage.xml"), "<coverage/>");
        for (Path descriptor : List.of(edt, configurator)) {
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                    "-s", descriptor.toString(), "--strict", "--exclusion-report", report.toString()), stderr.toString());
            assertTrue(stderr.toString().contains("Duplicate external form UUID"), stderr.toString());
            stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.CONVERT_COMMAND,
                    "-s", descriptor.toString(), "-c", raw.toString(), "-o", output.toString(),
                    "--exclusion-report", report.toString()), stderr.toString());
            assertTrue(stderr.toString().contains("Duplicate external form UUID"), stderr.toString());
            assertEquals("{}", Files.readString(report));
            assertEquals("<coverage/>", Files.readString(output));
        }
    }

    @Test
    void validatesConfiguratorDcsReportWithoutObjectModule() throws Exception {
        Path descriptor = writeConfiguratorDcsReportDescriptor();
        Path template = tempDir.resolve("ExternalReport/Templates/Main/Ext/Template.xml");
        Files.createDirectories(template.getParent());
        Files.writeString(template, "<DataCompositionSchema xmlns=\"http://v8.1c.ru/8.1/data-composition-system/schema\"/>");

        assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                .path("warnings").isEmpty());
        assertTrue(convertExternalSource(descriptor.toString()).isEmpty());
        Path objectModule = tempDir.resolve("ExternalReport/Ext/ObjectModule.bsl");
        Files.createDirectories(objectModule.getParent());
        Path protectedModule = Files.write(objectModule.resolveSibling("ObjectModule.bin"), new byte[] {1});
        assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                .path("warnings").isEmpty());
        assertTrue(convertExternalSource(descriptor.toString()).isEmpty());
        Files.delete(protectedModule);
        writeExternalModule(objectModule);
        assertEquals(Map.of("ExternalReport/Ext/ObjectModule.bsl", Map.of(2, true, 3, false)),
                convertExternalSource(descriptor.toString()));
        Files.delete(objectModule);
        Files.delete(template);
        assertMissingMainSchema(descriptor);
        writeExternalModule(objectModule);
        assertMissingMainSchema(descriptor);
    }

    @Test
    void convertsDeclaredExternalCommandModulesInBothFormats() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        String uuid = "7B4E9BE1-F638-4A2A-9CC1-41BDB5E73C88";
        Files.writeString(edt, Files.readString(edt).replace("</mdclass:ExternalReport>",
                "<mdclass:commands uuid=\"" + uuid + "\"><mdclass:name>Run</mdclass:name></mdclass:commands>"
                        + "<other:commands xmlns:other=\"urn:other\" uuid=\"" + uuid + "\"><name>Stale</name></other:commands>"
                        + "</mdclass:ExternalReport>"));
        Files.writeString(configurator, Files.readString(configurator).replace("</ChildObjects>",
                "<Command uuid=\"" + uuid + "\"><Properties><Name>Run</Name></Properties></Command></ChildObjects>"));

        String commandKey = "7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88/078a6af8-d22c-4248-9c33-7e90075a3d2c";
        for (Path descriptor : List.of(edt, configurator)) {
            boolean edtFormat = descriptor.equals(edt);
            String commands = edtFormat ? "Commands" : "ExternalReport/Commands";
            String module = edtFormat ? "CommandModule.bsl" : "Ext/CommandModule.bsl";
            Path command = tempDir.resolve(commands + "/Run/" + module);
            writeExternalModule(command);
            writeExternalModule(tempDir.resolve(commands + "/Stale/" + module));
            Path raw = Files.writeString(tempDir.resolve("commands-raw.xml"), "<coverage version=\"1\">"
                    + rawRecord(commandKey, "", 2) + "</coverage>");
            Path output = tempDir.resolve("commands-coverage.xml");
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, execute(stderr, PipeMessages.CONVERT_COMMAND,
                    "-s", descriptor.toString(), "-c", raw.toString(), "-o", output.toString()), stderr.toString());
            Map<String, Map<Integer, Boolean>> covered = MultipleSourcesTest.coverage(output);
            assertEquals(Map.of(2, true, 3, false), covered.get(commands + "/Run/" + module));
            assertFalse(covered.containsKey(commands + "/Stale/" + module), covered.toString());

            Files.move(command, command.resolveSibling("CommandModule.bin"));
            Files.writeString(raw, "<coverage version=\"1\" sourceIdentity=\"true\">"
                    + rawRecord(commandKey, "", 2)
                    + rawRecord("7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88/32e087ab-1491-49b6-aba7-43571b41ac2b",
                            " extension=\"Other\"", 2) + "</coverage>");
            stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, execute(stderr, PipeMessages.CONVERT_COMMAND,
                    "-s", descriptor.toString(), "-c", raw.toString(), "-o", output.toString()), stderr.toString());
            assertFalse(MultipleSourcesTest.coverage(output).containsKey(commands + "/Run/" + module));
        }
    }

    @Test
    void rejectsDuplicateExternalCommandDeclarationsEvenWithoutBothModules() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        String lower = "342ec3c7-82d4-42bb-a5ff-8a756f110744";
        String upper = "342EC3C7-82D4-42BB-A5FF-8A756F110744";
        Files.writeString(edt, Files.readString(edt).replace("</mdclass:ExternalReport>",
                "<commands uuid=\"" + lower + "\"><name>Run</name></commands>"
                        + "<commands uuid=\"" + upper + "\"><name>Copied</name></commands></mdclass:ExternalReport>"));
        Files.writeString(configurator, Files.readString(configurator).replace("</ChildObjects>",
                "<Command uuid=\"" + lower + "\"><Properties><Name>Run</Name></Properties></Command>"
                        + "<Command uuid=\"" + upper + "\"><Properties><Name>Copied</Name></Properties></Command></ChildObjects>"));
        writeExternalModule(tempDir.resolve("Commands/Run/CommandModule.bsl"));
        writeExternalModule(tempDir.resolve("ExternalReport/Commands/Run/Ext/CommandModule.bsl"));

        Path report = Files.writeString(tempDir.resolve("command-duplicates.json"), "{}");
        for (Path descriptor : List.of(edt, configurator)) {
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                    "-s", descriptor.toString(), "--strict", "--exclusion-report", report.toString()), stderr.toString());
            assertTrue(stderr.toString().contains("Duplicate external command UUID"), stderr.toString());
            assertEquals("{}", Files.readString(report));
        }
    }

    @Test
    void rejectsConflictingExternalCommandNamesBeforePublishing() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        String run = "342ec3c7-82d4-42bb-a5ff-8a756f110744";
        String copied = "ede53294-25c6-4e37-8be0-bd91e2603a33";

        for (Path descriptor : List.of(edt, configurator)) {
            boolean edtFormat = descriptor.equals(edt);
            String original = Files.readString(descriptor);
            String marker = edtFormat ? "</mdclass:ExternalReport>" : "</ChildObjects>";
            String command = edtFormat ? "<commands uuid=\"%s\"><name>%s</name></commands>"
                    : "<Command uuid=\"%s\"><Properties><Name>%s</Name></Properties></Command>";
            Path module = tempDir.resolve(edtFormat ? "Commands/Run/CommandModule.bsl"
                    : "ExternalReport/Commands/Run/Ext/CommandModule.bsl");
            writeExternalModule(module);

            for (String repeated : List.of("Run", "run")) {
                Files.writeString(descriptor, original.replace(marker,
                        String.format(command, run, "Run") + String.format(command, copied, repeated) + marker));
                assertConflictingExternalName(descriptor, "Duplicate external command name");
            }

            Path binary = Files.move(module, module.resolveSibling("CommandModule.bin"));
            assertConflictingExternalName(descriptor, "Duplicate external command name");
            Files.delete(binary);
            assertConflictingExternalName(descriptor, "Duplicate external command name");
        }
    }

    @Test
    void rejectsConflictingExternalFormNamesBeforePublishing() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        writeEdtExternalReportDescriptor(edt);
        String edtMetadata = Files.readString(edt);
        Path edtModule = tempDir.resolve("Forms/Форма/Module.bsl");
        writeExternalModule(edtModule);
        for (String repeated : List.of("Форма", "форма")) {
            Files.writeString(edt, edtMetadata.replace("</mdclass:ExternalReport>",
                    "<forms uuid=\"ede53294-25c6-4e37-8be0-bd91e2603a33\"><name>" + repeated + "</name></forms>"
                            + "</mdclass:ExternalReport>"));
            assertConflictingExternalName(edt, "Duplicate external form name");
        }

        Files.delete(edtModule);
        Files.writeString(edtModule.resolveSibling("Form.form"), "<form:Form xmlns:form=\"http://g5.1c.ru/v8/dt/form\"/>");
        assertConflictingExternalName(edt, "Duplicate external form name");

        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        Files.writeString(configurator, Files.readString(configurator).replace("</ChildObjects>",
                "<Form>форма</Form></ChildObjects>"));
        assertConflictingExternalName(configurator, "Duplicate external form name");
    }

    @Test
    void keepsDistinctExternalChildrenAndRepeatedExactConfiguratorFormReferences() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Files.writeString(descriptor, Files.readString(descriptor)
                .replace(" xmlns=\"http://v8.1c.ru/8.3/MDClasses\"", "")
                .replace("</ChildObjects>", "<Form>Форма</Form>"
                        + "<Command uuid=\"342ec3c7-82d4-42bb-a5ff-8a756f110744\">"
                        + "<Properties><Name>Форма</Name></Properties></Command>"
                        + "<Command uuid=\"ede53294-25c6-4e37-8be0-bd91e2603a33\">"
                        + "<Properties><Name>Other</Name></Properties></Command></ChildObjects>"));
        writeExternalModule(tempDir.resolve("ExternalReport/Commands/Форма/Ext/CommandModule.bsl"));
        writeExternalModule(tempDir.resolve("ExternalReport/Commands/Other/Ext/CommandModule.bsl"));

        JsonNode report = validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK);
        assertEquals(8, report.path("totalLines").asInt());
        assertTrue(report.path("warnings").isEmpty(), report.toString());
        assertEquals(Set.of("ExternalReport/Ext/ObjectModule.bsl", "ExternalReport/Forms/Форма/Ext/Form/Module.bsl",
                        "ExternalReport/Commands/Форма/Ext/CommandModule.bsl", "ExternalReport/Commands/Other/Ext/CommandModule.bsl"),
                convertExternalSource(descriptor.toString()).keySet());
    }

    private void assertConflictingExternalName(Path descriptor, String diagnostic) throws IOException {
        Path raw = Files.writeString(tempDir.resolve("ambiguous-raw.xml"), "<coverage version=\"1\"/>");
        Path report = Files.writeString(tempDir.resolve("ambiguous-exclusions.json"), "{}");
        Path output = Files.writeString(tempDir.resolve("ambiguous-coverage.xml"), "<coverage/>");
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-s", descriptor.toString(), "--strict", "--exclusion-report", report.toString()), stderr.toString());
        assertTrue(stderr.toString().contains(diagnostic), stderr.toString());

        stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.CONVERT_COMMAND,
                "-s", descriptor.toString(), "-c", raw.toString(), "-o", output.toString(),
                "--exclusion-report", report.toString()), stderr.toString());
        assertTrue(stderr.toString().contains(diagnostic), stderr.toString());
        assertEquals("{}", Files.readString(report));
        assertEquals("<coverage/>", Files.readString(output));
    }

    @Test
    void validatesConfiguratorMetadataNamespaceAndAcceptsLegacyBareXml() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        String metadata = Files.readString(descriptor);
        String namespace = "http://v8.1c.ru/8.3/MDClasses";
        Files.writeString(descriptor, metadata.replace(" xmlns=\"" + namespace + "\"", ""));
        assertEquals(2, convertExternalSource(descriptor.toString()).size());

        Files.writeString(descriptor, metadata.replace(namespace, "urn:other"));
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-s", descriptor.toString(), "--strict"), stderr.toString());
        assertTrue(stderr.toString().contains("Unknown source format"), stderr.toString());

        Files.writeString(descriptor, metadata);
        Path form = tempDir.resolve("ExternalReport/Forms/Форма.xml");
        Files.writeString(form, Files.readString(form).replace(namespace, "urn:other"));
        assertExternalFormDiagnostic(tempDir, descriptor.toString(), "ExternalReport/Forms/Форма.xml",
                "expected a MetaDataObject element");
    }

    @Test
    void acceptsQualifiedAndLegacyConfiguratorMetadataNames() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path form = tempDir.resolve("ExternalReport/Forms/Форма.xml");
        String metadata = Files.readString(descriptor);
        String formMetadata = Files.readString(form);

        Files.writeString(descriptor, qualifyConfiguratorMetadata(metadata));
        Files.writeString(form, qualifyConfiguratorMetadata(formMetadata));
        assertEquals(2, convertExternalSource(descriptor.toString()).size());
        assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                .path("warnings").isEmpty());

        Files.writeString(descriptor, metadata.replace(" xmlns=\"http://v8.1c.ru/8.3/MDClasses\"", ""));
        Files.writeString(form, formMetadata.replace(" xmlns=\"http://v8.1c.ru/8.3/MDClasses\"", ""));
        assertEquals(2, convertExternalSource(descriptor.toString()).size());
    }

    @Test
    void ignoresForeignConfiguratorChildDeclarationsAndKeepsTheirFilesProtected() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        String metadata = Files.readString(descriptor);
        String commandUuid = "342ec3c7-82d4-42bb-a5ff-8a756f110744";
        String command = "<Command uuid=\"" + commandUuid
                + "\"><Properties><Name>Foreign</Name></Properties></Command>";
        Path commandModule = tempDir.resolve("ExternalReport/Commands/Foreign/Ext/CommandModule.bsl");
        writeExternalModule(commandModule);
        Path raw = Files.writeString(tempDir.resolve("foreign-command-raw.xml"), "<coverage version=\"1\">"
                + rawRecord(commandUuid + "/078a6af8-d22c-4248-9c33-7e90075a3d2c", "", 2) + "</coverage>");
        Path coverage = tempDir.resolve("foreign-command-coverage.xml");

        for (String foreignChildren : List.of(
                "<ChildObjects>" + command.replace("<Command ", "<Command xmlns=\"urn:other\" ")
                        + "<Form xmlns=\"urn:other\">Foreign</Form><Form>Форма</Form></ChildObjects>",
                "<ChildObjects>" + command.replace("<Command ", "<Command xmlns=\"\" ")
                        + "<Form xmlns=\"\">Foreign</Form><Form>Форма</Form></ChildObjects>",
                "<ChildObjects xmlns=\"urn:other\">" + command + "<Form>Foreign</Form></ChildObjects>"
                        + "<ChildObjects><Form>Форма</Form></ChildObjects>")) {
            Files.writeString(descriptor, metadata.replace("<ChildObjects><Form>Форма</Form></ChildObjects>", foreignChildren));
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, execute(stderr, PipeMessages.CONVERT_COMMAND,
                    "-s", descriptor.toString(), "-c", raw.toString(), "-o", coverage.toString()), stderr.toString());
            Map<String, Map<Integer, Boolean>> files = MultipleSourcesTest.coverage(coverage);
            assertEquals(Set.of("ExternalReport/Ext/ObjectModule.bsl", "ExternalReport/Forms/Форма/Ext/Form/Module.bsl"),
                    files.keySet());
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
            assertValidateOutputRejected(descriptor, commandModule);
        }
    }

    @Test
    void rejectsForeignConfiguratorObjectAndDiagnosesForeignFormProperties() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        String metadata = Files.readString(descriptor);
        Files.writeString(descriptor, metadata.replace("<ExternalReport ", "<ExternalReport xmlns=\"urn:other\" "));
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-s", descriptor.toString(), "--strict"), stderr.toString());
        assertTrue(stderr.toString().contains("Expected an external data processor or report descriptor"), stderr.toString());

        Files.writeString(descriptor, metadata);
        Path form = tempDir.resolve("ExternalReport/Forms/Форма.xml");
        String formMetadata = Files.readString(form);
        for (String invalid : List.of(formMetadata.replace("<Form ", "<Form xmlns=\"urn:other\" "),
                formMetadata.replace("<Properties>", "<Properties xmlns=\"urn:other\">"),
                formMetadata.replace("<Name>", "<Name xmlns=\"urn:other\">"),
                formMetadata.replace("<Form uuid=", "<Form xmlns:id=\"urn:other\" id:uuid="))) {
            Files.writeString(form, invalid);
            assertExternalFormDiagnostic(tempDir, descriptor.toString(), "ExternalReport/Forms/Форма.xml",
                    "cannot read form descriptor");
        }
    }

    @Test
    void diagnosesEmptyAndInvalidDeclaredConfiguratorFormNames() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        String metadata = Files.readString(descriptor);
        for (String invalid : List.of("<Form/>", "<Form>   </Form>", "<Form>../Outside</Form>",
                "<Form>bad:name</Form>", "<Form><inner>Форма</inner></Form>")) {
            Files.writeString(descriptor, metadata.replace("<Form>Форма</Form>", invalid));
            assertExternalFormDiagnostic(tempDir, descriptor.toString(), "ExternalReport.xml", "invalid declared form name");
            assertEquals(Map.of("ExternalReport/Ext/ObjectModule.bsl", Map.of(2, true, 3, false)),
                    convertExternalSource(descriptor.toString()));
        }
    }

    @Test
    void checksConfiguratorMainTemplateIdentityAndNamespace() throws Exception {
        Path descriptor = writeConfiguratorDcsReportDescriptor();
        Path schema = tempDir.resolve("ExternalReport/Templates/Main/Ext/Template.xml");
        Files.createDirectories(schema.getParent());
        Files.writeString(schema, "<dcs:DataCompositionSchema"
                + " xmlns:dcs=\"http://v8.1c.ru/8.1/data-composition-system/schema\"/>");
        Path metadata = schema.getParent().getParent().resolveSibling("Main.xml");
        String bare = "<MetaDataObject><Template><Properties><Name>Main</Name><TemplateType>DataCompositionSchema</TemplateType>"
                + "</Properties></Template></MetaDataObject>";
        String canonical = bare.replace("<MetaDataObject>", "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\">");

        for (String invalid : List.of(canonical.replace("<Name>Main", "<Name>Other"),
                canonical.replace("http://v8.1c.ru/8.3/MDClasses", "urn:other"),
                canonical.replace("<Template>", "<Template xmlns=\"urn:other\">"),
                canonical.replace("<Properties>", "<Properties xmlns=\"urn:other\">"),
                canonical.replace("<Name>", "<Name xmlns=\"urn:other\">"),
                canonical.replace("<TemplateType>", "<TemplateType xmlns=\"\">"))) {
            Files.writeString(metadata, invalid);
            assertMissingMainSchema(descriptor);
        }

        for (String valid : List.of(bare, canonical, qualifyConfiguratorMetadata(canonical))) {
            Files.writeString(metadata, valid);
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
        }

        String report = Files.readString(descriptor);
        Files.writeString(descriptor, report.replace("<Template>", "<Template xmlns=\"urn:other\">"));
        assertMissingMainSchema(descriptor);
        Files.writeString(descriptor, report);
        Path other = schema.resolveSibling("../../Other/Ext/Template.xml").normalize();
        Files.createDirectories(other.getParent());
        Files.move(schema, other);
        assertMissingMainSchema(descriptor);
    }

    private static String qualifyConfiguratorMetadata(String xml) {
        return xml.replace("xmlns=", "xmlns:m=").replaceAll("(</?)([A-Z][A-Za-z]+)", "$1m:$2");
    }

    @Test
    void excludesUndeclaredConfiguratorFormsAndProtectsTheirFiles() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path forms = tempDir.resolve("ExternalReport/Forms");
        Path form = forms.resolve("Форма.xml");
        Path formModule = forms.resolve("Форма/Ext/Form/Module.bsl");
        Path stale = Files.writeString(forms.resolve("Stale.xml"), Files.readString(form).replace("Форма", "Stale"));
        writeExternalModule(forms.resolve("Stale/Ext/Form/Module.bsl"));
        String metadata = Files.readString(descriptor);

        Map<String, Map<Integer, Boolean>> declared = convertExternalSource(descriptor.toString());
        assertEquals(Map.of("ExternalReport/Ext/ObjectModule.bsl", Map.of(2, true, 3, false),
                "ExternalReport/Forms/Форма/Ext/Form/Module.bsl", Map.of(2, true, 3, false)), declared);

        for (String children : List.of("<ChildObjects/>", "")) {
            Files.writeString(descriptor, metadata.replace("<ChildObjects><Form>Форма</Form></ChildObjects>", children));
            assertEquals(Map.of("ExternalReport/Ext/ObjectModule.bsl", Map.of(2, true, 3, false)),
                    convertExternalSource(descriptor.toString()));
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
        }
        for (Path input : List.of(form, formModule, stale)) {
            byte[] original = Files.readAllBytes(input);
            assertValidateOutputRejected(descriptor, input);
            assertArrayEquals(original, Files.readAllBytes(input));
        }
    }

    @Test
    void diagnosesConfiguratorFormDescriptorCopiedUnderAnotherName() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        Path form = tempDir.resolve("ExternalReport/Forms/Форма.xml");
        Files.writeString(form, Files.readString(form).replace("Форма", "Unrelated"));
        writeExternalModule(form.resolveSibling("Unrelated/Ext/Form/Module.bsl"));

        assertExternalFormDiagnostic(tempDir, descriptor.toString(), "ExternalReport/Forms/Форма.xml", "expected form name");
        assertEquals(Map.of("ExternalReport/Ext/ObjectModule.bsl", Map.of(2, true, 3, false)),
                convertExternalSource(descriptor.toString()));
    }

    @Test
    void readsEdtExternalDescriptorAndFeatureNamesByNamespace() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        writeEdtExternalReportDescriptor(descriptor);
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        String metadata = Files.readString(descriptor);
        for (String alternative : List.of(metadata.replace("mdclass:", "m:").replace("xmlns:mdclass", "xmlns:m"),
                metadata.replace("mdclass:", "").replace("xmlns:mdclass", "xmlns"),
                metadata.replace("mdclass:", "m:").replace("xmlns:mdclass", "xmlns:m")
                        .replaceAll("(</?)(forms|name)", "$1m:$2"))) {
            Files.writeString(descriptor, alternative);
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
            assertEquals(Map.of("Forms/Форма/Module.bsl", Map.of(2, true, 3, false)),
                    convertExternalSource(descriptor.toString()));
        }
    }

    @Test
    void rejectsEdtExternalDescriptorsWithUnrelatedNamespaces() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        writeEdtExternalReportDescriptor(descriptor);
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        String metadata = Files.readString(descriptor);
        for (String alternative : List.of(metadata.replace("http://g5.1c.ru/v8/dt/metadata/mdclass", "urn:other"),
                metadata.replace("mdclass:", "").replace("xmlns:mdclass", "xmlns")
                        .replace("http://g5.1c.ru/v8/dt/metadata/mdclass", "urn:other"))) {
            Files.writeString(descriptor, alternative);
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                    "-s", descriptor.toString(), "--strict"), stderr.toString());
            assertTrue(stderr.toString().contains("Unknown source format"), stderr.toString());
        }
    }

    @Test
    void requiresFormatSpecificQNamesForModulelessExternalFormLayouts() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        Path edtForm = tempDir.resolve("Forms/Форма/Form.form");
        Path cfgForm = tempDir.resolve("ExternalReport/Forms/Форма/Ext/Form.xml");
        Files.createDirectories(edtForm.getParent());
        Files.delete(cfgForm.resolveSibling("Form/Module.bsl"));

        String edtNamespace = "http://g5.1c.ru/v8/dt/form";
        String configuratorNamespace = "http://v8.1c.ru/8.3/xcf/logform";
        for (Path descriptor : List.of(edt, configurator)) {
            boolean edtFormat = descriptor.equals(edt);
            Path form = edtFormat ? edtForm : cfgForm;
            String correct = edtFormat ? edtNamespace : configuratorNamespace;
            String otherFormat = edtFormat ? configuratorNamespace : edtNamespace;
            for (String invalid : List.of("<Form/>", "<x:Form xmlns:x=\"urn:other\"/>",
                    "<Form xmlns=\"" + otherFormat + "\"/>")) {
                Files.writeString(form, invalid);
                JsonNode warnings = validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.SOFTWARE)
                        .path("warnings");
                assertEquals(1, warnings.size(), warnings.toString());
                assertTrue(warnings.get(0).asText().contains("cannot read form layout"), warnings.toString());
            }

            Files.writeString(form, "<preview:Form xmlns:preview=\"" + correct + "\"/>");
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
            Files.writeString(form, "<Form xmlns=\"" + correct + "\"/>");
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
        }
    }

    @Test
    void validatesModulelessExternalFormsOnlyWithoutHandlers() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        Path edtForm = tempDir.resolve("Forms/Форма/Form.form");
        Path configForm = tempDir.resolve("ExternalReport/Forms/Форма/Ext/Form.xml");
        Files.createDirectories(edtForm.getParent());
        Path configModule = configForm.resolveSibling("Form/Module.bsl");
        Files.delete(configModule);
        for (Path descriptor : List.of(edt, configurator)) {
            boolean edtFormat = descriptor.equals(edt);
            Path form = edtFormat ? edtForm : configForm;
            String open = edtFormat ? "<form:Form xmlns:form=\"http://g5.1c.ru/v8/dt/form\">"
                    : "<Form xmlns=\"http://v8.1c.ru/8.3/xcf/logform\">";
            String close = edtFormat ? "</form:Form>" : "</Form>";
            Files.writeString(form, open + close);
            JsonNode warnings = validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings");
            assertTrue(warnings.isEmpty(), warnings.toString());
            assertEquals(1, convertExternalSource(descriptor.toString()).size());

            String handlers = edtFormat ? "<handlers><event>OnOpen</event><name>OnOpen</name></handlers>"
                    : "<Events><Event name=\"OnOpen\">OnOpen</Event></Events>";
            String commands = edtFormat ? "<formCommands><action><handler><name>Run</name></handler></action></formCommands>"
                    : "<Commands><Command><Action>Run</Action></Command></Commands>";
            for (String layout : List.of(handlers, commands)) {
                Files.writeString(form, open + layout + close);
                warnings = validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.SOFTWARE)
                        .path("warnings");
                assertTrue(warnings.toString().contains("Module.bsl"), warnings.toString());
            }

            Files.writeString(form, open);
            warnings = validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.SOFTWARE)
                    .path("warnings");
            assertTrue(warnings.toString().contains("cannot read form layout"), warnings.toString());
        }
    }


    @Test
    void validatesStandaloneEdtDcsReportWithoutObjectModule() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        writeStandaloneEdtDcsReport(descriptor);

        JsonNode report = validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK);
        assertTrue(report.path("warnings").isEmpty(), report.toString());
        assertEquals(0, report.path("totalLines").asInt());
        assertTrue(convertExternalSource(descriptor.toString()).isEmpty());

        Files.write(tempDir.resolve("ObjectModule.bin"), new byte[] {1, 0, -1});
        assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                .path("warnings").isEmpty());
        assertTrue(convertExternalSource(descriptor.toString()).isEmpty());

        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        assertEquals(Map.of("ObjectModule.bsl", Map.of(2, true, 3, false)),
                convertExternalSource(descriptor.toString()));
    }

    @Test
    void rejectsMainTemplateWithNonSchemaTypeOrContent() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path edtSchema = writeStandaloneEdtDcsReport(edt);
        Path configurator = writeConfiguratorDcsReportDescriptor();
        Path configuratorSchema = tempDir.resolve("ExternalReport/Templates/Main/Ext/Template.xml");
        Files.createDirectories(configuratorSchema.getParent());
        String schema = Files.readString(edtSchema);
        String spreadsheet = "<document xmlns=\"http://v8.1c.ru/8.2/data/spreadsheet\"/>";
        Path templateMetadata = tempDir.resolve("ExternalReport/Templates/Main.xml");
        String metadata = "<MetaDataObject><Template><Properties><Name>Main</Name><TemplateType>%s</TemplateType>"
                + "</Properties></Template></MetaDataObject>";
        Files.writeString(templateMetadata, String.format(metadata, "SpreadsheetDocument"));
        Files.writeString(configuratorSchema, spreadsheet);
        assertMissingMainSchema(configurator);
        Files.writeString(configuratorSchema, schema);
        assertMissingMainSchema(configurator);

        Files.writeString(templateMetadata, String.format(metadata, "DataCompositionSchema"));
        Files.writeString(configuratorSchema, spreadsheet);
        assertMissingMainSchema(configurator);
        Files.writeString(configuratorSchema, schema);
        assertTrue(validateExternalSource(tempDir, configurator.toString(), true, CommandLine.ExitCode.OK)
                .path("warnings").isEmpty());

        for (String invalid : List.of(spreadsheet, "<DataCompositionSchema xmlns=\"urn:example:unrelated\"/>")) {
            Files.writeString(edtSchema, invalid);
            assertMissingMainSchema(edt);
        }
        Files.writeString(edtSchema, "<dcs:DataCompositionSchema"
                + " xmlns:dcs=\"http://v8.1c.ru/8.1/data-composition-system/schema\"/>");
        assertTrue(validateExternalSource(tempDir, edt.toString(), true, CommandLine.ExitCode.OK)
                .path("warnings").isEmpty());
    }

    @Test
    void validatesExternalReportsWithoutMainSchemaOrObjectModuleInBothFormats() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path edtSchema = writeStandaloneEdtDcsReport(edt);
        Files.writeString(edt, Files.readString(edt).replaceFirst("<mainDataCompositionSchema>[^<]+</mainDataCompositionSchema>", ""));
        Path configurator = writeConfiguratorDcsReportDescriptor();
        Files.writeString(configurator, Files.readString(configurator)
                .replaceFirst("<MainDataCompositionSchema>[^<]+</MainDataCompositionSchema>", ""));
        Path configuratorSchema = tempDir.resolve("ExternalReport/Templates/Main/Ext/Template.xml");
        Files.createDirectories(configuratorSchema.getParent());
        Files.copy(edtSchema, configuratorSchema);
        for (Path descriptor : List.of(edt, configurator)) {
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
        }

        Files.delete(edtSchema);
        Files.delete(configuratorSchema);
        for (Path descriptor : List.of(edt, configurator)) {
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
        }
    }


    private Path writeConfiguratorDcsReportDescriptor() throws IOException {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        Files.writeString(descriptor, "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\">"
                + "<ExternalReport uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\">"
                + "<Properties><Name>ExternalReport</Name><MainDataCompositionSchema>"
                + "ExternalReport.ExternalReport.Template.Main</MainDataCompositionSchema></Properties>"
                + "<ChildObjects><Template>Main</Template></ChildObjects></ExternalReport></MetaDataObject>");
        return descriptor;
    }

    @Test
    void validatesTheDeclaredMainDataCompositionSchema() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        Path mainTemplate = writeStandaloneEdtDcsReport(descriptor);
        Path otherTemplate = mainTemplate.getParent().resolveSibling("Other/Template.dcs");
        Files.createDirectories(otherTemplate.getParent());
        Files.copy(mainTemplate, otherTemplate);
        Files.writeString(descriptor, Files.readString(descriptor).replace("</mdclass:ExternalReport>",
                "<templates uuid=\"a3f69c34-2242-4e7e-a47b-e85f2b3694cb\"><name>Other</name>"
                        + "<templateType>DataCompositionSchema</templateType></templates></mdclass:ExternalReport>"));

        assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                .path("warnings").isEmpty());
        Files.delete(mainTemplate);
        assertMissingMainSchema(descriptor);
        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        assertMissingMainSchema(descriptor);

        Files.copy(otherTemplate, mainTemplate);
        assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                .path("warnings").isEmpty());
    }

    private void assertMissingMainSchema(Path descriptor) throws IOException {
        JsonNode warnings = validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.SOFTWARE)
                .path("warnings");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).asText().contains("missing main data composition schema"), warnings.toString());
    }

    @Test
    void retainsEdtMissingModuleDiagnostics() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        Path template = writeStandaloneEdtDcsReport(descriptor);
        String metadata = Files.readString(descriptor);

        Files.delete(template);
        assertMissingMainSchema(descriptor);

        writeStandaloneEdtDcsReport(descriptor);
        Files.writeString(descriptor, metadata.replace("DataCompositionSchema</templateType>",
                "SpreadsheetDocument</templateType>"));
        assertMissingMainSchema(descriptor);

        Files.writeString(descriptor, metadata.replace("mdclass:ExternalReport", "mdclass:ExternalDataProcessor"));
        assertMissingEdtModule(descriptor, "ObjectModule.bsl");

        Files.writeString(descriptor, metadata.replace("</mdclass:ExternalReport>",
                "<forms uuid=\"7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88\"><name>MissingForm</name></forms>"
                        + "</mdclass:ExternalReport>"));
        assertMissingEdtModule(descriptor, "Forms/MissingForm/Module.bsl");
    }

    @Test
    void rejectsMalformedExternalObjectUuidsBeforePublishing() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        Path raw = Files.writeString(tempDir.resolve("raw.xml"), "<coverage version=\"1\">"
                + rawRecord("5b8f36b6-34dc-45b7-8b1b-c999e4300d22/a637f77f-3840-441d-a1c3-699c8c5cb7e0", "", 2)
                + "</coverage>");
        Path report = tempDir.resolve("exclusions.json");
        Path output = tempDir.resolve("coverage.xml");

        for (Path descriptor : List.of(edt, configurator)) {
            String metadata = Files.readString(descriptor);
            for (String invalid : List.of(" ", "not-a-uuid", "5b8f36b6-34dc-45b7-8b1b-c999e4300d2")) {
                Files.writeString(descriptor, metadata.replace("5b8f36b6-34dc-45b7-8b1b-c999e4300d22", invalid));

                StringWriter stderr = new StringWriter();
                assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                        "-s", descriptor.toString(), "--strict", "--exclusion-report", report.toString()),
                        descriptor + ": " + invalid + ": " + stderr);
                assertTrue(stderr.toString().contains("Invalid UUID"), stderr.toString());
                assertFalse(Files.exists(report));

                stderr = new StringWriter();
                assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.CONVERT_COMMAND,
                        "-s", descriptor.toString(), "-c", raw.toString(), "-o", output.toString()),
                        descriptor + ": " + invalid + ": " + stderr);
                assertTrue(stderr.toString().contains("Invalid UUID"), stderr.toString());
                assertFalse(Files.exists(output));
            }
        }
    }

    @Test
    void acceptsUppercaseExternalDescriptorUuids() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        Path form = tempDir.resolve("ExternalReport/Forms/Форма.xml");
        for (Path descriptor : List.of(edt, configurator, form)) {
            Files.writeString(descriptor, Files.readString(descriptor)
                    .replace("5b8f36b6-34dc-45b7-8b1b-c999e4300d22", "5B8F36B6-34DC-45B7-8B1B-C999E4300D22")
                    .replace("7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88", "7B4E9BE1-F638-4A2A-9CC1-41BDB5E73C88"));
        }
        for (Path descriptor : List.of(edt, configurator)) {
            assertTrue(validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.OK)
                    .path("warnings").isEmpty());
            Map<String, Map<Integer, Boolean>> coverage = convertExternalSource(descriptor.toString());
            assertEquals(2, coverage.size());
            coverage.values().forEach(lines -> assertEquals(Map.of(2, true, 3, false), lines));
        }
    }

    @Test
    void diagnosesAndSkipsMalformedExternalFormUuids() throws Exception {
        Path edt = tempDir.resolve("ExternalReport.mdo");
        Path configurator = tempDir.resolve("ExternalReport.xml");
        writeEdtExternalReportDescriptor(edt);
        writeExternalModule(tempDir.resolve("ObjectModule.bsl"));
        writeExternalModule(tempDir.resolve("Forms/Форма/Module.bsl"));
        writeConfiguratorExternalSource(configurator, "ExternalReport");
        Path form = tempDir.resolve("ExternalReport/Forms/Форма.xml");
        for (Path descriptor : List.of(edt, form)) {
            Files.writeString(descriptor, Files.readString(descriptor)
                    .replace("7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88", "not-a-uuid"));
        }

        assertExternalFormDiagnostic(tempDir, edt.toString(), "ExternalReport.mdo", "invalid UUID");
        assertExternalFormDiagnostic(tempDir, configurator.toString(), "ExternalReport/Forms/Форма.xml", "invalid UUID");
        assertEquals(Map.of("ObjectModule.bsl", Map.of(2, true, 3, false)), convertExternalSource(edt.toString()));
        assertEquals(Map.of("ExternalReport/Ext/ObjectModule.bsl", Map.of(2, true, 3, false)),
                convertExternalSource(configurator.toString()));
    }

    private void assertMissingEdtModule(Path descriptor, String modulePath) throws IOException {
        JsonNode report = validateExternalSource(tempDir, descriptor.toString(), true, CommandLine.ExitCode.SOFTWARE);
        JsonNode warnings = report.path("warnings");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).asText().contains(modulePath + ": cannot read module"), warnings.toString());
    }

    private static void assertValidateOutputRejected(Path descriptor, Path output) {
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-s", descriptor.toString(), "--exclusion-report", output.toString()), output + ": " + stderr);
        assertTrue(stderr.toString().contains("Exclusion report would overwrite input file")
                || stderr.toString().contains("Exclusion report is inside a source directory"), stderr.toString());
    }

    private static Path writeStandaloneEdtDcsReport(Path descriptor) throws IOException {
        Path template = descriptor.resolveSibling("Templates/Main/Template.dcs");
        Files.createDirectories(template.getParent());
        Files.writeString(template, "<DataCompositionSchema xmlns=\"http://v8.1c.ru/8.1/data-composition-system/schema\"/>");
        Files.writeString(descriptor, "<mdclass:ExternalReport xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
                + " uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\"><name>ExternalReport</name>"
                + "<mainDataCompositionSchema>ExternalReport.ExternalReport.Template.Main</mainDataCompositionSchema>"
                + "<templates uuid=\"a2d69c34-2242-4e7e-a47b-e85f2b3694cb\"><name>Main</name>"
                + "<templateType>DataCompositionSchema</templateType></templates></mdclass:ExternalReport>");
        return template;
    }

    @Test
    void permitsReportBesideStandaloneEdtDescriptor() throws Exception {
        Path descriptor = tempDir.resolve("standalone/Report.mdo");
        writeExternalModule(descriptor.resolveSibling("ObjectModule.bsl"));
        Files.writeString(descriptor, "<mdclass:ExternalReport xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
                + " uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\"><name>Report</name></mdclass:ExternalReport>");
        Path raw = Files.writeString(tempDir.resolve("raw.xml"), "<coverage version=\"1\"/>");
        for (String relativePath : List.of(".settings/coverage.xml", "standalone/Reports/coverage.xml")) {
            Path output = tempDir.resolve(relativePath);
            Files.createDirectories(output.getParent());
            StringWriter stderr = new StringWriter();

            assertEquals(CommandLine.ExitCode.OK, execute(stderr, PipeMessages.CONVERT_COMMAND,
                    "-P", tempDir.toString(), "-s", descriptor.toString(), "-c", raw.toString(),
                    "-o", output.toString()), stderr.toString());
            assertTrue(Files.isRegularFile(output));
        }
    }

    @Test
    void followsConfiguratorExternalReportDescriptorLink() throws Exception {
        Path sources = Files.createDirectories(tempDir.resolve("project/sources"));
        Path descriptor = sources.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");

        assertDescriptorLinkCoverage(descriptor, "Shortcut.xml", Set.of("sources/ExternalReport/Ext/ObjectModule.bsl",
                "sources/ExternalReport/Forms/Форма/Ext/Form/Module.bsl"));
    }

    @Test
    void followsEdtExternalReportDescriptorLink() throws Exception {
        Path sources = Files.createDirectories(tempDir.resolve("project/sources"));
        Path descriptor = sources.resolve("ExternalReport.mdo");
        writeEdtExternalReportDescriptor(descriptor);
        writeExternalModule(sources.resolve("ObjectModule.bsl"));
        writeExternalModule(sources.resolve("Forms/Форма/Module.bsl"));

        assertDescriptorLinkCoverage(descriptor, "Shortcut.mdo",
                Set.of("sources/ObjectModule.bsl", "sources/Forms/Форма/Module.bsl"));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolvesDescriptorParentComponentsInFilesystemOrder() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path outsideDeep = Files.createDirectory(outside.resolve("deep"));
        Path inside = Files.createDirectory(project.resolve("inside"));
        Path insideDeep = Files.createDirectory(inside.resolve("deep"));
        try {
            Files.createSymbolicLink(project.resolve("out-link"), project.relativize(outsideDeep));
            Files.createSymbolicLink(project.resolve("in-link"), project.relativize(insideDeep));
            Files.createSymbolicLink(tempDir.resolve("project-alias"), project);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a symbolic link: " + error.getMessage());
        }

        for (Path directory : List.of(outside, inside, project)) {
            Files.writeString(directory.resolve("Report.xml"), "<MetaDataObject><ExternalReport"
                    + " uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\"/></MetaDataObject>");
            Files.writeString(directory.resolve("Report.mdo"), "<m:ExternalReport"
                    + " xmlns:m=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
                    + " uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\"/>");
            writeExternalModule(directory.resolve("Report/Ext/ObjectModule.bsl"));
            writeExternalModule(directory.resolve("ObjectModule.bsl"));
        }

        for (String descriptor : List.of("Report.xml", "Report.mdo")) {
            String module = descriptor.endsWith(".xml") ? "Report/Ext/ObjectModule.bsl" : "ObjectModule.bsl";
            Map<Integer, Boolean> lines = Map.of(2, true, 3, false);
            Path outsideModule = outside.resolve(module);
            for (Path projectPath : List.of(project, tempDir.resolve("project-alias"))) {
                assertEquals(Map.of(outsideModule.toUri().getPath(), lines),
                        convertExternalSource("out-link/../" + descriptor, "-P", projectPath.toString()));
                assertEquals(Map.of("inside/" + module, lines),
                        convertExternalSource("in-link/../" + descriptor, "-P", projectPath.toString()));
                assertEquals(Map.of(module, lines),
                        convertExternalSource("inside/../" + descriptor, "-P", projectPath.toString()));
            }

            byte[] original = Files.readAllBytes(outsideModule);
            assertValidateOutputRejected(project.resolve("out-link/../" + descriptor), outsideModule);
            assertArrayEquals(original, Files.readAllBytes(outsideModule));

            Path report = tempDir.resolve("must-not-be-created.json");
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, execute(stderr, PipeMessages.VALIDATE_COMMAND,
                    "-P", project.toString(), "-s", "missing/../" + descriptor, "--exclusion-report", report.toString()),
                    stderr.toString());
            assertTrue(stderr.toString().contains("Sources path does not exist"), stderr.toString());
            assertFalse(Files.exists(report));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void resolvesProjectParentComponentsForExternalReportPaths() throws Exception {
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path physical = Files.createDirectory(tempDir.resolve("physical"));
        Path deep = Files.createDirectory(physical.resolve("deep"));
        try {
            Files.createSymbolicLink(project.resolve("link"), project.relativize(deep));
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a symbolic link: " + error.getMessage());
        }

        Path lexicalProject = project.resolve("link/..");
        String module = "Report/Ext/ObjectModule.bsl";
        Map<Integer, Boolean> lines = Map.of(2, true, 3, false);
        for (Path directory : List.of(project, physical)) {
            Files.writeString(directory.resolve("Report.xml"), "<MetaDataObject><ExternalReport"
                    + " uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\"/></MetaDataObject>");
            writeExternalModule(directory.resolve(module));
        }

        assertEquals(Map.of(project.resolve(module).toUri().getPath(), lines),
                convertExternalSource(project.resolve("Report.xml").toString(), "-P", lexicalProject.toString()));
        assertEquals(Map.of(module, lines), convertExternalSource("Report.xml", "-P", lexicalProject.toString()));
        assertEquals(Map.of(module, lines), convertExternalSource(lexicalProject.resolve("Report.xml").toString()));
    }

    private void assertDescriptorLinkCoverage(Path descriptor, String linkName, Set<String> modules) throws Exception {
        Path project = descriptor.getParent().getParent();
        Path links = Files.createDirectory(project.resolve("links"));
        Path link = links.resolve(linkName);
        Path projectLink = tempDir.resolve("project-link");
        try {
            Files.createSymbolicLink(link, links.relativize(descriptor));
            Files.createSymbolicLink(projectLink, project);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a symbolic link: " + error.getMessage());
        }
        Path raw = tempDir.resolve("qualified.xml");
        String attributes = " sourceUrl=\"" + PROCESSOR_URL + "\"";
        Files.writeString(raw, "<coverage sourceIdentity=\"true\">"
                + rawRecord("5b8f36b6-34dc-45b7-8b1b-c999e4300d22/a637f77f-3840-441d-a1c3-699c8c5cb7e0", attributes, 2)
                + rawRecord("7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88/32e087ab-1491-49b6-aba7-43571b41ac2b", attributes, 2)
                + "</coverage>");
        Path direct = tempDir.resolve("direct.xml");
        Path throughLink = tempDir.resolve("through-link.xml");

        convertExternalDescriptor(project, project.relativize(descriptor), raw, direct);
        Map<String, Map<Integer, Boolean>> expected = MultipleSourcesTest.coverage(direct);
        assertEquals(modules, expected.keySet());
        expected.values().forEach(lines -> assertEquals(Map.of(2, true, 3, false), lines));

        for (Path projectPath : List.of(project, projectLink)) {
            convertExternalDescriptor(projectPath, project.relativize(link), raw, throughLink);
            assertEquals(expected, MultipleSourcesTest.coverage(throughLink));
        }

        for (Path source : List.of(descriptor, projectLink.resolve(project.relativize(descriptor)))) {
            convertExternalDescriptor(projectLink, source, raw, throughLink);
            assertEquals(expected, MultipleSourcesTest.coverage(throughLink));
        }
        convertExternalDescriptor(project, projectLink.resolve(project.relativize(descriptor)), raw, throughLink);
        assertEquals(expected, MultipleSourcesTest.coverage(throughLink));

        Path outside = tempDir.resolve("project-unrelated");
        TestUtils.copyDirectory(descriptor.getParent(), outside);
        convertExternalDescriptor(projectLink, outside.resolve(descriptor.getFileName()), raw, throughLink);
        Set<String> outsideModules = modules.stream().map(path -> outside.resolve(path.substring("sources/".length()))
                .toUri().getPath()).collect(Collectors.toSet());
        assertEquals(outsideModules, MultipleSourcesTest.coverage(throughLink).keySet());

        convertExternalDescriptor(null, descriptor, raw, direct);
        assertEquals(modules.stream().map(path -> path.substring("sources/".length())).collect(Collectors.toSet()),
                MultipleSourcesTest.coverage(direct).keySet());
        convertExternalDescriptor(null, link, raw, throughLink);
        assertEquals(MultipleSourcesTest.coverage(direct), MultipleSourcesTest.coverage(throughLink));

        Path neighbor = Files.createDirectory(links.resolve("Shortcut")).resolve("coverage.xml");
        convertExternalDescriptor(project, project.relativize(link), raw, neighbor);
        assertEquals(expected, MultipleSourcesTest.coverage(neighbor));

        Path report = tempDir.resolve("excluded.json");
        for (Path input : List.of(descriptor, link)) {
            assertDescriptorOutputRejected(link, report, input, "Coverage output");
            assertFalse(Files.exists(report));
        }
        assertTrue(Files.isSymbolicLink(link));
    }

    private static String rawRecord(String key, String attributes, int line) {
        return "<file path=\"/" + key + "\"" + attributes
                + "><lineToCover covered=\"true\" lineNumber=\"" + line + "\"/></file>";
    }

    private static void convertExternalDescriptor(Path project, Path descriptor, Path raw, Path output) {
        List<String> arguments = new ArrayList<>(List.of(PipeMessages.CONVERT_COMMAND,
                "-s", descriptor.toString(), "-x", PROCESSOR_URL, "-c", raw.toString(), "-o", output.toString()));
        if (project != null) {
            arguments.addAll(List.of("-P", project.toString()));
        }
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.OK, execute(stderr, arguments.toArray(new String[0])), stderr.toString());
    }

    private static void writeEdtExternalReportDescriptor(Path descriptor) throws IOException {
        Files.writeString(descriptor, "<mdclass:ExternalReport xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
                + " uuid=\"5b8f36b6-34dc-45b7-8b1b-c999e4300d22\"><name>ExternalReport</name>"
                + "<forms uuid=\"7b4e9be1-f638-4a2a-9cc1-41bdb5e73c88\"><name>Форма</name></forms>"
                + "</mdclass:ExternalReport>");
    }

    @Test
    void validatesProtectedConfiguratorExternalModulesInStrictMode() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.xml");
        writeConfiguratorExternalSource(descriptor, "ExternalReport");
        assertProtectedExternalModules(descriptor, tempDir.resolve("ExternalReport/Ext/ObjectModule.bsl"),
                tempDir.resolve("ExternalReport/Forms/Форма/Ext/Form/Module.bsl"));
    }

    @Test
    void validatesProtectedEdtExternalModulesInStrictMode() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        writeEdtExternalReportDescriptor(descriptor);
        assertProtectedExternalModules(descriptor, tempDir.resolve("ObjectModule.bsl"),
                tempDir.resolve("Forms/Форма/Module.bsl"));
    }

    private void assertProtectedExternalModules(Path descriptor, Path objectModule, Path formModule) throws Exception {
        Path formBinary = formModule.resolveSibling("Module.bin");
        for (Path module : List.of(objectModule, formModule)) {
            Files.createDirectories(module.getParent());
            Files.deleteIfExists(module);
        }
        byte[] protectedModule = {1, 0, -1, 127};
        Files.write(objectModule.resolveSibling("ObjectModule.bin"), protectedModule);
        Files.write(formBinary, protectedModule);
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();
        String[] validation = {PipeMessages.VALIDATE_COMMAND, "-s", descriptor.toString(), "--strict",
                "--exclusion-report", report.toString()};
        assertEquals(CommandLine.ExitCode.OK, execute(stderr, validation), stderr.toString());
        assertEquals(0, new ObjectMapper().readTree(report.toFile()).path("warnings").size());
        assertTrue(convertExternalSource(descriptor.toString()).isEmpty());

        writeExternalModule(objectModule);
        writeExternalModule(formModule);
        Map<String, Map<Integer, Boolean>> coverage = convertExternalSource(descriptor.toString());
        assertEquals(Map.of(2, true, 3, false), coverage.get(tempDir.relativize(objectModule).toString().replace('\\', '/')));
        assertEquals(Map.of(2, true, 3, false), coverage.get(tempDir.relativize(formModule).toString().replace('\\', '/')));

        Files.delete(formModule);
        Files.delete(formBinary);
        assertEquals(CommandLine.ExitCode.SOFTWARE, execute(new StringWriter(), validation));
        JsonNode warnings = new ObjectMapper().readTree(report.toFile()).path("warnings");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).asText().contains("Module.bsl: cannot read module"), warnings.toString());
    }

    @Test
    void protectsStandaloneEdtBinaryModulesThroughHardLinks() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        writeEdtExternalReportDescriptor(descriptor);
        Files.createDirectories(tempDir.resolve("Forms/Форма"));
        Path output = tempDir.resolve("coverage.xml");
        Path exclusions = tempDir.resolve("exclusions.json");
        byte[] original = {1, 0, -1, 127};

        for (String module : List.of("ObjectModule.bin", "Forms/Форма/Module.bin")) {
            Path input = Files.write(tempDir.resolve(module), original);
            try {
                Files.createLink(output, input);
            } catch (IOException | UnsupportedOperationException error) {
                assumeTrue(false, () -> "Cannot create a hard link: " + error.getMessage());
            }
            assertDescriptorOutputRejected(descriptor, exclusions, output, "Coverage output");
            assertArrayEquals(original, Files.readAllBytes(input), module);
            assertFalse(Files.exists(exclusions));
            Files.delete(output);
        }
    }

    @Test
    void reservesFutureStandaloneEdtModulePaths() throws Exception {
        Path descriptor = tempDir.resolve("ExternalReport.mdo");
        writeEdtExternalReportDescriptor(descriptor);
        Files.createDirectories(tempDir.resolve("Forms/Форма"));
        Path exclusions = tempDir.resolve("exclusions.json");

        for (String module : List.of("ObjectModule.bsl", "ObjectModule.bin", "Forms/Форма/Module.bsl",
                "Forms/Форма/Module.bin", "Forms/Форма/Form.form")) {
            Path output = tempDir.resolve(module);
            assertDescriptorOutputRejected(descriptor, exclusions, output, "Coverage output");
            assertFalse(Files.exists(output), module);
            assertFalse(Files.exists(exclusions));
        }
    }

    private static int execute(StringWriter stderr, String... arguments) {
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments);
    }
}
