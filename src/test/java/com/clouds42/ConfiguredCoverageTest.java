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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.clouds42.CommandLineOptions.MetadataOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredCoverageTest {
    static final String MODULE = "CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";
    static final String OBJECT_ID = "1be4af7e-334e-49fa-a9f9-d80c737ff954";
    static final String PROPERTY_ID = "d5963243-262e-4398-b4d7-fb16d06484f6";
    static final String SOURCE = """
            Процедура Пример() Экспорт
                Первый();
                // ++
                Второй();
                // --
            КонецПроцедуры
            """;

    @TempDir Path directory;

    @Test
    void inlineMarkersDoNotChangeWhenConversionRequestsAReport() throws Exception {
        assertConversionMarkers("""
                Процедура Пример()
                    Первый(); // Cover:off
                    Второй(); // Cover:auto
                    Третий();
                КонецПроцедуры
                """, Map.of(4, true, 5, false));
    }

    @Test
    void methodMarkerStopsAtTheMethodEndWithoutExtraOptions() throws Exception {
        assertConversionMarkers("""
                Процедура Исключённая() // Cover:off
                    Первый();
                КонецПроцедуры
                Процедура Включённая()
                    Второй();
                КонецПроцедуры
                """, Map.of(5, false, 6, false));
    }

    @Test
    void nestedStandaloneMarkersCloseOneRangeAtATimeWithoutExtraOptions() throws Exception {
        assertConversionMarkers("""
                Процедура Пример()
                    // Cover:off
                    Первый();
                    // Cover:auto
                    Второй();
                    // Cover:on
                    Третий();
                    // Cover:on
                    Четвёртый();
                КонецПроцедуры
                """, Map.of(9, false, 10, false));
    }

    @Test
    void fileOffExcludesTheModuleWithoutExtraOptions() throws Exception {
        Path sources = project().resolve("configuration");
        Files.writeString(sources.resolve(MODULE), "// Cover:file-off\n" + SOURCE);
        MetadataOptions options = new MetadataOptions();
        options.setProjectDirName(sources.getParent().toString());
        options.setSrcDirName("configuration");
        options.setRemoveSupport(com.github._1c_syntax.bsl.support.SupportVariant.NONE);
        Map<URI, Map<BigDecimal, Integer>> data = new HashMap<>();
        var modules = Utils.readMetadata(options, "", data);
        assertEquals(sources.resolve(MODULE).toUri(), modules.get(OBJECT_ID + "/" + PROPERTY_ID));
        assertEquals(Map.of(), data.get(sources.resolve(MODULE).toUri()));
    }

    private void assertConversionMarkers(String source, Map<Integer, Boolean> expected) throws Exception {
        Path sources = project().resolve("configuration");
        Files.writeString(sources.resolve(MODULE), source);
        Path input = raw("");
        for (boolean report : List.of(false, true)) {
            Path output = directory.resolve("coverage-" + report + ".xml");
            var arguments = new java.util.ArrayList<>(List.of("convert", "-s", sources.toString(),
                    "-c", input.toString(), "-o", output.toString()));
            if (report) {
                arguments.addAll(List.of("--exclusion-report", directory.resolve("exclusions.json").toString()));
            }
            assertEquals(0, execute(arguments.toArray(String[]::new)));
            assertEquals(expected, coverage(output, MODULE), "exclusion report: " + report);
        }
    }

    @Test
    void legacyRawExtensionAttributeIsIgnoredWithoutAnExplicitSelector() throws Exception {
        Path project = project();
        Path input = raw("Sample");
        Path output = directory.resolve("coverage.xml");
        assertEquals(0, execute("convert", "-s", project.resolve("configuration").toString(),
                "-c", input.toString(), "-o", output.toString()));
        assertEquals(Map.of(2, true, 4, true, 6, false), coverage(output, MODULE));
    }

    @Test
    void rejectsQualifiedRawAlongsideLegacyEntries() throws Exception {
        assertRejectedBeforePublishing("""
                <coverage>
                  <file path="/%s/%s"><lineToCover covered="true" lineNumber="2"/></file>
                  <file path="/source-v1/-/-/object/property"/>
                </coverage>
                """.formatted(OBJECT_ID, PROPERTY_ID));
    }

    @Test
    void rejectsTextInsteadOfRawRecordsBeforePublishing() throws Exception {
        assertRejectedBeforePublishing("<coverage>lost records</coverage>");
    }

    @Test
    void rejectsCdataInsteadOfRawRecordsBeforePublishing() throws Exception {
        assertRejectedBeforePublishing("<coverage><![CDATA[lost records]]></coverage>");
    }

    @Test
    void rejectsTextInsteadOfRawLinesBeforePublishing() throws Exception {
        assertRejectedBeforePublishing("<coverage><file path=\"object/property\">lost lines</file></coverage>");
    }

    @Test
    void rejectsTextInsideRawLineBeforePublishing() throws Exception {
        assertRejectedBeforePublishing("<coverage><file path=\"object/property\">"
                + "<lineToCover covered=\"true\" lineNumber=\"2\"><![CDATA[unexpected]]></lineToCover></file></coverage>");
    }

    @Test
    void rejectsNestedRawLineElementsBeforePublishing() throws Exception {
        assertRejectedBeforePublishing("<coverage><file path=\"object/property\">"
                + "<lineToCover covered=\"true\" lineNumber=\"2\"><lineToCover covered=\"true\" lineNumber=\"4\"/>"
                + "</lineToCover></file></coverage>");
    }

    @Test
    void rejectsUnknownRawLineElementBeforePublishing() throws Exception {
        assertRejectedBeforePublishing("<coverage><file path=\"object/property\"><lineToCovre/></file></coverage>");
    }

    @Test
    void rawCommentsAndWhitespaceAreAllowed() throws Exception {
        Path sources = project().resolve("configuration");
        Path input = Files.writeString(directory.resolve("raw.xml"), """
                <coverage><![CDATA[
                ]]><!-- comment --><file path="/%s/%s">
                <!-- comment -->
                  <lineToCover covered="true" lineNumber="2"> <!-- comment --><![CDATA[ ]]></lineToCover>
                </file></coverage>
                """.formatted(OBJECT_ID, PROPERTY_ID));
        Path output = directory.resolve("coverage.xml");
        assertEquals(0, execute("convert", "-s", sources.toString(), "-c", input.toString(), "-o", output.toString()));
        assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output, MODULE));
    }

    @Test
    void matchesRawUuidsRegardlessOfCase() throws Exception {
        Path sources = project().resolve("configuration");
        Path input = Files.writeString(directory.resolve("raw.xml"), rawWithLineNumber("2")
                .replace(OBJECT_ID, OBJECT_ID.toUpperCase(java.util.Locale.ROOT))
                .replace(PROPERTY_ID, PROPERTY_ID.toUpperCase(java.util.Locale.ROOT)));
        Path output = directory.resolve("coverage.xml");
        assertEquals(0, execute("convert", "-s", sources.toString(), "-c", input.toString(), "-o", output.toString()));
        assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output, MODULE));
    }

    @Test
    void ordinaryConversionReportsCoverageWriteFailure() throws Exception {
        Path sources = project().resolve("configuration");
        Path output = Files.createDirectory(directory.resolve("coverage.xml"));
        assertEquals(1, execute("convert", "-s", sources.toString(), "-c", raw("").toString(), "-o", output.toString()));
    }

    @Test
    void rejectsGenericCoverageXmlAsRaw() throws Exception {
        assertRejectedBeforePublishing("<coverage><file path=\"Ext/Module.BSL\"/></coverage>");
    }

    @Test
    void rejectsUnknownRawVersionBeforePublishing() throws Exception {
        assertRejectedBeforePublishing(rawWithLineNumber("2").replace("<coverage>", "<coverage version=\"2\">"));
    }

    @Test
    void rejectsExplicitEmptyRawVersionBeforePublishing() throws Exception {
        assertRejectedBeforePublishing(rawWithLineNumber("2").replace("<coverage>", "<coverage version=\"\">"));
    }

    @Test
    void rejectsUnknownVersionEvenForEmptyRaw() throws Exception {
        assertRejectedBeforePublishing("<coverage version=\"2\"/>");
    }

    @Test
    void acceptsLegacyRawWithAndWithoutVersionOne() throws Exception {
        Path sources = project().resolve("configuration");
        Path input = directory.resolve("raw.xml");
        int variant = 0;
        for (String root : List.of("<coverage>", "<coverage version=\"1\">")) {
            Path output = directory.resolve("coverage-" + variant++ + ".xml");
            Files.writeString(input, rawWithLineNumber("2").replace("<coverage>", root));
            assertEquals(0, execute("convert", "-s", sources.toString(), "-c", input.toString(),
                    "-o", output.toString()), root);
            assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output, MODULE), root);
        }
    }

    @Test
    void rejectsCoberturaXmlAsRaw() throws Exception {
        assertRejectedBeforePublishing("<coverage><packages/></coverage>");
    }

    @Test
    void rejectsUnrelatedXmlAsRaw() throws Exception {
        assertRejectedBeforePublishing("<report/>");
    }

    @Test
    void rejectsInvalidRawLineInAModuleWithoutSourcesBeforePublishing() throws Exception {
        assertRejectedBeforePublishing("""
                <coverage><file path="unknown-object/unknown-property" extension="unselected">
                  <lineToCover covered="yes" lineNumber="2"/>
                </file></coverage>
                """);
    }

    @Test
    void rejectsInvalidRawLineNumberEvenForAnUncoveredLine() throws Exception {
        assertRejectedBeforePublishing("""
                <coverage><file path="/%s/%s">
                  <lineToCover covered="false" lineNumber="missing"/>
                </file></coverage>
                """.formatted(OBJECT_ID, PROPERTY_ID));
    }

    @Test
    void rejectsFractionalRawLineNumberBeforePublishing() throws Exception {
        assertRejectedBeforePublishing(rawWithLineNumber("2.5"));
    }

    @Test
    void rejectsOutOfRangeRawLineNumberBeforePublishing() throws Exception {
        assertRejectedBeforePublishing(rawWithLineNumber("2147483648"));
    }

    private static String rawWithLineNumber(String lineNumber) {
        return """
                <coverage><file path="/%s/%s">
                  <lineToCover covered="true" lineNumber="%s"/>
                </file></coverage>
                """.formatted(OBJECT_ID, PROPERTY_ID, lineNumber);
    }

    @Test
    void convertsWholeNumberWithDecimalScaleToTheMatchingLine() throws Exception {
        Path sources = project().resolve("configuration");
        Path input = Files.writeString(directory.resolve("raw.xml"), rawWithLineNumber("2.0"));
        Path output = directory.resolve("coverage.xml");
        assertEquals(0, execute("convert", "-s", sources.toString(), "-c", input.toString(), "-o", output.toString()));
        assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output, MODULE));
    }

    @Test
    void rawCannotLoadAnExternalDtd() throws Exception {
        Path dtd = Files.writeString(directory.resolve("coverage.dtd"), "<!ELEMENT coverage ANY>");
        assertRejectedBeforePublishing("<!DOCTYPE coverage SYSTEM \"%s\"><coverage/>"
                .formatted(dtd.toUri().toASCIIString()));
    }

    @Test
    void rawAllowsASelfContainedDocumentType() throws Exception {
        assertNoHits("<!DOCTYPE coverage [<!ELEMENT coverage EMPTY>]><coverage/>");
    }

    private void assertRejectedBeforePublishing(String xml) throws Exception {
        Path project = project();
        Path input = Files.writeString(directory.resolve("internal.xml"), xml);
        Path output = Files.writeString(directory.resolve("coverage.xml"), "previous coverage");
        Path report = directory.resolve("exclusions.json");
        assertEquals(1, execute("convert", "-s", project.resolve("configuration").toString(),
                "-c", input.toString(), "-o", output.toString(), "--exclusion-report", report.toString()));
        assertEquals("previous coverage", Files.readString(output));
        assertFalse(Files.exists(report));
    }

    @Test
    void acceptsEmptyLegacyRaw() throws Exception {
        assertNoHits("<coverage/>");
    }

    @Test
    void acceptsLegacyRawWithAnUnmappedModule() throws Exception {
        assertNoHits("""
                <coverage><file path="unknown-object/unknown-property">
                  <lineToCover covered="true" lineNumber="2"/>
                </file></coverage>
                """);
    }

    private void assertNoHits(String xml) throws Exception {
        Path project = project();
        Path input = Files.writeString(directory.resolve("internal.xml"), xml);
        Path output = directory.resolve("coverage.xml");
        assertEquals(0, execute("convert", "-s", project.resolve("configuration").toString(),
                "-c", input.toString(), "-o", output.toString()));
        assertEquals(Map.of(2, false, 4, false, 6, false), coverage(output, MODULE));
    }

    @Test
    void usesRulesForConfiguredConversionAndWritesASeparateReport() throws Exception {
        Path project = project();
        Path input = raw("");
        Path config = config("""
                {"exclude":{"objects":["CommonModule.ПростойОбщийМодуль"]},"changeMarkers":{}}
                """);
        Path output = directory.resolve("coverage.xml");
        Path report = directory.resolve("diagnostics/exclusions.json");
        assertEquals(0, execute("convert", "-P", project.toString(), "-s", "configuration", "-c", input.toString(),
                "--config", config.toString(), "-o", output.toString(), "--exclusion-report", report.toString()));
        assertEquals(Map.of(4, true), coverage(output, "configuration/" + MODULE));
        var json = new ObjectMapper().readTree(report.toFile());
        assertTrue(json.path("excludedLines").asInt() > 0);
        assertFalse(json.path("changeBlocks").isEmpty());
    }

    @Test
    void configuredConversionReportsCoverageWriteFailure() throws Exception {
        Path sources = project().resolve("configuration");
        Path outputDirectory = Files.createDirectory(directory.resolve("coverage.xml"));
        Path input = raw("");
        for (String format : new String[]{"GENERIC_COVERAGE", "COBERTURA", "LCOV"}) {
            Path report = directory.resolve("exclusions-" + format + ".json");
            assertEquals(1, execute("convert", "-s", sources.toString(), "-c", input.toString(),
                    "-o", outputDirectory.toString(), "-f", format, "--exclusion-report", report.toString()), format);
            assertTrue(Files.isRegularFile(report), format + " passed preparation");
            assertTrue(new ObjectMapper().readTree(report.toFile()).isObject(), format + " wrote valid JSON");
        }
    }

    @Test
    void rawProcessingInstructionsCannotBeMistakenForLineRecords() throws Exception {
        Path sources = project().resolve("configuration");
        Path input = raw("");
        Files.writeString(input, Files.readString(input).replace("<lineToCover ",
                "<?lineToCover ignored?>\n<!-- line record -->\n<lineToCover "));
        Path output = directory.resolve("coverage.xml");
        assertEquals(0, execute("convert", "-s", sources.toString(), "-c", input.toString(), "-o", output.toString()));
        assertEquals(Map.of(2, true, 4, true, 6, false), coverage(output, MODULE));
    }

    @Test
    void lcovCountsAnOptionalLineOnlyAfterItIsCovered() throws Exception {
        Path sources = project().resolve("configuration");
        Path input = raw("");
        String withHit = Files.readString(input);
        Path config = config("{\"auto\":{\"lines\":[\"Второй\"]}}");
        Path output = directory.resolve("coverage.lcov");
        String[] arguments = {"convert", "-s", sources.toString(), "-c", input.toString(),
                "-o", output.toString(), "-f", "LCOV", "--config", config.toString()};

        Files.writeString(input, withHit.replace("covered=\"true\" lineNumber=\"4\"", "covered=\"false\" lineNumber=\"4\""));
        assertEquals(0, execute(arguments));
        var uncovered = lcovRecord(output, MODULE);
        assertTrue(uncovered.stream().noneMatch(line -> line.startsWith("DA:4,")));
        assertTrue(uncovered.contains("LF:2"));
        assertTrue(uncovered.contains("LH:1"));

        Files.writeString(input, withHit);
        assertEquals(0, execute(arguments));
        var covered = lcovRecord(output, MODULE);
        assertTrue(covered.contains("DA:4,1"));
        assertTrue(covered.contains("LF:3"));
        assertTrue(covered.contains("LH:2"));
    }

    private static List<String> lcovRecord(Path file, String source) throws Exception {
        for (String record : Files.readString(file).split("end_of_record")) {
            var lines = record.lines().toList();
            if (lines.contains("SF:" + source)) {
                return lines;
            }
        }
        throw new AssertionError("Missing module in LCOV: " + source);
    }

    @Test
    void configuredConversionKeepsInputAndDoesNotPublishOnReportCollision() throws Exception {
        Path project = project();
        Path input = raw("");
        String rawBefore = Files.readString(input);
        Path config = config("{\"changeMarkers\":{}}");
        Path output = directory.resolve("coverage.xml");
        assertEquals(1, execute("convert", "-s", project.resolve("configuration").toString(), "-c", input.toString(),
                "--config", config.toString(), "-o", output.toString(), "--exclusion-report", input.toString()));
        assertEquals(rawBefore, Files.readString(input));
        assertFalse(Files.exists(output));
        assertEquals(1, execute("convert", "-s", project.resolve("configuration").toString(), "-c", input.toString(),
                "--config", config.toString(), "-o", output.toString(), "--exclusion-report", output.toString()));
        assertFalse(Files.exists(output));
    }

    @Test
    void configuredConversionProtectsDesignerBinaryModulesFromCoverageOutput() throws Exception {
        Path sources = project().resolve("configuration");
        Path module = sources.resolve(MODULE);
        Files.delete(module);
        assertProtectedModuleCannotBeOverwritten(sources, module.resolveSibling("Module.bin"));
    }

    @Test
    void configuredConversionProtectsEdtBinaryModulesWithBslExtension() throws Exception {
        Path sources = directory.resolve("edt");
        TestUtils.copyDirectory(Path.of("src/test/resources/edt/pc"), sources);
        Path module = sources.resolve("src/CommonModules/ПростойОбщийМодуль/Module.bsl");
        assertTrue(Files.exists(module));
        assertProtectedModuleCannotBeOverwritten(sources, module);
    }

    private void assertProtectedModuleCannotBeOverwritten(Path sources, Path module) throws Exception {
        byte[] original = {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f, 1};
        Files.write(module, original);
        Path input = raw("");
        Path rules = config("{\"changeMarkers\":{}}");
        Path report = directory.resolve("exclusions.json");
        assertEquals(1, execute("convert", "-s", sources.toString(), "--config", rules.toString(),
                "-c", input.toString(), "-o", module.toString(), "--exclusion-report", report.toString()));
        assertArrayEquals(original, Files.readAllBytes(module));
        assertFalse(Files.exists(report));

        Path coverage = directory.resolve("coverage.xml");
        assertEquals(1, execute("convert", "-s", sources.toString(), "--config", rules.toString(),
                "-c", input.toString(), "-o", coverage.toString(), "--exclusion-report", module.toString()));
        assertArrayEquals(original, Files.readAllBytes(module));
        assertFalse(Files.exists(coverage));
    }

    @Test
    void configuredConversionProtectsObjectMetadataFromCoverageOutput() throws Exception {
        Path project = project();
        Path metadata = project.resolve("configuration/CommonModules/ПростойОбщийМодуль.xml");
        byte[] previous = Files.readAllBytes(metadata);
        Path input = raw("");
        Path config = config("{\"changeMarkers\":{}}");
        Path report = directory.resolve("diagnostics.json");

        assertEquals(1, execute("convert", "-P", project.toString(), "-s", "configuration",
                "-c", input.toString(), "--config", config.toString(), "-o", metadata.toString(),
                "--exclusion-report", report.toString()));
        assertArrayEquals(previous, Files.readAllBytes(metadata));
        assertFalse(Files.exists(report));
    }

    @Test
    void designerSupportDataCannotBeReplacedByEitherReport() throws Exception {
        Path sources = project().resolve("configuration");
        Path support = sources.resolve("Ext/ParentConfigurations.bin");
        Files.copy(Path.of("src/test/resources/support-data/ParentConfigurations.bin"), support);
        assertSupportDataCannotBeOverwritten(sources, support);
    }

    @Test
    void edtSupportDataCannotBeReplacedByEitherReport() throws Exception {
        Path sources = edtProject();
        Path support = sources.resolve("src/Configuration/ParentConfigurations.bin");
        Files.copy(Path.of("src/test/resources/support-data/ParentConfigurations.bin"), support);
        assertSupportDataCannotBeOverwritten(sources, support);
    }

    @Test
    void edtDistributionCannotBeReplacedByEitherReport() throws Exception {
        Path sources = edtProject();
        Path support = sources.resolve("src/Configuration/Configuration.distr");
        Files.copy(Path.of("src/test/resources/support-data/Configuration.distr"), support);
        assertSupportDataCannotBeOverwritten(sources, support);
    }

    private void assertSupportDataCannotBeOverwritten(Path sources, Path support) throws Exception {
        byte[] original = Files.readAllBytes(support);
        assertEquals(0, execute("validate", "-s", sources.toString()));
        assertEquals(1, execute("validate", "-s", sources.toString(), "--exclusion-report", support.toString()));
        assertArrayEquals(original, Files.readAllBytes(support));

        Path input = raw("");
        Path output = directory.resolve("coverage.xml");
        Path report = directory.resolve("exclusions.json");
        assertEquals(1, execute("convert", "-s", sources.toString(), "-c", input.toString(),
                "-o", support.toString(), "--exclusion-report", report.toString()));
        assertArrayEquals(original, Files.readAllBytes(support));
        assertFalse(Files.exists(report));
        assertEquals(1, execute("convert", "-s", sources.toString(), "-c", input.toString(),
                "-o", output.toString(), "--exclusion-report", support.toString()));
        assertArrayEquals(original, Files.readAllBytes(support));
        assertFalse(Files.exists(output));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void validationProtectsSupportDataThroughFileAliases() throws Exception {
        Path sources = project().resolve("configuration");
        Path support = sources.resolve("Ext/ParentConfigurations.bin");
        Files.copy(Path.of("src/test/resources/support-data/ParentConfigurations.bin"), support);
        byte[] original = Files.readAllBytes(support);
        for (boolean symbolic : List.of(false, true)) {
            Path report = directory.resolve("alias-" + symbolic + ".json");
            if (symbolic) Files.createSymbolicLink(report, support);
            else Files.createLink(report, support);
            assertEquals(1, execute("validate", "-s", sources.toString(), "--exclusion-report", report.toString()));
            assertArrayEquals(original, Files.readAllBytes(support));
        }
    }

    @Test
    void designerConversionCanUpdateItsOwnReportsInsideSources() throws Exception {
        Path sources = project().resolve("configuration");
        assertRepeatedConversion(sources, List.of(
                "CommonModules/ПростойОбщийМодуль/Ext/coverage.xml", "CommonModules/coverage.xml"), MODULE);
    }

    @Test
    void edtConversionCanUpdateItsOwnReportsInsideSources() throws Exception {
        Path sources = edtProject();
        String module = "src/CommonModules/ПростойОбщийМодуль/Module.bsl";
        Files.writeString(sources.resolve(module), SOURCE);
        assertRepeatedConversion(sources, List.of(
                "src/CommonModules/ПростойОбщийМодуль/coverage.xml", "src/CommonModules/coverage.xml",
                "src/CommonModules/ПростойОбщийМодуль/coverage.mdo"), module);
    }

    private void assertRepeatedConversion(Path sources, List<String> paths, String module) throws Exception {
        Path input = raw("");
        String before = Files.readString(input);
        Path report = directory.resolve("exclusions.json");
        for (String format : List.of("GENERIC_COVERAGE", "LCOV", "COBERTURA")) {
            for (String path : paths) {
                Path output = sources.resolve(path);
                Files.writeString(input, before);
                String[] arguments = {"convert", "-s", sources.toString(), "-c", input.toString(),
                        "-o", output.toString(), "-f", format, "--exclusion-report", report.toString()};
                assertEquals(0, execute(arguments), format + ": first write to " + path);
                Files.writeString(input, before.replace("covered=\"true\" lineNumber=\"4\"",
                        "covered=\"false\" lineNumber=\"4\""));
                assertEquals(0, execute(arguments), format + ": repeated write to " + path);
                if (format.equals("GENERIC_COVERAGE")) {
                    assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output, module));
                }
                Files.delete(output);
            }
        }
    }

    @Test
    void designerValidationCanUpdateAJsonReportNamedXmlInsideSources() throws Exception {
        Path sources = project().resolve("configuration");
        assertRepeatedValidation(sources, sources.resolve(MODULE),
                sources.resolve("CommonModules/ПростойОбщийМодуль/Ext/exclusions.xml"));
    }

    @Test
    void edtValidationCanUpdateAJsonReportNamedXmlInsideSources() throws Exception {
        Path sources = edtProject();
        Path module = sources.resolve("src/CommonModules/ПростойОбщийМодуль/Module.bsl");
        Files.writeString(module, SOURCE);
        assertRepeatedValidation(sources, module,
                sources.resolve("src/CommonModules/ПростойОбщийМодуль/exclusions.xml"));
    }

    private void assertRepeatedValidation(Path sources, Path module, Path report) throws Exception {
        String[] arguments = {"validate", "-s", sources.toString(), "--exclusion-report", report.toString()};
        assertEquals(0, execute(arguments));
        assertTrue(new ObjectMapper().readTree(report.toFile()).path("warnings").isEmpty());
        Files.writeString(module, "// Cover:off\n" + SOURCE);
        assertEquals(0, execute(arguments));
        assertTrue(new ObjectMapper().readTree(report.toFile()).path("warnings").toString().contains("not closed"));
    }

    @Test
    void validationStillProtectsDesignerMetadataFiles() throws Exception {
        Path sources = project().resolve("configuration");
        assertMetadataFilesCannotBeOverwritten(sources, sources);
    }

    @Test
    void validationStillProtectsEdtMetadataFiles() throws Exception {
        Path sources = edtProject();
        assertMetadataFilesCannotBeOverwritten(sources, sources.resolve("src"));
    }

    private void assertMetadataFilesCannotBeOverwritten(Path sources, Path metadata) throws Exception {
        List<Path> inputs;
        try (var files = Files.walk(metadata)) {
            inputs = files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().matches(".*\\.(xml|mdo|form)")).sorted().toList();
        }
        assertFalse(inputs.isEmpty());
        for (Path input : inputs) {
            byte[] original = Files.readAllBytes(input);
            assertEquals(1, execute("validate", "-s", sources.toString(), "--exclusion-report", input.toString()),
                    "metadata must remain protected: " + input);
            assertArrayEquals(original, Files.readAllBytes(input));
        }
    }

    private Path edtProject() throws Exception {
        Path sources = directory.resolve("edt");
        TestUtils.copyDirectory(Path.of("src/test/resources/edt/pc"), sources);
        return sources;
    }

    @Test
    void configuredConversionStillSeparatesExtensionsSelectedWithTheExistingFlags() throws Exception {
        Path project = project();
        Path extension = project.resolve("extensions/Sample");
        TestUtils.copyDirectory(project.resolve("configuration"), extension);
        String key = OBJECT_ID + "/" + PROPERTY_ID;
        Path input = Files.writeString(directory.resolve("internal.xml"), """
                <coverage version="1">
                  <file path="/%s"><lineToCover covered="true" lineNumber="2"/></file>
                  <file path="/%s" extension="Sample"><lineToCover covered="true" lineNumber="4"/></file>
                </coverage>
                """.formatted(key, key));
        Path config = config("{\"exclude\":{\"lines\":[\"Первый\"]}}");
        Path output = directory.resolve("coverage.xml");
        assertEquals(0, execute("convert", "-P", project.toString(), "-s", "configuration",
                "--extension", "Sample=extensions/Sample", "-c", input.toString(), "--config", config.toString(),
                "-o", output.toString()));
        assertEquals(Map.of(4, false, 6, false), coverage(output, "configuration/" + MODULE));
        assertEquals(Map.of(4, true, 6, false), coverage(output, "extensions/Sample/" + MODULE));
    }

    @Test
    void configuredConversionRejectsRepeatedSourcesBeforePublishing() throws Exception {
        Path project = project();
        Path config = config("{\"changeMarkers\":{}}");
        Path output = Files.writeString(directory.resolve("coverage.xml"), "previous coverage");
        Path report = Files.writeString(directory.resolve("report.json"), "previous report");

        assertEquals(1, execute("convert", "-P", project.toString(), "-s", "configuration",
                "--extension", "Second=configuration", "--config", config.toString(),
                "-c", raw("").toString(), "-o", output.toString(), "--exclusion-report", report.toString()));
        assertEquals("previous coverage", Files.readString(output));
        assertEquals("previous report", Files.readString(report));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void validationRejectsSourceDirectoryAliases() throws Exception {
        Path project = project();
        Files.createSymbolicLink(project.resolve("linked"), Path.of("configuration"));
        Path report = Files.writeString(directory.resolve("report.json"), "previous report");

        assertEquals(1, execute("validate", "-P", project.toString(), "-s", "configuration",
                "--extension", "Second=linked", "--exclusion-report", report.toString()));
        assertEquals("previous report", Files.readString(report));
    }

    @Test
    void validationRejectsRepeatedExtensionDirectories() throws Exception {
        Path project = project();
        TestUtils.copyDirectory(project.resolve("configuration"), project.resolve("extensions/Sample"));
        Path report = Files.writeString(directory.resolve("report.json"), "previous report");

        assertEquals(1, execute("validate", "-P", project.toString(), "-s", "configuration",
                "--extension", "First=extensions/Sample", "--extension", "Second=extensions/./Sample",
                "--exclusion-report", report.toString()));
        assertEquals("previous report", Files.readString(report));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void configuredConversionRejectsAnExclusionReportHardLinkedToTheConfig() throws Exception {
        Path project = project();
        Path input = raw("");
        String original = "{\"changeMarkers\":{}}";
        Path config = config(original);
        Path report = Files.createLink(directory.resolve("exclusions.json"), config);
        Path output = directory.resolve("coverage.xml");
        assertEquals(1, execute("convert", "-P", project.toString(), "-s", "configuration", "-c", input.toString(),
                "--config", config.toString(), "-o", output.toString(), "--exclusion-report", report.toString()));
        assertEquals(original, Files.readString(config));
        assertFalse(Files.exists(output));
    }

    @Test
    void validateProtectsObjectMetadataAndAllowsAnUnrelatedXmlReport() throws Exception {
        Path project = project();
        Path sources = project.resolve("configuration");
        Path metadata = sources.resolve("CommonModules/ПростойОбщийМодуль.xml");
        String previous = Files.readString(metadata);

        assertEquals(1, execute("validate", "-s", sources.toString(), "--exclusion-report", metadata.toString()));
        assertEquals(previous, Files.readString(metadata));

        Path report = Files.writeString(sources.resolve("diagnostic.xml"), "previous report");
        assertEquals(0, execute("validate", "-s", sources.toString(), "--exclusion-report", report.toString()));
        assertTrue(new ObjectMapper().readTree(report.toFile()).has("excludedLines"));
    }

    @Test
    void validateProtectsEdtObjectMetadata() throws Exception {
        Path sources = directory.resolve("edt");
        TestUtils.copyDirectory(Path.of("src/test/resources/edt/pc"), sources);
        Path metadata = sources.resolve("src/CommonModules/ПростойОбщийМодуль/ПростойОбщийМодуль.mdo");
        String previous = Files.readString(metadata);

        assertEquals(1, execute("validate", "-s", sources.toString(), "--exclusion-report", metadata.toString()));
        assertEquals(previous, Files.readString(metadata));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void validateRejectsAnExclusionReportHardLinkedToObjectMetadata() throws Exception {
        Path sources = project().resolve("configuration");
        Path metadata = sources.resolve("CommonModules/ПростойОбщийМодуль.xml");
        String previous = Files.readString(metadata);
        Path report = Files.createLink(directory.resolve("report.json"), metadata);

        assertEquals(1, execute("validate", "-s", sources.toString(), "--exclusion-report", report.toString()));
        assertEquals(previous, Files.readString(metadata));
    }

    @Test
    void validateCanUseAnExplicitReportAndFailsStrictValidationOnWarnings() throws Exception {
        Path project = project();
        Path config = config("""
                {"includeSubsystems":["ПерваяПодсистема"],"exclusionReport":"ignored.json"}
                """);
        Path report = directory.resolve("report.json");
        assertEquals(0, execute("validate", "-P", project.toString(), "-s", "configuration", "--strict",
                "--config", config.toString(), "--exclusion-report", report.toString()));
        assertTrue(Files.exists(report));
        assertFalse(Files.exists(project.resolve("ignored.json")));

        Files.writeString(config, "{\"includeSubsystems\":[\"UnknownSubsystem*\"]}");
        assertEquals(1, execute("validate", "-P", project.toString(), "-s", "configuration", "--strict",
                "--config", config.toString()));
        assertEquals(0, execute("validate", "-P", project.toString(), "-s", "configuration",
                "--config", config.toString()));
    }

    @Test
    void strictValidationAcceptsMarkerBeforeExportOnFollowingLine() throws Exception {
        Path sources = project().resolve("configuration");
        Files.writeString(sources.resolve(MODULE), """
                Процедура Пример(
                    Параметр) // Cover:off
                Экспорт
                    Шаг();
                КонецПроцедуры
                """);
        Path report = directory.resolve("report.json");

        assertEquals(0, execute("validate", "-s", sources.toString(), "--strict",
                "--exclusion-report", report.toString()));
        var json = new ObjectMapper().readTree(report.toFile());
        assertTrue(json.path("excludedLines").asInt() > 0);
        assertTrue(json.path("warnings").isEmpty());
    }

    @Test
    void strictValidationReportsIncompleteInlineMarkerBoundary() throws Exception {
        Path sources = project().resolve("configuration");
        Files.writeString(sources.resolve(MODULE), """
                Процедура Пример()
                    Если Готово Тогда // Cover:off
                        Шаг();
                КонецПроцедуры
                """);
        Path report = directory.resolve("report.json");

        assertEquals(1, execute("validate", "-s", sources.toString(), "--strict",
                "--exclusion-report", report.toString()));
        var warnings = new ObjectMapper().readTree(report.toFile()).path("warnings");
        assertTrue(warnings.toString().contains("incomplete"), warnings.toString());
        assertEquals(0, execute("validate", "-s", sources.toString()));
    }

    @Test
    void strictValidationKeepsWarningsOnAsyncPrefixMethodHeaders() throws Exception {
        Path sources = project().resolve("configuration");
        String source = "Асинх // Cover:on\nПроцедура тестЗапуск()\nШаг();\nКонецПроцедуры\n";
        Files.writeString(sources.resolve(MODULE), source);
        Path config = config("{\"includePrefix\":[\"тест\"],\"prefixScope\":{\"routines\":true}}");
        Path report = directory.resolve("report.json");

        assertEquals(1, execute("validate", "-s", sources.toString(), "--strict",
                "--config", config.toString(), "--exclusion-report", report.toString()));
        assertTrue(new ObjectMapper().readTree(report.toFile()).path("warnings").toString()
                .contains(":1: Cover:on without open"));
        Files.writeString(sources.resolve(MODULE), source.replace(" // Cover:on", ""));
        assertEquals(0, execute("validate", "-s", sources.toString(), "--strict", "--config", config.toString()));
    }

    @Test
    void prefixedRoutineInFileOffModuleDoesNotMakeScopeNonempty() throws Exception {
        Path project = project();
        Files.writeString(project.resolve("configuration").resolve(MODULE), "// Cover:file-off\n" + SOURCE);
        Path config = config("""
                {"includePrefix":["Пример"],"prefixScope":{"subsystems":false,"modules":false,"routines":true}}
                """);
        Path report = directory.resolve("exclusions.json");
        assertEquals(1, execute("validate", "-s", project.resolve("configuration").toString(), "--strict",
                "--config", config.toString(), "--exclusion-report", report.toString()));
        var warnings = new ObjectMapper().readTree(report.toFile()).path("warnings");
        assertTrue(warnings.toString().contains("Coverage scope is empty"));
        assertFalse(warnings.toString().contains("rule does not match anything"));
    }

    @Test
    void validateAndConvertProtectExternalFormDescriptor() throws Exception {
        Path processor = externalProcessor();
        Path form = Files.createDirectories(directory.resolve("Sample/Forms")).resolve("Main.xml");
        String descriptor = "<MetaDataObject><Form uuid=\"form-id\"><Properties><Name>Main</Name></Properties></Form></MetaDataObject>";
        Files.writeString(form, descriptor);
        assertEquals(1, execute("validate", "-s", processor.toString(), "--exclusion-report", form.toString()));
        assertEquals(descriptor, Files.readString(form));

        Path input = raw("");
        Path report = directory.resolve("exclusions.json");
        assertEquals(1, execute("convert", "-s", processor.toString(), "-c", input.toString(),
                "-o", form.toString(), "--exclusion-report", report.toString()));
        assertEquals(descriptor, Files.readString(form));
        assertFalse(Files.exists(report));
    }

    @Test
    void externalProcessorAndFormCannotLoadAnExternalDtd() throws Exception {
        Path processor = externalProcessor();
        Path form = directory.resolve("Sample/Forms/Main.xml");
        Path dtd = Files.writeString(directory.resolve("descriptor.dtd"), "<!ELEMENT MetaDataObject ANY>");
        String doctype = "<!DOCTYPE MetaDataObject SYSTEM \"%s\">".formatted(dtd.toUri().toASCIIString());
        String descriptor = Files.readString(processor);
        Path report = directory.resolve("exclusions.json");
        Files.writeString(processor, doctype + descriptor);
        assertEquals(1, execute("validate", "-s", processor.toString(), "--exclusion-report", report.toString()));
        assertFalse(Files.exists(report));

        Files.writeString(processor, descriptor);
        Files.writeString(form, doctype + """
                <MetaDataObject><Form uuid="form-id"><Properties><Name>Main</Name></Properties></Form></MetaDataObject>
                """);
        assertEquals(1, execute("validate", "-s", processor.toString(), "--strict", "--exclusion-report", report.toString()));
        assertTrue(new ObjectMapper().readTree(report.toFile()).path("warnings").toString().contains("Main.xml"));
    }

    @Test
    void strictValidationReportsUnreadableFormAndStillChecksOtherModules() throws Exception {
        Path processor = externalProcessor();
        Path forms = directory.resolve("Sample/Forms");
        Files.writeString(forms.resolve("Broken.xml"), "<MetaDataObject>");
        Files.writeString(forms.resolve("Incomplete.xml"), "<MetaDataObject/>");
        Files.writeString(forms.resolve("Main.xml"), """
                <MetaDataObject><Form uuid="form-id"><Properties><Name>Main</Name></Properties></Form></MetaDataObject>
                """);
        Path formModule = Files.createDirectories(forms.resolve("Main/Ext/Form")).resolve("Module.bsl");
        Files.writeString(formModule, "// Cover:auto\n" + SOURCE + "// Cover:on\n");
        Path report = directory.resolve("exclusions.json");
        assertEquals(1, execute("validate", "-s", processor.toString(), "--strict", "--exclusion-report", report.toString()));
        var json = new ObjectMapper().readTree(report.toFile());
        String warnings = json.path("warnings").toString();
        assertTrue(warnings.contains("Broken.xml"));
        assertTrue(warnings.contains("Incomplete.xml"));
        assertTrue(json.path("autoLines").asInt() > 0);
        assertEquals(0, execute("validate", "-s", processor.toString(), "--exclusion-report", report.toString()));
    }

    @Test
    void reportsUnreadableExternalModuleAndKeepsItsRawKeyUsable() throws Exception {
        Path processor = externalProcessor();
        Path objectModule = directory.resolve("Sample/Ext/ObjectModule.bsl");
        Files.write(objectModule, new byte[]{(byte) 0xc3, 0x28});
        Path report = directory.resolve("exclusions.json");
        assertEquals(1, execute("validate", "-s", processor.toString(), "--strict",
                "--exclusion-report", report.toString()));
        var warnings = new ObjectMapper().readTree(report.toFile()).path("warnings");
        assertTrue(warnings.toString().contains("ObjectModule.bsl: cannot read module"));

        class Options {
            @CommandLine.Mixin MetadataOptions metadata = new MetadataOptions();
        }
        Options options = new Options();
        new CommandLine(options).parseArgs("-s", processor.toString(), "--exclusion-report", report.toString());
        Map<URI, Map<BigDecimal, Integer>> data = new HashMap<>();
        var prepared = ConfiguredCoverage.prepare(options.metadata, "", data, null, null, false);
        assertEquals(objectModule.toUri(), prepared.modules().get("object-id/a637f77f-3840-441d-a1c3-699c8c5cb7e0"));
        assertTrue(data.containsKey(objectModule.toUri()));
        assertTrue(data.get(objectModule.toUri()).isEmpty(), "unreadable source has no measurable lines");

        Path input = Files.writeString(directory.resolve("raw.xml"), """
                <coverage><file path="/object-id/a637f77f-3840-441d-a1c3-699c8c5cb7e0">
                  <lineToCover covered="true" lineNumber="2"/>
                </file></coverage>
                """);
        Path output = directory.resolve("coverage-with-report.xml");
        Path convertedReport = directory.resolve("convert-exclusions.json");
        assertEquals(0, execute("convert", "-s", processor.toString(), "-c", input.toString(),
                "-o", output.toString(), "--exclusion-report", convertedReport.toString()));
        assertEquals(0, Utils.newInputXmlBuilder().parse(output.toFile()).getElementsByTagName("lineToCover").getLength());
        assertTrue(new ObjectMapper().readTree(convertedReport.toFile()).path("warnings").toString()
                .contains("ObjectModule.bsl: cannot read module"));
        Path outputWithoutReport = directory.resolve("coverage-without-report.xml");
        assertEquals(0, execute("convert", "-s", processor.toString(), "-c", input.toString(),
                "-o", outputWithoutReport.toString()));
        assertEquals(0, Utils.newInputXmlBuilder().parse(outputWithoutReport.toFile())
                .getElementsByTagName("lineToCover").getLength());
    }

    @Test
    void externalModuleWithoutSourceTextIsSkippedWithAndWithoutAReport() throws Exception {
        Path processor = externalProcessor();
        Files.write(directory.resolve("Sample/Ext/ObjectModule.bsl"),
                new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f});
        Path input = Files.writeString(directory.resolve("raw.xml"), """
                <coverage><file path="/object-id/a637f77f-3840-441d-a1c3-699c8c5cb7e0">
                  <lineToCover covered="true" lineNumber="2"/>
                </file></coverage>
                """);
        for (boolean report : List.of(false, true)) {
            Path output = directory.resolve("coverage-" + report + ".xml");
            var arguments = new java.util.ArrayList<>(List.of("convert", "-s", processor.toString(),
                    "-c", input.toString(), "-o", output.toString()));
            if (report) {
                arguments.addAll(List.of("--exclusion-report", directory.resolve("exclusions.json").toString()));
            }
            assertEquals(0, execute(arguments.toArray(String[]::new)));
            assertEquals(0, Utils.newInputXmlBuilder().parse(output.toFile())
                    .getElementsByTagName("lineToCover").getLength());
        }
    }

    @Test
    void externalProcessorWithoutRootIdIsRejected() throws Exception {
        Path processor = externalProcessor();
        Files.writeString(processor, "<MetaDataObject><ExternalDataProcessor/></MetaDataObject>");
        assertEquals(1, execute("validate", "-s", processor.toString(), "--strict"));
    }

    @Test
    void edtExternalProcessorReportsIncompleteFormsAndMapsValidModules() throws Exception {
        Path processor = Files.writeString(directory.resolve("Sample.mdo"), """
                <mdclass:ExternalDataProcessor xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass" uuid="object-id">
                  <forms uuid="broken-form"/>
                  <forms uuid="form-id"><name>Main</name></forms>
                  <forms><name>NoId</name></forms>
                </mdclass:ExternalDataProcessor>
                """);
        Files.writeString(directory.resolve("ObjectModule.bsl"), SOURCE);
        Files.writeString(Files.createDirectories(directory.resolve("Forms/Main")).resolve("Module.bsl"), SOURCE);
        Path report = directory.resolve("exclusions.json");
        assertEquals(1, execute("validate", "-s", processor.toString(), "--strict", "--exclusion-report", report.toString()));
        String warnings = new ObjectMapper().readTree(report.toFile()).path("warnings").toString();
        assertTrue(warnings.contains("broken-form"));
        assertTrue(warnings.contains("NoId"));
        var modules = Utils.discoverExternalDataProcessorSources(processor, ignored -> {}, ignored -> {});
        assertEquals(2, modules.size());
        assertTrue(modules.keySet().stream().anyMatch(key -> key.startsWith("object-id/")));
        assertTrue(modules.keySet().stream().anyMatch(key -> key.startsWith("form-id/")));
    }

    private Path externalProcessor() throws Exception {
        Files.createDirectories(directory.resolve("Sample/Forms"));
        Path source = Files.createDirectories(directory.resolve("Sample/Ext")).resolve("ObjectModule.bsl");
        Files.writeString(source, SOURCE);
        return Files.writeString(directory.resolve("Sample.xml"), """
                <MetaDataObject><ExternalDataProcessor uuid="object-id"/></MetaDataObject>
                """);
    }

    private Path project() throws Exception {
        Path project = TestUtils.copyConfiguration(directory);
        Files.writeString(project.resolve("configuration").resolve(MODULE), SOURCE);
        return project;
    }

    private Path config(String contents) throws Exception {
        return Files.writeString(directory.resolve("coverage41c.json"), contents);
    }

    private Path raw(String extension) throws Exception {
        return Files.writeString(directory.resolve("internal.xml"), """
                <coverage version="1">
                  <file path="/%s/%s" extension="%s">
                    <lineToCover covered="true" lineNumber="2"/>
                    <lineToCover covered="true" lineNumber="4"/>
                  </file>
                </coverage>
                """.formatted(OBJECT_ID, PROPERTY_ID, extension));
    }

    static Map<Integer, Boolean> coverage(Path output, String module) throws Exception {
        NodeList files = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(output.toFile()).getElementsByTagName("file");
        for (int index = 0; index < files.getLength(); index++) {
            Element file = (Element) files.item(index);
            if (file.getAttribute("path").equals(module)) {
                Map<Integer, Boolean> results = new TreeMap<>();
                NodeList lines = file.getElementsByTagName("lineToCover");
                for (int line = 0; line < lines.getLength(); line++) {
                    Element entry = (Element) lines.item(line);
                    results.put(Integer.parseInt(entry.getAttribute("lineNumber")),
                            Boolean.parseBoolean(entry.getAttribute("covered")));
                }
                return results;
            }
        }
        throw new AssertionError("Missing module in coverage: " + module);
    }

    private static int execute(String... arguments) {
        return Coverage41C.getCommandLine().execute(arguments);
    }
}
