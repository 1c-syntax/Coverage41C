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
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Сквозная проверка: исходники + конфиг исключений + сырые замеры → итоговый отчёт покрытия.
class ConvertWithExclusionsTest {

    private static final String MODULE = "configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";
    // Ключ модуля в сырых замерах: UUID общего модуля и UUID свойства «Модуль».
    private static final String MODULE_KEY = "/1be4af7e-334e-49fa-a9f9-d80c737ff954/d5963243-262e-4398-b4d7-fb16d06484f6";
    private static final String INTERNAL_XML = new File("src/test/resources/coverage/internal.xml").getAbsolutePath();
    // Строки, выполненные тестами: 2 — обычная, 4 — под Cover:off, 7 — под Cover:auto.
    private static final String[] MARKED_MODULE = {
            "Процедура А() Экспорт",
            "\tБ();",
            "\t// Cover:off",
            "\tВ();",
            "\t// Cover:on",
            "\t// Cover:auto",
            "\tГ();",
            "\tД();",
            "\t// Cover:on",
            "\tЕ();",
            "КонецПроцедуры"};
    private static final String[] PLAIN_MODULE = {
            "Процедура А() Экспорт",
            "\tБ();",
            "\tВ();",
            "КонецПроцедуры"};

    @TempDir
    Path tempDir;

    @Test
    void convertRejectsDocumentTypeDeclarations() throws Exception {
        assertEquals(CommandLine.ExitCode.SOFTWARE, convertSyntheticReport("<!DOCTYPE coverage><coverage/>"));
        assertFalse(Files.exists(tempDir.resolve("converted.xml")));
    }

    @Test
    void convertIgnoresProcessingInstructionNamedLineToCover() throws Exception {
        String raw = """
                <coverage><file path="%s">
                  <?lineToCover ignored?>
                  <lineToCover covered="true" lineNumber="3"/>
                </file></coverage>
                """.formatted(MODULE_KEY);
        assertEquals(CommandLine.ExitCode.OK, convertSyntheticReport(raw));
        assertEquals(true, genericCoverage(tempDir.resolve("converted.xml")).get("src/test/resources/" + MODULE).get(3));
    }

    @Test
    void convertRejectsUnrelatedXmlDocument() throws Exception {
        assertEquals(CommandLine.ExitCode.SOFTWARE, convertSyntheticReport("<other/>"));
        assertFalse(Files.exists(tempDir.resolve("converted.xml")));
    }

    @Test
    void convertRejectsCoberturaXmlDocument() throws Exception {
        String cobertura = """
                <coverage line-rate="0" branch-rate="0" version="0">
                  <sources/>
                  <packages/>
                </coverage>
                """;
        assertEquals(CommandLine.ExitCode.SOFTWARE, convertSyntheticReport(cobertura));
        assertFalse(Files.exists(tempDir.resolve("converted.xml")));
    }

    @Test
    void convertRejectsNonBlankTextAndCdataInRawStructure() throws Exception {
        String fileStart = "<file path=\"" + MODULE_KEY + "\">";
        String lineStart = "<lineToCover covered=\"true\" lineNumber=\"2\">";
        for (String structure : List.of("measurements unavailable", "<![CDATA[measurements unavailable]]>",
                fileStart + "measurements unavailable</file>",
                fileStart + lineStart + "measurements unavailable</lineToCover></file>")) {
            String input = "<coverage>" + structure + "</coverage>";
            assertEquals(CommandLine.ExitCode.SOFTWARE, convertSyntheticReport(input), input);
            assertFalse(Files.exists(tempDir.resolve("converted.xml")));
        }
    }

    @Test
    void convertRejectsNestedRawLineData() throws Exception {
        String raw = "<coverage><file path=\"" + MODULE_KEY
                + "\"><details><lineToCover covered=\"true\" lineNumber=\"2\"/></details></file></coverage>";
        assertEquals(CommandLine.ExitCode.SOFTWARE, convertSyntheticReport(raw));
        assertFalse(Files.exists(tempDir.resolve("converted.xml")));
    }

    @Test
    void convertRejectsUnrecognizedCoveredValue() throws Exception {
        String raw = "<coverage><file path=\"" + MODULE_KEY
                + "\"><lineToCover covered=\"maybe\" lineNumber=\"2\"/></file></coverage>";
        assertEquals(CommandLine.ExitCode.SOFTWARE, convertSyntheticReport(raw));
        assertFalse(Files.exists(tempDir.resolve("converted.xml")));
    }

    @Test
    void convertRejectsNonnumericCoveredLineNumber() throws Exception {
        assertInvalidRawLineNumber("not-a-number", true);
    }

    @Test
    void convertRejectsFractionalCoveredLineNumber() throws Exception {
        assertInvalidRawLineNumber("2.5", true);
    }

    @Test
    void convertRejectsMalformedUncoveredLineNumber() throws Exception {
        assertInvalidRawLineNumber("invalid", false);
    }

    private void assertInvalidRawLineNumber(String number, boolean covered) throws IOException {
        String raw = """
                <coverage><file path="%s">
                  <lineToCover covered="true" lineNumber="2"/>
                  <lineToCover covered="%s" lineNumber="%s"/>
                </file></coverage>
                """.formatted(MODULE_KEY, covered, number);

        assertEquals(CommandLine.ExitCode.SOFTWARE, convertSyntheticReport(raw));
        assertFalse(Files.exists(tempDir.resolve("converted.xml")));
    }

    @Test
    void convertAcceptsEmptyRawReportWithoutVersion() throws Exception {
        assertEquals(CommandLine.ExitCode.OK, convertSyntheticReport("<coverage><!-- no hits --></coverage>"));
        assertTrue(Files.size(tempDir.resolve("converted.xml")) > 0);
    }

