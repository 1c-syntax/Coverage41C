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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

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
    void rejectsGenericCoverageXmlAsRaw() throws Exception {
        assertRejectedBeforePublishing("<coverage><file path=\"Ext/Module.BSL\"/></coverage>");
    }

    @Test
    void rejectsCoberturaXmlAsRaw() throws Exception {
        assertRejectedBeforePublishing("<coverage><packages/></coverage>");
    }

    @Test
    void rejectsUnrelatedXmlAsRaw() throws Exception {
        assertRejectedBeforePublishing("<report/>");
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
