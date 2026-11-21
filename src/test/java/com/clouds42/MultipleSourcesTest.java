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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
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
import java.util.stream.Stream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MultipleSourcesTest {
    static final String MODULE = "CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";
    static final String KEY = "1be4af7e-334e-49fa-a9f9-d80c737ff954/d5963243-262e-4398-b4d7-fb16d06484f6";
    private static final String UNKNOWN_KEY = "00000000-0000-0000-0000-000000000000/00000000-0000-0000-0000-000000000000";
    static final String TEXT = "Процедура А() Экспорт\n\tТиповой();\n\t// ++ уч\n\tНаш();\n\t// -- уч\nКонецПроцедуры\n";
    @TempDir Path tempDir;
    Path project;
    Path raw;
    Path output;

    @BeforeEach
    void prepare() throws Exception {
        project = TestUtils.copyConfiguration(tempDir);
        TestUtils.copyDirectory(TestUtils.CONFIGURATION, project.resolve("extensions/A"));
        Files.writeString(project.resolve("configuration/" + MODULE), TEXT);
        Files.writeString(project.resolve("extensions/A/" + MODULE), TEXT);
        raw = tempDir.resolve("raw.xml");
        output = tempDir.resolve("coverage.xml");
        writeRaw("source-v1/-/-/" + KEY, 4, "source-v1/QQ/-/" + KEY, 2);
    }

    @Test
    void duplicateModuleUuidWithinSourceIsRejectedBeforeWritingReport() throws Exception {
        Path modules = project.resolve("configuration/CommonModules");
        Path other = modules.resolve("ГлобальныйОбщийМодуль.xml");
        String original = Files.readString(other);
        String duplicated = original.replaceFirst("(<CommonModule uuid=\")[^\"]+",
                "$1" + KEY.substring(0, KEY.indexOf('/')));
        assertNotEquals(original, duplicated);
        Files.writeString(other, duplicated);

        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "--source", "=configuration"));
        assertTrue(error.toString().contains("Duplicate module key in source"), error.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void sourceMustBeDirectoryRatherThanMetadataFile() throws Exception {
        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "--source", "=configuration/Configuration.xml"));
        assertTrue(error.toString().contains("requires a configuration or extension directory"), error.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void namedSourceRejectsExistingDirectoriesWithoutRootMetadata() throws Exception {
        Files.createDirectory(project.resolve("empty"));
        for (String directory : List.of("empty", ".", "configuration/CommonModules")) {
            StringWriter error = new StringWriter();
            assertEquals(1, convert(error, "--source", "A=" + directory), directory);
            assertTrue(error.toString().contains("Cannot read configuration metadata for source 'A'"), error.toString());
            assertTrue(error.toString().contains(project.resolve(directory).normalize().toString()), error.toString());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void missingExtensionMetadataFailsConvertAndStrictValidateInEitherRootOrder() throws Exception {
        Files.createDirectory(project.resolve("empty"));
        for (String[] roots : List.of(new String[]{"=configuration", "A=empty"},
                new String[]{"A=empty", "=configuration"})) {
            StringWriter error = new StringWriter();
            Path report = tempDir.resolve("exclusions.json");
            assertEquals(1, convert(error, "--source", roots[0], "--source", roots[1],
                    "--exclusion-report", report.toString()));
            assertTrue(error.toString().contains("Cannot read configuration metadata for source 'A'"), error.toString());
            assertFalse(Files.exists(output));
            assertFalse(Files.exists(report));
            CommandLine validate = Coverage41C.getCommandLine();
            StringWriter validationError = new StringWriter();
            validate.setErr(new PrintWriter(validationError, true));
            assertEquals(1, validate.execute("validate", "-P", project.toString(),
                    "--source", roots[0], "--source", roots[1], "--strict"));
            assertTrue(validationError.toString().contains("Cannot read configuration metadata for source 'A'"),
                    validationError.toString());
        }
    }

    @Test
    void namedSourceRejectsUnreadableRootMetadataRatherThanTrustingFileName() throws Exception {
        for (String metadataFile : List.of("broken-designer/Configuration.xml",
                "broken-edt/src/Configuration/Configuration.mdo")) {
            Path metadata = project.resolve(metadataFile);
            Files.createDirectories(metadata.getParent());
            Files.writeString(metadata, "<broken");
            String directory = metadataFile.substring(0, metadataFile.indexOf('/'));
            StringWriter error = new StringWriter();
            assertEquals(1, convert(error, "--source", "A=" + directory));
            assertTrue(error.toString().contains("Cannot read configuration metadata for source 'A'"), error.toString());
            assertTrue(error.toString().contains("Caused by:"), error.toString());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void validConfigurationWithoutBslModulesIsAcceptedInBothFormats() throws Exception {
        Path designer = project.resolve("empty-designer/Configuration.xml");
        Files.createDirectories(designer.getParent());
        Files.writeString(designer, "<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\" version=\"2.9\">"
                + "<Configuration uuid=\"11111111-1111-1111-1111-111111111111\"><Properties>"
                + "<Name>Empty</Name></Properties><ChildObjects/></Configuration></MetaDataObject>");
        Path edt = project.resolve("empty-edt/src/Configuration/Configuration.mdo");
        Files.createDirectories(edt.getParent());
        Files.writeString(edt, "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\""
                + " uuid=\"22222222-2222-2222-2222-222222222222\"><name>Empty</name></mdclass:Configuration>");
        Files.writeString(raw, "<coverage version=\"1\"/>");
        for (String directory : List.of("empty-designer", "empty-edt")) {
            assertEquals(0, convert("--source", "A=" + directory));
            assertTrue(coverage(output).isEmpty());
            assertEquals(0, Coverage41C.getCommandLine().execute("validate", "-P", project.toString(),
                    "--source", "A=" + directory, "--strict"));
        }
    }

    @Test
    void namedSourceAcceptsEdtProjectRootWithNestedSrc() throws Exception {
        TestUtils.copyDirectory(Path.of("src/test/resources/edt/pc"), project.resolve("extensions/EDT"));
        assertEquals(0, convert("--source", "=configuration", "--source", "EDT=extensions/EDT"));
        assertTrue(coverage(output).containsKey("extensions/EDT/src/CommonModules/ПростойОбщийМодуль/Module.bsl"));
        assertEquals(0, Coverage41C.getCommandLine().execute("validate", "-P", project.toString(),
                "--source", "EDT=extensions/EDT", "--strict"));
    }

    @Test
    void malformedQualifiedRawKeysAreRejectedBeforeWritingReport() throws Exception {
        for (String key : List.of("source-v1/-/-/object", "source-v1//-/" + KEY,
                "source-v1/-//" + KEY, "source-v1/-/-//module", "source-v1/-/-/object/")) {
            writeRaw(key, 4, key, 2);
            StringWriter error = new StringWriter();
            assertEquals(1, convert(error, "--source", "=configuration"), key);
            assertTrue(error.toString().contains("Invalid raw source key"), error.toString());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void finishedCoverageWithTwoPartPathIsRejectedAsRaw() throws Exception {
        Files.writeString(raw, "<coverage version=\"1\"><file path=\"Ext/SessionModule.bsl\">"
                + "<lineToCover covered=\"true\" lineNumber=\"2\"/></file></coverage>");

        for (List<String> options : List.of(List.of("-s", "configuration"), List.of("--source", "=configuration"))) {
            StringWriter error = new StringWriter();
            assertEquals(1, convert(error, options.toArray(new String[0])), options.toString());
            assertTrue(error.toString().contains("Invalid RAW module path"), error.toString());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void malformedRawSourceIdentityIsRejectedWithBothSourceOptions() throws Exception {
        for (String prefix : List.of("source-v1/!/-/", "source-v1/-/!/", "source-v1/-/_w/")) {
            writeRaw(KEY, 4, prefix + KEY, 2);
            for (List<String> options : List.of(List.of("-s", "configuration"), List.of("--source", "=configuration"))) {
                StringWriter error = new StringWriter();
                assertEquals(1, convert(error, options.toArray(new String[0])), prefix + options);
                assertTrue(error.toString().contains("Invalid raw source key"), error.toString());
                assertFalse(Files.exists(output));
            }
        }
    }

    @Test
    void opaqueModuleIdsStillWorkWithRawCoverage() throws Exception {
        for (String key : List.of("sample-object_v1/module.2", "source-v1/-/-/sample-object_v1/module.bsl")) {
            writeRaw(key, 2, KEY, 4);

            assertEquals(0, convert("-s", "configuration"), key);
            assertEquals(true, coverage(output).get("configuration/" + MODULE).get(4));
        }
    }

    @Test
    void legacyProjectAndNestedSourceKeepRelativePathsAndConfiguration() throws Exception {
        Files.createDirectories(project.resolve("src_folder"));
        Files.move(project.resolve("configuration"), project.resolve("src_folder/main"));
        Path config = project.resolve("tools/JSON/coverage41c.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"exclude\":{\"paths\":[\"src_folder/main/" + MODULE
                + "\"]},\"changeMarkers\":{}}");
        writeRaw(KEY, 4, KEY, 2);

        assertEquals(0, convert("-s", "src_folder/main", "-r", "NOT_EDITABLE",
                "--config", config.toString()));

        Map<String, Map<Integer, Boolean>> result = coverage(output);
        assertEquals(Map.of(4, true), result.get("src_folder/main/" + MODULE));
        assertFalse(result.containsKey(MODULE));
        assertFalse(result.containsKey("configuration/" + MODULE));
    }

    @Test
    void legacySourceOnlyAndProjectOnlyUseSourceRootForReportPaths() throws Exception {
        writeRaw(KEY, 4, KEY, 2);
        for (String option : List.of("-s", "-P")) {
            assertEquals(0, Coverage41C.getCommandLine().execute("convert",
                    option, project.resolve("configuration").toString(),
                    "-c", raw.toString(), "-o", output.toString()));
            Map<String, Map<Integer, Boolean>> result = coverage(output);
            assertEquals(Map.of(2, true, 4, true, 6, false), result.get(MODULE), option);
            assertFalse(result.containsKey("configuration/" + MODULE), option);
        }
    }

    @Test
    void convertThreeSourcesKeepsCyrillicExtensionHitsSeparate() throws Exception {
        TestUtils.copyDirectory(TestUtils.CONFIGURATION, project.resolve("extensions/Б"));
        Files.writeString(project.resolve("extensions/Б/" + MODULE), TEXT);
        Files.writeString(raw, Files.readString(raw).replace("</coverage>",
                "<file path=\"/source-v1/0JE/-/" + KEY
                        + "\"><lineToCover covered=\"true\" lineNumber=\"6\"/></file></coverage>"));

        assertEquals(0, convert("--source", "Б=extensions/Б", "--source", "=configuration",
                "--source", "A=extensions/A"));

        Map<String, Map<Integer, Boolean>> result = coverage(output);
        assertEquals(Map.of(2, false, 4, true, 6, false), result.get("configuration/" + MODULE));
        assertEquals(Map.of(2, true, 4, false, 6, false), result.get("extensions/A/" + MODULE));
        assertEquals(Map.of(2, false, 4, false, 6, true), result.get("extensions/Б/" + MODULE));
    }

    @Test
    void namedSourcesRejectLegacyExtensionAndExternalFilters() throws Exception {
        for (String filter : List.of("-e", "-x")) {
            StringWriter error = new StringWriter();
            assertEquals(1, convert(error, "--source", "=configuration", filter, "Other"));
            assertTrue(error.toString().contains("--source cannot be combined"), error.toString());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void absoluteSourceOutsideProjectIsRejected() {
        assertSourceOutsideProjectIsRejected(tempDir.toString());
    }

    @Test
    void sourceOutsideProjectIsRejectedThroughSymlink() {
        createSymbolicLink(project.resolve("outside"), tempDir);
        assertSourceOutsideProjectIsRejected("outside");
    }

    private void assertSourceOutsideProjectIsRejected(String directory) {
        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "--source", "A=" + directory));
        assertTrue(error.toString().contains("Source directory must be inside -P"), error.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void samePhysicalSourceCannotBeRegisteredThroughSymlinkTwice() {
        createSymbolicLink(project.resolve("alias"), project.resolve("configuration"));
        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "--source", "=configuration", "--source", "A=alias"));
        assertTrue(error.toString().contains("Duplicate source directory"), error.toString());
        assertFalse(Files.exists(output));
    }

    private static void createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException error) {
            assumeTrue(false, () -> "Cannot create a symbolic link: " + error.getMessage());
        }
    }

    @Test
    void convertKeepsSourceHitsSeparateAndAppliesChangeMarkers() throws Exception {
        Path config = tempDir.resolve("coverage.json");
        Files.writeString(config, "{\"exclude\":{\"objects\":[\"CommonModule.ПростойОбщийМодуль\"]},\"changeMarkers\":{}}");
        Path report = tempDir.resolve("exclusions.json");
        assertEquals(0, convert("--source", "=configuration", "--source", "A=extensions/A",
                "--config", config.toString(), "--exclusion-report", report.toString()));
        Map<String, Map<Integer, Boolean>> result = coverage(output);
        assertEquals(Map.of(4, true), result.get("configuration/" + MODULE));
        assertEquals(Map.of(4, false), result.get("extensions/A/" + MODULE));
        JsonNode json = new ObjectMapper().readTree(report.toFile());
        assertEquals(2, json.get("includedLines").asInt());
        assertEquals(2, json.get("changeBlocks").get(0).get("modules").size());
        assertTrue(json.get("warnings").isEmpty(), json.get("warnings").toString());
    }

    @Test
    void prefixedMethodsApplyToEveryNamedSource() throws Exception {
        String text = "Процедура А() Экспорт\n\tТиповой();\nКонецПроцедуры\n\n"
                + "Процедура тестБ() Экспорт\n\tНаш();\nКонецПроцедуры\n";
        Files.writeString(project.resolve("configuration/" + MODULE), text);
        Files.writeString(project.resolve("extensions/A/" + MODULE), text);
        writeRaw("source-v1/-/-/" + KEY, 6, "source-v1/QQ/-/" + KEY, 2);
        Path config = tempDir.resolve("coverage.json");
        Files.writeString(config, "{\"includePrefix\":[\"тест\"],\"prefixScope\":{\"routines\":true}}");
        Path report = tempDir.resolve("exclusions.json");

        assertEquals(0, convert("--source", "=configuration", "--source", "A=extensions/A",
                "--config", config.toString(), "--exclusion-report", report.toString()));

        Map<String, Map<Integer, Boolean>> result = coverage(output);
        assertEquals(Map.of(6, true, 7, false), result.get("configuration/" + MODULE));
        assertEquals(Map.of(6, false, 7, false), result.get("extensions/A/" + MODULE));
        JsonNode json = new ObjectMapper().readTree(report.toFile());
        assertEquals(2, json.get("prefixRoutines").get(0).get("modules").size());
        assertEquals(4, json.get("prefixRoutines").get(0).get("includedLines").asInt());
        assertTrue(json.get("warnings").isEmpty(), json.get("warnings").toString());
    }

    @Test
    void emptyAmbiguousLegacyKeyDoesNotRequireSourceSelection() throws Exception {
        String emptyRecord = "<file path=\"" + KEY + "\"/>";
        String[] sources = {"--source", "=configuration", "--source", "A=extensions/A"};
        Map<Integer, Boolean> uncovered = Map.of(2, false, 4, false, 6, false);

        Files.writeString(raw, "<coverage>" + emptyRecord + "</coverage>");
        StringWriter error = new StringWriter();
        assertEquals(0, convert(error, sources), error.toString());
        Map<String, Map<Integer, Boolean>> result = coverage(output);
        assertEquals(uncovered, result.get("configuration/" + MODULE));
        assertEquals(uncovered, result.get("extensions/A/" + MODULE));

        String measurement = "<file path=\"" + SourceKey.qualify(KEY, "A", "") + "\">"
                + "<lineToCover covered=\"true\" lineNumber=\"4\"/></file>";
        Files.writeString(raw, "<coverage>" + emptyRecord + measurement + "</coverage>");
        error = new StringWriter();
        assertEquals(0, convert(error, sources), error.toString());
        result = coverage(output);
        assertEquals(uncovered, result.get("configuration/" + MODULE));
        assertEquals(Map.of(2, false, 4, true, 6, false), result.get("extensions/A/" + MODULE));
    }

    @Test
    void legacyKeyMustNotCoverTwoSourcesWithTheSameUuid() throws Exception {
        writeRaw(KEY, 4, KEY, 2);
        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "--source", "=configuration", "--source", "A=extensions/A"));
        assertTrue(error.toString().contains("Ambiguous legacy module key"), error.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void legacyExtensionSourcesGiveBareMeasurementsToTheTreePassedWithSrcDir() throws Exception {
        writeRaw(KEY, 4, KEY, 2);

        assertEquals(0, convert("-s", "configuration", "--extension", "A=extensions/A"));

        Map<String, Map<Integer, Boolean>> result = coverage(output);
        assertEquals(Map.of(2, true, 4, true, 6, false), result.get("configuration/" + MODULE));
        assertEquals(Map.of(2, false, 4, false, 6, false), result.get("extensions/A/" + MODULE));
    }

    @Test
    void emptyLegacyExtensionRecordDoesNotReclassifyBareMeasurements() throws Exception {
        Files.writeString(raw, "<coverage><file path=\"" + KEY + "\" extension=\"B\"/>"
                + "<file path=\"" + KEY + "\"><lineToCover covered=\"true\" lineNumber=\"4\"/></file></coverage>");

        StringWriter error = new StringWriter();
        assertEquals(0, convert(error, "-s", "extensions/A", "-e", "A", "--extension", "B=configuration"),
                error.toString());

        Map<String, Map<Integer, Boolean>> result = coverage(output);
        assertEquals(Map.of(2, false, 4, true, 6, false), result.get("extensions/A/" + MODULE));
        assertEquals(Map.of(2, false, 4, false, 6, false), result.get("configuration/" + MODULE));

        Files.delete(output);
        error = new StringWriter();
        assertEquals(1, convert(error, "--source", "A=extensions/A", "--source", "B=configuration"));
        assertTrue(error.toString().contains("Ambiguous legacy module key"), error.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void legacyKeyCountsProtectedModulesAsPotentialMatches() throws Exception {
        Path mainModule = project.resolve("configuration/" + MODULE);
        Files.delete(mainModule);
        Files.write(mainModule.resolveSibling("Module.bin"), new byte[]{1, 2, 3});
        writeRaw(KEY, 4, KEY, 2);

        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "--source", "=configuration", "--source", "A=extensions/A"));
        assertTrue(error.toString().contains("Ambiguous legacy module key"), error.toString());
        assertFalse(Files.exists(output));

        writeRaw(SourceKey.qualify(KEY, "", ""), 4, SourceKey.qualify(KEY, "A", ""), 2);
        assertEquals(0, convert("--source", "=configuration", "--source", "A=extensions/A"));
        Map<String, Map<Integer, Boolean>> modules = coverage(output);
        assertFalse(modules.containsKey("configuration/" + MODULE));
        assertEquals(Map.of(2, true, 4, false, 6, false), modules.get("extensions/A/" + MODULE));
    }

    @Test
    void legacyKeyStillWorksWhenOnlyOneSourceMatches() throws Exception {
        writeRaw(KEY, 4, KEY, 2);
        assertEquals(0, convert("--source", "A=extensions/A"));
        assertEquals(Map.of(2, true, 4, true, 6, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void qualifiedUnknownSourceDoesNotFallBackToMatchingUuid() throws Exception {
        writeRaw("source-v1/Qg/-/" + KEY, 4, "source-v1/Qg/-/" + KEY, 2);
        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "--source", "=configuration", "--source", "A=extensions/A"));
        assertTrue(error.toString().contains("--source B=DIR"), error.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void namedSourceRequiresMatchingRawAttributesEvenWithKnownModuleUuid() throws Exception {
        Files.writeString(raw, "<coverage sourceIdentity=\"true\"><file path=\"/" + KEY
                + "\" extension=\"A\"><lineToCover covered=\"true\" lineNumber=\"2\"/></file></coverage>");
        assertEquals(1, convert("--source", "=configuration"));
        assertFalse(Files.exists(output));
        assertEquals(0, convert("--source", "A=extensions/A"));
        assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void namedSourceCanMatchWithoutAnyKnownModuleOrHit() throws Exception {
        writeRaw(SourceKey.qualify(UNKNOWN_KEY, "A", ""), 4, SourceKey.qualify(KEY, "B", ""), 2);
        assertEquals(0, convert("--source", "A=extensions/A"));
        assertEquals(Map.of(2, false, 4, false, 6, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void moduleUuidsIgnoreCaseInLegacyAndQualifiedRaw() throws Exception {
        String upper = KEY.toUpperCase(Locale.ROOT);
        writeRaw(upper, 2, upper, 4);
        assertEquals(0, convert("-s", "configuration"));
        assertEquals(Map.of(2, true, 4, true, 6, false), coverage(output).get("configuration/" + MODULE));
        writeRaw(SourceKey.qualify(upper, "A", ""), 2, SourceKey.qualify(upper, "a", ""), 4);
        assertEquals(0, convert("--source", "A=extensions/A"));
        assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void moduleKeysKeepOpaqueComponentsCaseSensitive() {
        String object = KEY.substring(0, KEY.indexOf('/'));
        String property = KEY.substring(KEY.indexOf('/') + 1);
        assertEquals(object + "/Opaque", SourceKey.moduleKey(object.toUpperCase(Locale.ROOT), "Opaque"));
        assertEquals("Opaque/" + property, SourceKey.moduleKey("Opaque", property.toUpperCase(Locale.ROOT)));
    }

    @Test
    void extensionSourceListRejectsForeignRawAndKeepsPartialMatches() throws Exception {
        writeRaw(SourceKey.qualify(KEY, "B", ""), 2, SourceKey.qualify(KEY, "B", ""), 4);
        assertEquals(1, convert("-s", "configuration", "--extension", "A=extensions/A"));
        assertFalse(Files.exists(output));
        writeRaw(SourceKey.qualify(KEY, "A", ""), 2, SourceKey.qualify(KEY, "B", ""), 4);
        assertEquals(0, convert("-s", "configuration", "--extension", "A=extensions/A"));
        assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void singleRootNewRawDataUsesOnlyTheSelectedSource() throws Exception {
        assertEquals(0, convert("-s", "configuration"));
        assertEquals(Map.of(2, false, 4, true, 6, false), coverage(output).get("configuration/" + MODULE));
        assertEquals(0, convert("-s", "extensions/A", "-e", "A"));
        assertEquals(Map.of(2, true, 4, false, 6, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void selectedSourceCanContainOnlyModulesMissingFromTheExport() throws Exception {
        writeRaw(SourceKey.qualify(UNKNOWN_KEY, "A", ""), 4, SourceKey.qualify(KEY, "B", ""), 2);

        assertEquals(0, convert("-s", "extensions/A", "-e", "A"));
        assertEquals(Map.of(2, false, 4, false, 6, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void wrongSourceWithoutMatchingModuleKeysStillFailsWithHint() throws Exception {
        writeRaw(SourceKey.qualify(UNKNOWN_KEY, "B", ""), 4, SourceKey.qualify(UNKNOWN_KEY, "B", ""), 2);

        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "-s", "extensions/A", "-e", "A"));
        assertTrue(error.toString().contains("-e B"), error.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void rawExtensionWithoutSelectorFailsWithHintBeforeWritingReport() throws Exception {
        writeRaw(SourceKey.qualify(KEY, "Расширение", ""), 2,
                SourceKey.qualify(KEY, "Расширение", ""), 4);
        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "-s", "extensions/A"));
        assertTrue(error.toString().contains("-e Расширение"), error.toString());
        assertFalse(Files.exists(output));
        assertEquals(0, convert("-s", "extensions/A", "-e", "Расширение"));
        assertEquals(Map.of(2, true, 4, true, 6, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void wrongExtensionSelectorFailsWithActualSourceHint() throws Exception {
        for (String source : List.of("A", "")) {
            writeRaw(SourceKey.qualify(KEY, source, ""), 2, SourceKey.qualify(KEY, source, ""), 4);
            StringWriter error = new StringWriter();
            assertEquals(1, convert(error, "-s", "extensions/A", "-e", "Other"));
            String hint = source.isEmpty() ? "omit -e and -x" : "-e A";
            assertTrue(error.toString().contains(hint), error.toString());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void rawExternalSourceWithoutSelectorFailsWithUrlHint() throws Exception {
        String url = "file:///work/Обработка.epf";
        writeRaw(SourceKey.qualify(KEY, "", url), 2, SourceKey.qualify(KEY, "", url), 4);
        StringWriter error = new StringWriter();
        assertEquals(1, convert(error, "-s", "configuration"));
        assertTrue(error.toString().contains("-x " + url), error.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void mixedRawWarnsAboutIgnoredSourceWithoutMixingItsHits() throws Exception {
        String log = captureConvertLog("-s", "configuration");
        assertTrue(log.contains("-e A"), log);
        assertTrue(log.contains("Ignoring RAW source"), log);
        assertEquals(Map.of(2, false, 4, true, 6, false), coverage(output).get("configuration/" + MODULE));
    }

    @Test
    void rootEntirelyOutsideScopeIsNamedInLogEvenWithChangeBlocks() throws Exception {
        prepareDifferentSubsystems();
        Path config = tempDir.resolve("coverage.json");
        Files.writeString(config, "{\"includeSubsystems\":[\"ПерваяПодсистема\"],\"changeMarkers\":{}}");
        String log = captureConvertLog("--source", "=configuration", "--source", "A=extensions/A",
                "--config", config.toString());
        assertTrue(log.contains("Source 'A' has no modules in coverage scope"), log);
        assertTrue(log.contains(project.resolve("extensions/A").toString()), log);
        assertEquals(Map.of(4, false), coverage(output).get("extensions/A/" + MODULE));
    }

    @Test
    void configLoadWarningIsReportedOnceForAllRoots() throws Exception {
        Path config = tempDir.resolve("coverage.json");
        Files.writeString(config, "{\"unexpected\":true,\"auto\":{\"objects\":[\"CommonModule.ПростойОбщийМодуль\"]}}");
        Path report = tempDir.resolve("exclusions.json");
        String log = captureConvertLog("--source", "=configuration", "--source", "A=extensions/A",
                "--config", config.toString(), "--exclusion-report", report.toString());
        String warning = "coverage.json: unknown key unexpected";
        assertEquals(1, log.lines().filter(line -> line.contains(warning)).count(), log);
        JsonNode warnings = new ObjectMapper().readTree(report.toFile()).get("warnings");
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals(warning, warnings.get(0).asText());
    }

    private String captureConvertLog(String... options) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try (PrintStream capture = new PrintStream(log, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            assertEquals(0, convert(options));
        } finally {
            System.setErr(original);
        }
        return log.toString(StandardCharsets.UTF_8);
    }

    @Test
    void ruleMatchingOnlyOneRootDoesNotWarnAboutTheOtherRoot() throws Exception {
        Path config = tempDir.resolve("coverage.json");
        Files.writeString(config, "{\"exclude\":{\"paths\":[\"extensions/**\"]},\"changeMarkers\":{}}");
        Path report = tempDir.resolve("exclusions.json");
        assertEquals(0, convert("--source", "=configuration", "--source", "A=extensions/A",
                "--config", config.toString(), "--exclusion-report", report.toString()));
        assertEquals(Map.of(2, false, 4, true, 6, false), coverage(output).get("configuration/" + MODULE));
        assertEquals(Map.of(4, false), coverage(output).get("extensions/A/" + MODULE));
        assertTrue(new ObjectMapper().readTree(report.toFile()).get("warnings").isEmpty());
    }

    @Test
    void exactScopeMayExistInOnlyOneSourceInEitherOrder() throws Exception {
        prepareDifferentSubsystems();
        Path config = tempDir.resolve("coverage.json");
        Files.writeString(config, "{\"includeSubsystems\":[\"ПерваяПодсистема\"],\"changeMarkers\":{}}");
        for (String[] roots : List.of(new String[]{"=configuration", "A=extensions/A"},
                new String[]{"A=extensions/A", "=configuration"})) {
            StringWriter error = new StringWriter();
            assertEquals(0, convert(error, "--source", roots[0], "--source", roots[1], "--config", config.toString()),
                    error.toString());
            assertEquals(Map.of(2, false, 4, true, 6, false), coverage(output).get("configuration/" + MODULE));
            assertEquals(Map.of(4, false), coverage(output).get("extensions/A/" + MODULE));
            assertEquals(0, Coverage41C.getCommandLine().execute("validate", "-P", project.toString(),
                    "--source", roots[0], "--source", roots[1], "--config", config.toString(), "--strict"));
        }
    }

    @Test
    void exactExcludeAndAutoAffectOnlyTheSourceContainingSubsystem() throws Exception {
        prepareDifferentSubsystems();
        for (String section : List.of("exclude", "auto")) {
            Path config = tempDir.resolve("coverage.json");
            Files.writeString(config, "{\"" + section + "\":{\"subsystems\":[\"ПерваяПодсистема\"]}}");
            StringWriter error = new StringWriter();
            assertEquals(0, convert(error, "--source", "A=extensions/A", "--source", "=configuration",
                    "--config", config.toString()), error.toString());
            Map<String, Map<Integer, Boolean>> result = coverage(output);
            if (section.equals("exclude")) {
                assertFalse(result.containsKey("configuration/" + MODULE));
            } else {
                assertEquals(Map.of(4, true), result.get("configuration/" + MODULE));
            }
            assertEquals(Map.of(2, true, 4, false, 6, false), result.get("extensions/A/" + MODULE));
        }
    }

    @Test
    void unknownExactSubsystemStillFailsWhenAbsentFromAllSources() throws Exception {
        for (String section : List.of("includeSubsystems", "exclude", "auto")) {
            Path config = tempDir.resolve("coverage.json");
            String list = "[\"Несуществующая\"]";
            Files.writeString(config, section.equals("includeSubsystems")
                    ? "{\"includeSubsystems\":" + list + "}"
                    : "{\"" + section + "\":{\"subsystems\":" + list + "}}");
            StringWriter error = new StringWriter();
            assertEquals(1, convert(error, "--source", "=configuration", "--source", "A=extensions/A",
                    "--config", config.toString()));
            assertTrue(error.toString().contains("Unknown subsystem: Несуществующая"), error.toString());
            assertFalse(Files.exists(output));
        }
    }

    private void prepareDifferentSubsystems() throws Exception {
        Path subsystem = project.resolve("configuration/Subsystems/ПерваяПодсистема.xml");
        Files.writeString(subsystem, Files.readString(subsystem).replace("</Content>",
                "<xr:Item xsi:type=\"xr:MDObjectRef\">CommonModule.ПростойОбщийМодуль</xr:Item></Content>"));
        Path extension = project.resolve("extensions/A/Configuration.xml");
        Files.writeString(extension, Files.readString(extension).replace("<Subsystem>ПерваяПодсистема</Subsystem>", ""));
        try (Stream<Path> paths = Files.walk(project.resolve("extensions/A/Subsystems"))) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }

    @Test
    void rejectsConflictingOrDuplicateSourceDefinitions() throws Exception {
        for (String[] options : List.of(
                new String[]{"--source", "configuration"},
                new String[]{"--source", "A=configuration", "--source", "A=extensions/A"},
                new String[]{"--source", "=configuration", "--source", "A=configuration"},
                new String[]{"--source", "=configuration", "-s", "configuration"},
                new String[]{"--source", "A="},
                new String[]{"--source", "A=missing"})) {
            assertEquals(1, convert(options), Arrays.toString(options));
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void allFormatsKeepBothPathsAndUncoveredLines() throws Exception {
        assertEquals(0, convert("--source", "=configuration", "--source", "A=extensions/A", "-f", "LCOV"));
        List<List<String>> lcovRecords = Arrays.stream(Files.readString(output).split("end_of_record"))
                .map(record -> record.lines().toList()).toList();
        List<String> paths = List.of("configuration/" + MODULE, "extensions/A/" + MODULE);
        for (String path : paths) {
            List<String> record = lcovRecords.stream().filter(lines -> lines.contains("SF:" + path))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing LCOV record for " + path));
            assertTrue(record.contains("DA:6,0"), path);
        }

        assertEquals(0, convert("--source", "=configuration", "--source", "A=extensions/A", "-f", "COBERTURA"));
        NodeList classes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
                .getElementsByTagName("class");
        Map<String, String> hitsByPath = new LinkedHashMap<>();
        for (int i = 0; i < classes.getLength(); i++) {
            Element coberturaClass = (Element) classes.item(i);
            NodeList lines = coberturaClass.getElementsByTagName("line");
            for (int j = 0; j < lines.getLength(); j++) {
                Element line = (Element) lines.item(j);
                if ("6".equals(line.getAttribute("number"))) {
                    hitsByPath.put(coberturaClass.getAttribute("filename"), line.getAttribute("hits"));
                }
            }
        }
        for (String path : paths) {
            assertEquals("0", hitsByPath.get(path), path);
        }
    }

    void writeRaw(String first, int firstLine, String second, int secondLine) throws Exception {
        Files.writeString(raw, "<coverage version=\"1\"><file path=\"/" + first
                + "\"><lineToCover covered=\"true\" lineNumber=\"" + firstLine + "\"/></file><file path=\"/" + second
                + "\"><lineToCover covered=\"true\" lineNumber=\"" + secondLine + "\"/></file></coverage>");
    }

    int convert(String... options) { return convert(new StringWriter(), options); }

    int convert(StringWriter error, String... options) {
        List<String> args = new ArrayList<>(List.of("convert", "-P", project.toString(), "-c", raw.toString(), "-o", output.toString()));
        args.addAll(Arrays.asList(options));
        CommandLine command = Coverage41C.getCommandLine();
        command.setErr(new PrintWriter(error, true));
        return command.execute(args.toArray(new String[0]));
    }

    static Map<String, Map<Integer, Boolean>> coverage(Path file) throws Exception {
        NodeList files = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getElementsByTagName("file");
        Map<String, Map<Integer, Boolean>> result = new LinkedHashMap<>();
        for (int i = 0; i < files.getLength(); i++) {
            Element element = (Element) files.item(i);
            Map<Integer, Boolean> lines = new LinkedHashMap<>();
            NodeList lineNodes = element.getElementsByTagName("lineToCover");
            for (int j = 0; j < lineNodes.getLength(); j++) {
                Element line = (Element) lineNodes.item(j);
                lines.put(Integer.valueOf(line.getAttribute("lineNumber")), Boolean.valueOf(line.getAttribute("covered")));
            }
            assertNull(result.put(element.getAttribute("path"), lines), "Duplicate file in coverage");
        }
        return result;
    }
}