    private int convertSyntheticReport(String report) throws IOException {
        Path input = Files.writeString(tempDir.resolve("input.xml"), report);
        return Coverage41C.getCommandLine().execute("convert", "-P", Path.of("").toAbsolutePath().toString(),
                "-s", "src/test/resources/configuration", "-c", input.toString(),
                "-o", tempDir.resolve("converted.xml").toString());
    }

    @Test
    void markersShapeGenericCoverage() throws Exception {
        Path output = convert(MARKED_MODULE, null, new int[]{2, 4, 7}, "GENERIC_COVERAGE");

        assertEquals(Map.of(2, true, 7, true, 10, false, 11, false), genericCoverage(output).get(MODULE));
    }

    @Test
    void markersShapeLcov() throws Exception {
        Path output = convert(MARKED_MODULE, null, new int[]{2, 4, 7}, "LCOV");

        // Порядок строк DA в LCOV не гарантирован.
        assertEquals(Stream.of("TN:", "SF:" + MODULE, "DA:2,1", "DA:7,1", "DA:10,0", "DA:11,0", "LH:2", "LF:4",
                "end_of_record").sorted().toList(), sortedLcovRecord(output, MODULE));
    }

    @Test
    void markersShapeCobertura() throws Exception {
        Path output = convert(MARKED_MODULE, null, new int[]{2, 4, 7}, "COBERTURA");

        assertEquals(Map.of(2, 1, 7, 1, 10, 0, 11, 0), coberturaHits(output, MODULE));
    }

    @Test
    void fileOffLeavesModuleOutOfEveryFormat() throws Exception {
        String[] module = {"// Cover:file-off", "Процедура А() Экспорт", "\tБ();", "КонецПроцедуры"};

        assertFalse(genericCoverage(convert(module, null, new int[]{3}, "GENERIC_COVERAGE")).containsKey(MODULE));
        assertFalse(Files.readAllLines(convert(module, null, new int[]{3}, "LCOV")).contains("SF:" + MODULE));
        assertNull(coberturaClass(convert(module, null, new int[]{3}, "COBERTURA"), MODULE));
    }

    @Test
    void moduleExcludedByConfigIsLeftOut() throws Exception {
        Path output = convert(PLAIN_MODULE, "{'exclude':{'objects':['CommonModule.ПростойОбщийМодуль']}}",
                new int[]{2}, "GENERIC_COVERAGE");

        Map<String, Map<Integer, Boolean>> coverage = genericCoverage(output);
        assertFalse(coverage.containsKey(MODULE));
        assertFalse(coverage.isEmpty());
    }

