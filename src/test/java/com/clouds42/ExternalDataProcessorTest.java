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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertEquals(Set.of("ВнешняяОбработка/Ext/ObjectModule.bsl:2 → Cover:off"),
                exclusionSources(project, "ВнешняяОбработка.xml"));
    }

    @Test
    void skipsEdtFormWithoutName() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("edt/pc"), project);
        Path processor = project.resolve("src/ExternalDataProcessors/ВнешняяОбработка");
        Files.writeString(processor.resolve("ObjectModule.bsl"), OBJECT_MODULE);
        Path descriptor = processor.resolve("ВнешняяОбработка.mdo");
        Files.writeString(descriptor, Files.readString(descriptor).replace("<name>Форма</name>", ""));

        assertEquals(Set.of("src/ExternalDataProcessors/ВнешняяОбработка/ObjectModule.bsl:2 → Cover:off"),
                exclusionSources(project, "src/ExternalDataProcessors/ВнешняяОбработка/ВнешняяОбработка.mdo"));
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

    private Set<String> exclusionSources(Path project, String sources) throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-P", project.toAbsolutePath().toString(),
                "-s", sources,
                "--strict",
                "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        Set<String> result = new TreeSet<>();
        for (JsonNode entry : new ObjectMapper().readTree(report.toFile()).get("entries")) {
            result.add(entry.get("source").asText());
        }
        return result;
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

    private static int execute(StringWriter stderr, String... arguments) {
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments);
    }
}