    @Test
    void moduleUnderAutoRuleKeepsOnlyExecutedLines() throws Exception {
        Path output = convert(PLAIN_MODULE, "{'auto':{'objects':['CommonModule.ПростойОбщийМодуль']}}",
                new int[]{2}, "GENERIC_COVERAGE");

        assertEquals(Map.of(2, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void lineRuleLeavesOutMatchingLines() throws Exception {
        Path output = convert(PLAIN_MODULE, "{'exclude':{'lines':['^\\\\s*В\\\\(\\\\);']}}",
                new int[]{2, 3}, "GENERIC_COVERAGE");

        assertEquals(Map.of(2, true, 4, false), genericCoverage(output).get(MODULE));
    }

    @Test
    void removesModulesBySupportLevel() throws Exception {
        String locked = "configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";
        String editable = "configuration/CommonModules/ГлобальныйОбщийМодуль/Ext/Module.bsl";
        String notSupported = "configuration/CommonModules/ОбщийМодульПовтИспСеанс/Ext/Module.bsl";
        String own = "configuration/CommonModules/ОбщийМодульПовтИспВызов/Ext/Module.bsl";
        Path project = TestUtils.copyConfiguration(tempDir);
        // Поставка: первый модуль не редактируется, второй редактируется с сохранением поддержки, третий снят с поддержки.
        Files.writeString(project.resolve("configuration/Ext/ParentConfigurations.bin"),
                "{6,0,1,46c7c1d0-b04d-4295-9b04-ae3207c18d29,0,11111111-1111-1111-1111-111111111111,"
                        + "\"1.0\",\"Вендор\",\"Поставка\",3,"
                        + "0,0,1be4af7e-334e-49fa-a9f9-d80c737ff954,1be4af7e-334e-49fa-a9f9-d80c737ff954,"
                        + "1,0,9e9c021c-bdbd-4804-a53a-9442ba9eb18c,9e9c021c-bdbd-4804-a53a-9442ba9eb18c,"
                        + "2,0,a09f73b1-0058-4f3b-a22b-799ef3c2a33d,a09f73b1-0058-4f3b-a22b-799ef3c2a33d,0}");

        assertEquals(List.of(true, true, true, true), modulesPresent(project, "NONE", locked, editable, notSupported, own));
        assertEquals(List.of(false, true, true, true),
                modulesPresent(project, "NOT_EDITABLE", locked, editable, notSupported, own));
        assertEquals(List.of(false, false, true, true),
                modulesPresent(project, "EDITABLE_SUPPORT_ENABLED", locked, editable, notSupported, own));
        assertEquals(List.of(false, false, false, true),
                modulesPresent(project, "NOT_SUPPORTED", locked, editable, notSupported, own));
    }

    private List<Boolean> modulesPresent(Path project, String removeSupport, String... modules) throws Exception {
        Path output = tempDir.resolve("coverage-" + removeSupport + ".xml");
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.OK, convert(project, output, stderr, "-r", removeSupport), stderr.toString());
        Map<String, Map<Integer, Boolean>> coverage = genericCoverage(output);
        List<Boolean> present = new ArrayList<>();
        for (String module : modules) {
            present.add(coverage.containsKey(module));
        }
        return present;
    }

    @Test
    void verboseLogShowsCoveredLineThatIsNotToCover() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), String.join("\n", PLAIN_MODULE) + "\n");
        Path rawCoverage = tempDir.resolve("internal.xml");
        Files.writeString(rawCoverage, "<coverage version=\"1\"><file path=\"" + MODULE_KEY + "\">"
                + "<lineToCover covered=\"true\" lineNumber=\"1\"/>"
                + "<lineToCover covered=\"false\" lineNumber=\"2\"/></file>"
                + "<file path=\"/00000000-0000-0000-0000-000000000000/00000000-0000-0000-0000-000000000000\"/>"
                + "</coverage>");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            exitCode = convert(project, rawCoverage, tempDir.resolve("coverage.xml"), new StringWriter(), "--verbose");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        String output = log.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Can't find line to cover 1 in module"), output);
        assertTrue(output.contains(">>> Процедура А() Экспорт"), output);
        assertTrue(output.contains("Can't find file key: 00000000-0000-0000-0000-000000000000/"), output);
        assertEquals(Map.of(2, false, 3, false, 4, false), genericCoverage(tempDir.resolve("coverage.xml")).get(MODULE));
    }

    @Test
    void missingIntermediateDirectoryCannotRedirectCoverageToRaw() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path raw = Files.copy(Path.of(INTERNAL_XML), tempDir.resolve("raw.xml"));
        byte[] original = Files.readAllBytes(raw);
        Path output = tempDir.resolve("missing/../raw.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, raw, output, stderr), stderr.toString());
        assertArrayEquals(original, Files.readAllBytes(raw));
        assertFalse(Files.exists(tempDir.resolve("missing")));
    }

    @Test
    void rejectsExclusionReportThatIsTheRawInput() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path rawCoverage = Files.copy(Path.of(INTERNAL_XML), tempDir.resolve("raw.xml"));

        assertReportCollision(project, rawCoverage, rawCoverage, "--exclusion-report", rawCoverage.toString());
    }

    @Test
    void rejectsConfiguredReportThatIsTheRawInput() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path rawCoverage = Files.copy(Path.of(INTERNAL_XML), project.resolve("raw.xml"));
        Path config = Files.writeString(tempDir.resolve("team.json"), "{\"exclusionReport\":\"raw.xml\"}");

        assertReportCollision(project, rawCoverage, rawCoverage, "--config", config.toString());
    }

    @Test
    void rejectsExclusionReportThatIsASymlinkToRawInput() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path rawCoverage = Files.copy(Path.of(INTERNAL_XML), tempDir.resolve("raw.xml"));
        Path alias = tempDir.resolve("report.json");
        createSymbolicLink(alias, rawCoverage);

        assertReportCollision(project, rawCoverage, rawCoverage, "--exclusion-report", alias.toString());
    }

    @Test
    void missingRawInputIsNotCreatedByTheExclusionReport() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path rawDirectory = Files.createDirectory(tempDir.resolve("raw"));
        Path rawCoverage = rawDirectory.resolve("coverage.xml");
        Path alias = tempDir.resolve("alias");
        createSymbolicLink(alias, rawDirectory);

        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, rawCoverage, tempDir.resolve("coverage.xml"), stderr,
                "--exclusion-report", alias.resolve(rawCoverage.getFileName()).toString()), stderr.toString());
        assertFalse(Files.exists(rawCoverage));
    }

    @Test
    void rejectsExclusionReportThatIsTheSingleConfigInput() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path config = Files.writeString(tempDir.resolve("team.json"), "{\"includeUsers\":[\"unit\"]}");

        assertReportCollision(project, Path.of(INTERNAL_XML), config,
                "--config", config.toString(), "--exclusion-report", config.toString());
    }

    @Test
    void rejectsExclusionReportThatIsAHardLinkToAConfigInput() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path configDir = Files.createDirectory(tempDir.resolve("conf"));
        Path config = Files.writeString(configDir.resolve("team.json"), "{\"includeUsers\":[\"unit\"]}");
        Path alias = tempDir.resolve("report.json");
        try {
            Files.createLink(alias, config);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a hard link: " + error.getMessage());
        }

        assertReportCollision(project, Path.of(INTERNAL_XML), config,
                "--config", configDir.toString(), "--exclusion-report", alias.toString());
    }

    @Test
    void rejectsCoverageOutputThroughDanglingLinkIntoConfigDirectory() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path rules = Files.createDirectory(tempDir.resolve("rules"));
        Path config = Files.writeString(rules.resolve("team.json"), "{\"includeUsers\":[\"unit\"]}");
        byte[] original = Files.readAllBytes(config);
        Path generated = rules.resolve("generated.json");
        Path output = tempDir.resolve("result.xml");
        createSymbolicLink(output, generated);
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, Path.of(INTERNAL_XML), output, stderr,
                "--config", rules.toString(), "--exclusion-report", report.toString()), stderr.toString());
        assertTrue(stderr.toString().contains("would be read as a config"), stderr.toString());
        assertArrayEquals(original, Files.readAllBytes(config));
        assertFalse(Files.exists(generated));
        assertFalse(Files.exists(report));
    }

    @Test
    void rejectsCoverageOutputThatWouldActivateDanglingConfigLink() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path rules = Files.createDirectory(tempDir.resolve("rules"));
        Path config = Files.writeString(rules.resolve("team.json"), "{\"includeUsers\":[\"unit\"]}");
        Path links = Files.createDirectory(tempDir.resolve("links"));
        Path output = tempDir.resolve("result.xml");
        Path intermediate = links.resolve("destination");
        createSymbolicLink(intermediate, links.relativize(output));
        Path generated = rules.resolve("generated.json");
        createSymbolicLink(generated, rules.relativize(intermediate));
        byte[] original = Files.readAllBytes(config);
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, Path.of(INTERNAL_XML), output, stderr,
                "--config", rules.toString(), "--exclusion-report", report.toString()), stderr.toString());
        assertTrue(stderr.toString().contains("would be read as a config"), stderr.toString());
        assertArrayEquals(original, Files.readAllBytes(config));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(report));
        assertTrue(Files.isSymbolicLink(generated));
    }

    @Test
    void emptySelectedRecordsDoNotHideForeignMeasurements() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), String.join("\n", PLAIN_MODULE) + "\n");
        Path report = Files.writeString(tempDir.resolve("report.json"), "{\"previous\":true}");
        Path coverage = Files.writeString(tempDir.resolve("coverage.xml"), "<previous/>");
        Path raw = tempDir.resolve("raw.xml");
        String selected = "source-v1/-/-/" + MODULE_KEY.replaceFirst("^/", "");
        String unknown = "source-v1/-/-/11111111-1111-4111-8111-111111111111/22222222-2222-4222-8222-222222222222";
        String foreign = "<file extension=\"A\" path=\"" + MODULE_KEY
                + "\"><lineToCover covered=\"true\" lineNumber=\"4\"/></file>";
        for (String placeholder : List.of(
                "<file path=\"" + selected + "\"/>",
                "<file path=\"" + unknown + "\"/>")) {
            Files.writeString(raw, "<coverage sourceIdentity=\"true\">" + placeholder + foreign + "</coverage>");
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, raw, coverage, stderr,
                    "--exclusion-report", report.toString()), stderr.toString());
            assertTrue(stderr.toString().contains("No RAW measurements"), stderr.toString());
            assertEquals("<previous/>", Files.readString(coverage));
            assertEquals("{\"previous\":true}", Files.readString(report));
        }
        for (boolean covered : List.of(false, true)) {
            Files.writeString(raw, "<coverage sourceIdentity=\"true\"><file path=\"" + selected
                    + "\"><lineToCover covered=\"" + covered + "\" lineNumber=\"2\"/></file>" + foreign + "</coverage>");
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, convert(project, raw, coverage, stderr,
                    "--exclusion-report", report.toString()), stderr.toString());
            assertEquals(Map.of(2, covered, 3, false, 4, false), genericCoverage(coverage).get(MODULE));
        }
    }

    @Test
    void convertsRawWithOnlyEmptyRecordsFromAnySource() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), String.join("\n", PLAIN_MODULE) + "\n");
        Path raw = tempDir.resolve("raw.xml");
        Path output = tempDir.resolve("coverage.xml");
        String main = "<file path=\"" + MODULE_KEY + "\"/>";
        String foreign = "<file path=\"" + MODULE_KEY + "\" extension=\"B\"/>";
        for (String files : List.of("", main, main + foreign, foreign, foreign + main)) {
            Files.writeString(raw, "<coverage sourceIdentity=\"true\">" + files + "</coverage>");
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, convert(project, raw, output, stderr), stderr.toString());
            assertEquals(Map.of(2, false, 3, false, 4, false), genericCoverage(output).get(MODULE));
        }
    }

    @Test
    void rejectsForeignRawXmlNamespacesWithoutReplacingReports() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), String.join("\n", PLAIN_MODULE) + "\n");
        Path report = Files.writeString(tempDir.resolve("report.json"), "{\"previous\":true}");
        Path coverage = Files.writeString(tempDir.resolve("coverage.xml"), "<previous/>");
        Path raw = tempDir.resolve("raw.xml");
        String document = "<coverage><file path=\"" + MODULE_KEY
                + "\"><lineToCover covered=\"true\" lineNumber=\"2\"/></file></coverage>";
        for (String tag : List.of("coverage", "file", "lineToCover")) {
            Files.writeString(raw, document.replaceFirst("<" + tag, "<" + tag + " xmlns=\"urn:example:foreign\""));
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, raw, coverage, stderr,
                    "--exclusion-report", report.toString()), stderr.toString());
            assertEquals("<previous/>", Files.readString(coverage));
            assertEquals("{\"previous\":true}", Files.readString(report));
        }
        Files.writeString(raw, document.replace("<coverage>", "<coverage xmlns:example=\"urn:example:unused\">"));
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.OK, convert(project, raw, coverage, stderr,
                "--exclusion-report", report.toString()), stderr.toString());
        assertEquals(Map.of(2, true, 3, false, 4, false), genericCoverage(coverage).get(MODULE));
    }


    @Test
    void foreignRawKeepsPreviousCoverageAndConfiguredExclusionReport() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path config = Files.writeString(tempDir.resolve("rules.json"), "{\"exclusionReport\":\"previous.json\"}");
        Path report = Files.writeString(project.resolve("previous.json"), "{\"previous\":true}");
        Path coverage = Files.writeString(tempDir.resolve("coverage.xml"), "<previous/>");
        Path raw = Files.writeString(tempDir.resolve("raw.xml"),
                "<coverage sourceIdentity=\"true\"><file extension=\"Other\" path=\"" + MODULE_KEY
                        + "\"><lineToCover covered=\"true\" lineNumber=\"2\"/></file></coverage>");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, raw, coverage, stderr,
                "--config", config.toString()), stderr.toString());
        assertTrue(stderr.toString().contains("No RAW measurements"), stderr.toString());
        assertEquals("{\"previous\":true}", Files.readString(report));
        assertEquals("<previous/>", Files.readString(coverage));
    }

    @Test
    void invalidLineAfterValidHitKeepsPreviousReports() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path report = Files.writeString(tempDir.resolve("report.json"), "{\"previous\":true}");
        Path coverage = Files.writeString(tempDir.resolve("coverage.xml"), "<previous/>");
        Path raw = Files.writeString(tempDir.resolve("raw.xml"), "<coverage><file path=\"" + MODULE_KEY
                + "\"><lineToCover covered=\"true\" lineNumber=\"2\"/>"
                + "<lineToCover covered=\"invalid\" lineNumber=\"4\"/></file></coverage>");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, raw, coverage, stderr,
                "--exclusion-report", report.toString()), stderr.toString());
        assertEquals("{\"previous\":true}", Files.readString(report));
        assertEquals("<previous/>", Files.readString(coverage));
    }

    @Test
    void failedCoverageWriteKeepsPreviousExclusionReport() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path report = Files.writeString(tempDir.resolve("report.json"), "{\"previous\":true}");
        Path directory = Files.createDirectory(tempDir.resolve("coverage.xml"));
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, Path.of(INTERNAL_XML), directory, stderr,
                "--exclusion-report", report.toString()), stderr.toString());
        assertEquals("{\"previous\":true}", Files.readString(report));
    }

    @Test
    void invalidExclusionDestinationKeepsPreviousCoverage() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path report = Files.createDirectory(tempDir.resolve("report.json"));
        Path coverage = Files.writeString(tempDir.resolve("coverage.xml"), "<previous/>");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, Path.of(INTERNAL_XML), coverage, stderr,
                "--exclusion-report", report.toString()), stderr.toString());
        assertEquals("<previous/>", Files.readString(coverage));
    }

    @Test
    void writesBothReportsInNewDirectory() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path directory = tempDir.resolve("reports");
        Path report = directory.resolve("exclusions.json");
        Path coverage = directory.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(project, Path.of(INTERNAL_XML), coverage, stderr,
                "--exclusion-report", report.toString()), stderr.toString());
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
        assertFalse(genericCoverage(coverage).isEmpty());
    }

    @Test
    void rejectsCoverageOutputLinkedToRawBeforeWritingExclusionReport() throws Exception {
        Path raw = Files.copy(Path.of(INTERNAL_XML), tempDir.resolve("raw.xml"));
        assertCoverageOutputDoesNotOverwriteInput(raw, raw);
    }

    @Test
    void rejectsCoverageOutputLinkedToRulesBeforeWritingExclusionReport() throws Exception {
        Path configDir = Files.createDirectory(tempDir.resolve("rules"));
        Path config = Files.writeString(configDir.resolve("team.json"), "{\"includeUsers\":[\"unit\"]}");
        assertCoverageOutputDoesNotOverwriteInput(Path.of(INTERNAL_XML), config, "--config", configDir.toString());
    }

    private void assertCoverageOutputDoesNotOverwriteInput(Path raw, Path input, String... options) throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path output = tempDir.resolve("coverage.xml");
        try {
            Files.createLink(output, input);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a hard link: " + error.getMessage());
        }
        byte[] original = Files.readAllBytes(input);
        Path report = tempDir.resolve("report.json");
        List<String> arguments = new ArrayList<>(List.of(options));
        arguments.addAll(List.of("--exclusion-report", report.toString()));
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, raw, output, stderr,
                arguments.toArray(new String[0])), stderr.toString());
        assertTrue(stderr.toString().contains("Coverage output would overwrite input file"), stderr.toString());
        assertArrayEquals(original, Files.readAllBytes(input));
        assertFalse(Files.exists(report));
    }

    @Test
    void rejectsCoverageOutputThatMatchesConfiguredExclusionReport() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path config = Files.writeString(tempDir.resolve("team.json"), "{\"exclusionReport\":\"result.xml\"}");
        Path output = project.resolve("result.xml");

        assertCoverageOutputCollision(project, output, "--config", config.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void checksCaseOnlyReportPathsOnTheDestinationFileSystem() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path probe = Files.createFile(tempDir.resolve("CaseProbe"));
        boolean ignoresCase = Files.exists(tempDir.resolve("caseprobe"));
        Files.delete(probe);
        Path report = tempDir.resolve("Result.xml");
        Path output = tempDir.resolve("result.xml");
        String[] options = {"--exclusion-report", report.toString()};

        if (ignoresCase) {
            assertCoverageOutputCollision(project, output, options);
            assertFalse(Files.exists(report));
            assertFalse(Files.exists(output));
        } else {
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, convert(project, Path.of(INTERNAL_XML), output, stderr, options),
                    stderr.toString());
            assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
            assertFalse(genericCoverage(output).isEmpty());
        }
    }

    @Test
    void checksUnicodeEquivalentReportPathsOnTheDestinationFileSystem() throws Exception {
        String composed = "Отчёт.xml";
        assertOutputNameAliasing(composed, Normalizer.normalize(composed, Normalizer.Form.NFD));
    }

    @Test
    void comparesUnicodeCaseExpansionUsingTheDestinationFileSystem() throws Exception {
        assertOutputNameAliasing("Straße.xml", "STRASSE.xml");
    }

    private void assertOutputNameAliasing(String reportName, String outputName) throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path report = tempDir.resolve(reportName);
        Path output = tempDir.resolve(outputName);
        Files.createFile(report);
        boolean aliases = Files.exists(output);
        Files.delete(report);
        String[] options = {"--exclusion-report", report.toString()};

        if (aliases) {
            assertCoverageOutputCollision(project, output, options);
            assertFalse(Files.exists(report));
            assertFalse(Files.exists(output));
        } else {
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, convert(project, Path.of(INTERNAL_XML), output, stderr, options),
                    stderr.toString());
            assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
            assertTrue(Files.readString(output).contains("<coverage "));
        }
    }

    @Test
    void overwritesDistinctExistingCaseOnlyFilesWithoutCreatingFilesInTheirDirectory() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path directory = Files.createDirectory(tempDir.resolve("reports"));
        Path report = Files.writeString(directory.resolve("Result.xml"), "existing JSON");
        Path output = directory.resolve("result.xml");
        assumeTrue(!Files.exists(output), "Requires a case-sensitive directory");
        Files.writeString(output, "existing XML");
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"), "Requires POSIX permissions");

        var permissions = Files.getPosixFilePermissions(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
        try {
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.OK, convert(project, Path.of(INTERNAL_XML), output, stderr,
                    "--exclusion-report", report.toString()), stderr.toString());
            assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
            assertFalse(genericCoverage(output).isEmpty());
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
    }

    @Test
    void rejectsCoverageOutputThroughSymlinkedDirectoryBeforeCreatingFiles() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path dir = Files.createDirectory(tempDir.resolve("reports"));
        Path aliasDir = tempDir.resolve("alias");
        createSymbolicLink(aliasDir, dir);
        Path report = dir.resolve("new/result.xml");

        assertCoverageOutputCollision(project, aliasDir.resolve("new/result.xml"),
                "--exclusion-report", report.toString());
        assertFalse(Files.exists(report.getParent()));
    }

    @Test
    void rejectsCoverageOutputThatIsATargetOfAnExclusionReportSymlink() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path output = tempDir.resolve("result.xml");
        Path alias = tempDir.resolve("report.json");
        createSymbolicLink(alias, output.getFileName());

        assertCoverageOutputCollision(project, output, "--exclusion-report", alias.toString());
        assertTrue(Files.isSymbolicLink(alias));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsCoverageOutputThatIsAHardLinkToAnExclusionReport() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path report = Files.writeString(tempDir.resolve("report.json"), "original");
        Path output = tempDir.resolve("result.xml");
        try {
            Files.createLink(output, report);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a hard link: " + error.getMessage());
        }

        assertCoverageOutputCollision(project, output, "--exclusion-report", report.toString());
        assertEquals("original", Files.readString(report));
        assertEquals("original", Files.readString(output));
    }

    private void assertCoverageOutputCollision(Path project, Path output, String... options) throws Exception {
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE,
                convert(project, Path.of(INTERNAL_XML), output, stderr, options), stderr.toString());
        assertTrue(stderr.toString().contains("Exclusion report and coverage output must use different files"),
                stderr.toString());
    }

    @Test
    void usesCliReportBesideConfigEvenWhenConfiguredPathAliasesRawInput() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path rawCoverage = Files.copy(Path.of(INTERNAL_XML), project.resolve("raw.xml"));
        byte[] rawContents = Files.readAllBytes(rawCoverage);
        Path config = Files.writeString(tempDir.resolve("team.json"), "{\"exclusionReport\":\"raw.xml\"}");
        Path report = tempDir.resolve("report.json");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(project, rawCoverage, tempDir.resolve("coverage.xml"), stderr,
                "--config", config.toString(), "--exclusion-report", report.toString()), stderr.toString());
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("totalLines"));
        assertArrayEquals(rawContents, Files.readAllBytes(rawCoverage));
    }

    private static void createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a symbolic link: " + error.getMessage());
        }
    }

    private void assertReportCollision(Path project, Path rawCoverage, Path input, String... options) throws Exception {
        byte[] original = Files.readAllBytes(input);
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(project, rawCoverage, output, stderr, options), stderr.toString());
        assertTrue(stderr.toString().contains("Exclusion report would overwrite input file"), stderr.toString());
        assertArrayEquals(original, Files.readAllBytes(input));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsConfigurationWithoutSources() throws Exception {
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"exclude\":{\"lines\":[\"А\"]}}");
        StringWriter stderr = new StringWriter();
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));

        int exitCode = commandLine.execute(PipeMessages.CONVERT_COMMAND,
                "-c", INTERNAL_XML,
                "-o", tempDir.resolve("coverage.xml").toString(),
                "--config", config.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Coverage configuration requires source files"), stderr.toString());
    }

    // includeUsers делает конфиг допустимым без исходников только для сбора; convert без исходников — по-прежнему ошибка.
    @Test
    void rejectsConfigurationWithIncludeUsersWithoutSources() throws Exception {
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"includeUsers\":[\"unit\"],\"exclude\":{\"lines\":[\"А\"]}}");
        StringWriter stderr = new StringWriter();
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));

        int exitCode = commandLine.execute(PipeMessages.CONVERT_COMMAND,
                "-c", INTERNAL_XML,
                "-o", tempDir.resolve("coverage.xml").toString(),
                "--config", config.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Coverage configuration requires source files"), stderr.toString());
    }

    @Test
    void moduleWithoutSourceTextIsLeftOutOfCoverage() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.write(project.resolve(MODULE), new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0x00, 0x02});
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(project, output, stderr), stderr.toString());
        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    @Test
    void moduleThatCannotBeReadIsLeftOutOfCoverage() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.write(project.resolve(MODULE),
                "Процедура А()\n\tБ();\nКонецПроцедуры\n".getBytes(Charset.forName("windows-1251")));
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(project, output, stderr), stderr.toString());
        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    // Исполняемые строки: 2, 4, 6; блок доработки — строка 4.
    private static final String[] CHANGED_MODULE = {
            "Процедура А() Экспорт",
            "\tТиповой();",
            "\t// ++ уч",
            "\tНаш();",
            "\t// -- уч",
            "КонецПроцедуры"};

    @Test
    void changeBlockOfModuleOutOfScopeIsCovered() throws Exception {
        Path output = convert(CHANGED_MODULE, "{'includeSubsystems':['ПодчиненнаяПодсистема'],'changeMarkers':{}}",
                new int[]{2, 4}, "GENERIC_COVERAGE");

        assertEquals(Map.of(4, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void changeBlockClosedByInlineEndMarkerCoversItsLine() throws Exception {
        Path output = convert(new String[]{"Процедура А()", "\tТиповой();", "\t// ++ уч", "\tНаш();",
                        "КонецПроцедуры // -- уч"},
                "{'includeSubsystems':['ПодчиненнаяПодсистема'],'changeMarkers':{}}",
                new int[]{2, 4, 5}, "GENERIC_COVERAGE");

        assertEquals(Map.of(4, true, 5, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void moduleOutOfScopeWithoutChangeMarkersStaysOut() throws Exception {
        Path output = convert(CHANGED_MODULE, "{'includeSubsystems':['ПодчиненнаяПодсистема']}",
                new int[]{2, 4}, "GENERIC_COVERAGE");

        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    @Test
    void changeBlockOfModuleExcludedByRuleIsCovered() throws Exception {
        Path output = convert(CHANGED_MODULE,
                "{'exclude':{'objects':['CommonModule.ПростойОбщийМодуль']},'changeMarkers':{}}",
                new int[]{2, 4}, "GENERIC_COVERAGE");

        assertEquals(Map.of(4, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void changeBlockOfModuleUnderRemoveSupportIsCovered() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve("configuration/Ext/ParentConfigurations.bin"),
                "{6,0,1,46c7c1d0-b04d-4295-9b04-ae3207c18d29,0,11111111-1111-1111-1111-111111111111,"
                        + "\"1.0\",\"Вендор\",\"Поставка\",3,"
                        + "0,0,1be4af7e-334e-49fa-a9f9-d80c737ff954,1be4af7e-334e-49fa-a9f9-d80c737ff954,"
                        + "1,0,9e9c021c-bdbd-4804-a53a-9442ba9eb18c,9e9c021c-bdbd-4804-a53a-9442ba9eb18c,"
                        + "2,0,a09f73b1-0058-4f3b-a22b-799ef3c2a33d,a09f73b1-0058-4f3b-a22b-799ef3c2a33d,0}");

        Path output = convert(CHANGED_MODULE, "{'changeMarkers':{}}", new int[]{2, 4}, "GENERIC_COVERAGE",
                "-r", "NOT_EDITABLE");

        assertEquals(Map.of(4, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void exclusionReportAddsUpWithChangeBlocks() throws Exception {
        Path report = tempDir.resolve("exclusions.json");

        Path output = convert(CHANGED_MODULE, "{'includeSubsystems':['ПодчиненнаяПодсистема'],"
                        + "'exclude':{'objects':['Catalog.Справочник1']},'changeMarkers':{}}", new int[]{2, 4},
                "GENERIC_COVERAGE", "--exclusion-report", report.toString());

        JsonNode root = new ObjectMapper().readTree(report.toFile());
        int linesInCoverage = genericCoverage(output).values().stream().mapToInt(Map::size).sum();
        assertEquals(root.get("totalLines").asInt() - root.get("excludedLines").asInt()
                - root.get("autoLines").asInt(), linesInCoverage);
        assertEquals(1, root.get("includedLines").asInt());
        JsonNode blocks = root.get("changeBlocks").get(0);
        assertEquals("team.json → changeMarkers[0]", blocks.get("source").asText());
        assertEquals(MODULE, blocks.get("modules").get(0).asText());
    }

    @Test
    void moduleWithoutSourceTextOutOfScopeIsLeftOutWithChangeMarkers() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.write(project.resolve(MODULE), new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0x00, 0x02});
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"includeSubsystems\":[\"ПодчиненнаяПодсистема\"],\"changeMarkers\":{}}");
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(project, output, stderr, "--config", config.toString()),
                stderr.toString());
        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    // Исполняемые строки: 3 и 15 вне методов с префиксом; 7, 8, 9 и 12, 13 — в тестОбработать и тестПолучить.
    private static final String[] ROUTINE_MODULE = {
            "Перем Счётчик;",
            "Процедура Типовая() Экспорт",
            "\tБ();",
            "КонецПроцедуры",
            "",
            "Процедура тестОбработать(Документ) Экспорт",
            "\tВ();",
            "\tГ();",
            "КонецПроцедуры",
            "",
            "Функция тестПолучить()",
            "\tВозврат 1;",
            "КонецФункции",
            "",
            "Д();"};
    private static final String ROUTINES = "{'includePrefix':['тест'],'prefixScope':{'routines':true}}";

    @Test
    void prefixedMethodsOfModuleOutOfScopeAreCovered() throws Exception {
        Path output = convert(ROUTINE_MODULE, ROUTINES, new int[]{3, 7, 12}, "GENERIC_COVERAGE");

        Map<String, Map<Integer, Boolean>> coverage = genericCoverage(output);
        assertEquals(Map.of(7, true, 8, false, 9, false, 12, true, 13, false), coverage.get(MODULE));
        assertEquals(Set.of(MODULE), coverage.keySet());
    }

    @Test
    void prefixedMethodsShapeLcovAndCobertura() throws Exception {
        Path lcov = convert(ROUTINE_MODULE, ROUTINES, new int[]{3, 7, 12}, "LCOV");
        assertEquals(Stream.of("TN:", "SF:" + MODULE, "DA:7,1", "DA:8,0", "DA:9,0", "DA:12,1", "DA:13,0", "LH:2", "LF:5",
                "end_of_record").sorted().toList(), sortedLcovRecord(lcov, MODULE));

        Path cobertura = convert(ROUTINE_MODULE, ROUTINES, new int[]{3, 7, 12}, "COBERTURA");
        assertEquals(Map.of(7, 1, 8, 0, 9, 0, 12, 1, 13, 0), coberturaHits(cobertura, MODULE));
    }

    @Test
    void subsystemContentsAreIncludedOnlyWithFlag() throws Exception {
        String catalog = "configuration/Catalogs/Справочник1/Ext/ObjectModule.bsl";
        String accounts = "configuration/ChartsOfAccounts/ПланСчетов1/Ext/ObjectModule.bsl";

        Map<String, Map<Integer, Boolean>> full = genericCoverage(convert(PLAIN_MODULE, "{'includePrefix':['Перв']}",
                new int[]{2}, "GENERIC_COVERAGE"));
        assertTrue(full.containsKey(catalog), full.keySet().toString());
        assertTrue(full.containsKey(accounts), full.keySet().toString());
        assertFalse(full.containsKey(MODULE));

        Map<String, Map<Integer, Boolean>> none = genericCoverage(convert(PLAIN_MODULE,
                "{'includePrefix':['Перв'],'prefixScope':{'includeSubsystemContents':false}}", new int[]{2},
                "GENERIC_COVERAGE"));
        assertTrue(none.isEmpty(), none.keySet().toString());
    }

    @Test
    void prefixedMethodsUnderRemoveSupportStayOut() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve("configuration/Ext/ParentConfigurations.bin"),
                "{6,0,1,46c7c1d0-b04d-4295-9b04-ae3207c18d29,0,11111111-1111-1111-1111-111111111111,"
                        + "\"1.0\",\"Вендор\",\"Поставка\",1,"
                        + "0,0,1be4af7e-334e-49fa-a9f9-d80c737ff954,1be4af7e-334e-49fa-a9f9-d80c737ff954,0}");

        Path output = convert(ROUTINE_MODULE, ROUTINES, new int[]{7}, "GENERIC_COVERAGE", "-r", "NOT_EDITABLE");

        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    private Path convert(String[] moduleLines, String configJson, int[] executedLines, String format,
                         String... extraOptions) throws Exception {
        Path project = tempDir.resolve("project");
        if (!Files.exists(project)) {
            TestUtils.copyConfiguration(tempDir);
        }
        Files.writeString(project.resolve(MODULE), String.join("\n", moduleLines) + "\n");
        Path rawCoverage = tempDir.resolve("internal.xml");
        StringBuilder raw = new StringBuilder("<coverage version=\"1\"><file path=\"" + MODULE_KEY + "\">");
        for (int line : executedLines) {
            raw.append("<lineToCover covered=\"true\" lineNumber=\"").append(line).append("\"/>");
        }
        Files.writeString(rawCoverage, raw.append("</file></coverage>").toString());
        List<String> options = new ArrayList<>(List.of("-f", format));
        if (configJson != null) {
            Path config = tempDir.resolve("team.json");
            Files.writeString(config, configJson.replace('\'', '"'));
            options.addAll(List.of("--config", config.toString()));
        }
        options.addAll(Arrays.asList(extraOptions));
        Path output = tempDir.resolve("coverage.out");
        StringWriter stderr = new StringWriter();

        int exitCode = convert(project, rawCoverage, output, stderr, options.toArray(new String[0]));

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        return output;
    }

    private static int convert(Path project, Path output, StringWriter stderr, String... options) {
        return convert(project, Path.of(INTERNAL_XML), output, stderr, options);
    }

    private static int convert(Path project, Path rawCoverage, Path output, StringWriter stderr, String... options) {
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.CONVERT_COMMAND,
                "-P", project.toAbsolutePath().toString(),
                "-s", "configuration",
                "-c", rawCoverage.toString(),
                "-o", output.toString()));
        arguments.addAll(Arrays.asList(options));
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments.toArray(new String[0]));
    }

    private static List<String> sortedLcovRecord(Path output, String path) throws Exception {
        List<String> lines = Files.readAllLines(output);
        int file = lines.indexOf("SF:" + path);
        assertTrue(file > 0, "Missing LCOV record for " + path);
        int end = file;
        while (!lines.get(end).equals("end_of_record")) {
            end++;
        }
        return lines.subList(file - 1, end + 1).stream().sorted().toList();
    }

    private static Element coberturaClass(Path output, String path) throws Exception {
        NodeList classes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
                .getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            Element coberturaClass = (Element) classes.item(i);
            if (coberturaClass.getAttribute("filename").equals(path)) {
                return coberturaClass;
            }
        }
        return null;
    }

    // номер строки → число попаданий
    private static Map<Integer, Integer> coberturaHits(Path output, String path) throws Exception {
        Element coberturaClass = coberturaClass(output, path);
        assertNotNull(coberturaClass, "Missing Cobertura class for " + path);
        Map<Integer, Integer> hits = new TreeMap<>();
        NodeList lines = coberturaClass.getElementsByTagName("line");
        for (int i = 0; i < lines.getLength(); i++) {
            Element line = (Element) lines.item(i);
            hits.put(Integer.parseInt(line.getAttribute("number")), Integer.parseInt(line.getAttribute("hits")));
        }
        return hits;
    }

    // path → (номер строки → покрыта ли)
    private static Map<String, Map<Integer, Boolean>> genericCoverage(Path output) throws Exception {
        Map<String, Map<Integer, Boolean>> result = new TreeMap<>();
        NodeList files = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
                .getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            Map<Integer, Boolean> lines = new TreeMap<>();
            NodeList lineNodes = file.getElementsByTagName("lineToCover");
            for (int j = 0; j < lineNodes.getLength(); j++) {
                Element line = (Element) lineNodes.item(j);
                lines.put(Integer.parseInt(line.getAttribute("lineNumber")),
                        Boolean.parseBoolean(line.getAttribute("covered")));
            }
            result.put(file.getAttribute("path"), lines);
        }
        return result;
    }
}
