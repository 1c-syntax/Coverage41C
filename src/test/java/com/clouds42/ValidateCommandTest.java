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

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidateCommandTest {

    private static final File CONFIGURATION = new File("src/test/resources/configuration");
    private static final String SIMPLE_MODULE = "configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";

    @TempDir
    Path tempDir;

    @Test
    void passesCleanSourcesInStrictMode() {
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, validate(stderr, "--strict"), stderr.toString());
    }

    @Test
    void passesValidConfigInStrictMode() throws Exception {
        File config = config("{'exclude':{'objects':['Catalog.Справочник1']}}");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, validate(stderr, "--config", config.getPath(), "--strict"),
                stderr.toString());
    }

    @Test
    void passesConfigWithWarningsWithoutStrict() throws Exception {
        File config = config("{'exclude':{'objects':['Catalog.Нет']}}");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, validate(stderr, "--config", config.getPath()), stderr.toString());
    }

    @Test
    void failsOnWarningsInStrictMode() throws Exception {
        File config = config("{'exclude':{'objects':['Catalog.Нет']}}");

        assertEquals(CommandLine.ExitCode.SOFTWARE,
                validate(new StringWriter(), "--config", config.getPath(), "--strict"));
    }

    @Test
    void failsOnBrokenConfig() throws Exception {
        File config = config("{'exclude':{'lines':['(']}}");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, validate(stderr, "--config", config.getPath()));
        assertTrue(stderr.toString().contains("team.json → exclude.lines[0]: invalid regular expression"),
                stderr.toString());
    }

    @Test
    void writesExclusionReport() throws Exception {
        File config = config("{'exclude':{'objects':['Catalog.Справочник1']}}");
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = validate(stderr, "--config", config.getPath(), "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertEquals(1, root.get("entries").size());
        assertEquals("team.json → exclude.objects[0]", root.get("entries").get(0).get("source").asText());
    }

    @Test
    void writesExclusionReportFromConfigRelativeToProjectDirectory() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path config = project.resolve("tools/conf/team.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"exclusionReport\":\"out/exclusions.json\","
                + "\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, validateProject(project, stderr, "--config", config.toString()),
                stderr.toString());
        JsonNode root = new ObjectMapper().readTree(project.resolve("out/exclusions.json").toFile());
        assertEquals("team.json → exclude.objects[0]", root.get("entries").get(0).get("source").asText());
        assertFalse(Files.exists(config.getParent().resolve("out")));
    }

    @Test
    void rejectsCommandLineExclusionReportInsideConfigDirectory() throws Exception {
        Path config = tempDir.resolve("conf/team.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]}}");
        Path report = tempDir.resolve("conf/exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = validate(stderr, "--config", config.getParent().toString(),
                "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode, stderr.toString());
        assertTrue(stderr.toString().contains("--exclusion-report: " + report.toAbsolutePath()
                + " is inside the configuration directory and would be read as a config on the next run"),
                stderr.toString());
        assertFalse(Files.exists(report));
    }

    @Test
    void commandLineExclusionReportOverridesConfig() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        File config = config("{'exclusionReport':'from-config.json','exclude':{'objects':['Catalog.Справочник1']}}");
        Path report = tempDir.resolve("from-cli.json");
        StringWriter stderr = new StringWriter();

        int exitCode = validateProject(project, stderr, "--config", config.getPath(),
                "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        assertTrue(Files.exists(report));
        assertFalse(Files.exists(project.resolve("from-config.json")));
    }

    @Test
    void failsInStrictModeOnUnclosedChangeBlock() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE), "Процедура А()\n\t// ++ уч\n\tБ();\nКонецПроцедуры\n");
        File config = config("{'changeMarkers':{}}");
        Path report = tempDir.resolve("exclusions.json");

        int exitCode = validateProject(project, new StringWriter(), "--config", config.getPath(), "--strict",
                "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(warnings(report).contains(SIMPLE_MODULE + ":2 → team.json → changeMarkers[0]: "
                + "block is not closed, not counted"), warnings(report).toString());
    }

    @Test
    void enabledAutomaticClosurePassesStrictAndReportsBoundary() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE), "Процедура А()\n//++\nБ();\nКонецПроцедуры\n");
        File config = config("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}");
        Path report = tempDir.resolve("automatic.json");
        assertEquals(CommandLine.ExitCode.OK, validateProject(project, new StringWriter(), "--config",
                config.getPath(), "--strict", "--exclusion-report", report.toString()));
        assertTrue(warnings(report).isEmpty());
        JsonNode block = new ObjectMapper().readTree(report.toFile()).path("automaticallyClosedBlocks").get(0);
        assertEquals(SIMPLE_MODULE, block.path("module").asText());
        assertEquals(2, block.path("beginLine").asInt());
        assertEquals(4, block.path("endLine").asInt());
        assertEquals("method", block.path("boundary").asText());
    }

    @Test
    void enabledAutomaticClosureDoesNotHideUnmatchedEndInStrictMode() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE), "//--\nПроцедура А()\n//++\nБ();\nКонецПроцедуры\n");
        File config = config("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}");
        Path report = tempDir.resolve("automatic.json");
        assertEquals(CommandLine.ExitCode.SOFTWARE, validateProject(project, new StringWriter(), "--config",
                config.getPath(), "--strict", "--exclusion-report", report.toString()));
        assertTrue(warnings(report).stream().anyMatch(warning -> warning.contains("end without begin")));
    }

    @Test
    void warnsAboutChangeMarkersWithoutBlocks() throws Exception {
        File config = config("{'changeMarkers':{}}");
        Path report = tempDir.resolve("exclusions.json");

        int exitCode = validate(new StringWriter(), "--config", config.getPath(),
                "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        assertEquals(List.of("team.json → changeMarkers[0]: rule does not match anything"), warnings(report));
    }

    @Test
    void logsChangeBlocksSummary() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE),
                "Процедура А()\n\tБ();\n\t// ++ уч\n\tВ();\n\t// -- уч\nКонецПроцедуры\n");
        File config = config("{'includeSubsystems':['ПодчиненнаяПодсистема'],'changeMarkers':{}}");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            exitCode = validateProject(project, new StringWriter(), "--config", config.getPath());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        String output = log.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Change blocks: 1 line(s) returned to coverage from 1 block(s) in 1 module(s)"),
                output);
        assertTrue(output.contains("  1 lines: team.json → changeMarkers[0]"), output);
    }

    @Test
    void inlineEndMarkerDoesNotWarnAndCountsItsLine() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE),
                "Процедура А()\n\tБ();\n\t// ++ уч\n\tВ();\nКонецПроцедуры // -- уч\n");
        File config = config("{'includeSubsystems':['ПодчиненнаяПодсистема'],'changeMarkers':{}}");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            exitCode = validateProject(project, new StringWriter(), "--config", config.getPath());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        String output = log.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Change blocks: 2 line(s) returned to coverage from 1 block(s) in 1 module(s)"),
                output);
        assertFalse(output.contains("block is not closed"), output);
    }

    @Test
    void requiresSources() {
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND, "--strict");

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Validation requires source files"), stderr.toString());
    }

    @Test
    void skipsModuleWithoutSourceTextWithItsPath() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path module = project.resolve("configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl");
        Files.write(module, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0x00, 0x02, 0x0D, 0x0A});
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            exitCode = validateProject(project, new StringWriter(), "--strict");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        String output = log.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Skipping module without source text: "
                + "configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl"), output);
    }

    @Test
    void failsInStrictModeOnModuleThatCannotBeRead() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Path module = project.resolve("configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl");
        Files.write(module, "Процедура А()\n\tБ();\nКонецПроцедуры\n".getBytes(Charset.forName("windows-1251")));
        Path report = tempDir.resolve("exclusions.json");

        int exitCode = validateProject(project, new StringWriter(), "--strict", "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        String warning = new ObjectMapper().readTree(report.toFile()).get("warnings").get(0).asText();
        assertTrue(warning.startsWith("configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl: cannot read module: "),
                warning);
    }

    @Test
    void failsInStrictModeOnUnclosedMarker() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE), "Процедура А()\n\t// Cover:off\n\tБ();\nКонецПроцедуры\n");

        assertEquals(CommandLine.ExitCode.SOFTWARE, validateProject(project, new StringWriter(), "--strict"));
    }

    @Test
    void markersInsideModuleExcludedByAnotherTeamPassStrictMode() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE),
                "Процедура А()\n\t// Cover:off\n\tБ();\n\t// Cover:on\n\t// Cover:auto\n\tВ();\n\t// Cover:on\nКонецПроцедуры\n");
        File config = config("{'team':'Склад','exclude':{'objects':['CommonModule.ПростойОбщийМодуль']}}");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK,
                validateProject(project, stderr, "--config", config.getPath(), "--strict"), stderr.toString());
    }

    @Test
    void ruleMatchingOnlyModulesOutsideScopeIsNotWarned() throws Exception {
        File config = config("{'includeSubsystems':['ПодчиненнаяПодсистема'],"
                + "'exclude':{'objects':['Document.Документ1','Catalog.Нет']}}");
        Path report = tempDir.resolve("exclusions.json");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            exitCode = validate(new StringWriter(), "--config", config.getPath(), "--exclusion-report", report.toString());
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        assertEquals(List.of("team.json → exclude.objects[1]: rule does not match anything"),
                warnings(report));
        String output = log.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("INFO com.clouds42.Utils - team.json → exclude.objects[0]: "
                + "rule matches only modules outside coverage scope"), output);
    }

    @Test
    void logsSubsystemsSelectedByMaskWithoutNestedOnes() throws Exception {
        File config = config("{'includeSubsystems':['*Подсистема']}");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            exitCode = validate(new StringWriter(), "--config", config.getPath(), "--strict");
        } finally {
            System.setErr(originalErr);
        }

        String output = log.toString(StandardCharsets.UTF_8);
        assertEquals(CommandLine.ExitCode.OK, exitCode, output);
        assertTrue(output.contains("INFO com.clouds42.ExclusionReport - team.json → includeSubsystems[0]: "
                + "*Подсистема matches 1 subsystem(s): ПерваяПодсистема"), output);
    }

    @Test
    void subsystemMasksMatchingNothingAreWarnings() throws Exception {
        File config = config("{'includeSubsystems':['Перв*','DEMO_*'],'exclude':{'subsystems':['demo*']}}");
        Path report = tempDir.resolve("exclusions.json");

        int exitCode = validate(new StringWriter(), "--config", config.getPath(), "--exclusion-report",
                report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        assertEquals(List.of("team.json → includeSubsystems[1]: rule does not match anything",
                "team.json → exclude.subsystems[0]: rule does not match anything"), warnings(report));
        assertEquals(CommandLine.ExitCode.SOFTWARE,
                validate(new StringWriter(), "--config", config.getPath(), "--strict"));
    }

    @Test
    void includePrefixLogsMatchesAndWarnsAboutPrefixMatchingNothing() throws Exception {
        File config = config("{'includePrefix':['Документ','Подч','DEMO_']}");
        Path report = tempDir.resolve("exclusions.json");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            exitCode = validate(new StringWriter(), "--config", config.getPath(), "--exclusion-report",
                    report.toString());
        } finally {
            System.setErr(originalErr);
        }

        String output = log.toString(StandardCharsets.UTF_8);
        assertEquals(CommandLine.ExitCode.OK, exitCode, output);
        assertEquals(List.of("team.json → includePrefix[2]: rule does not match anything"), warnings(report));
        assertTrue(output.contains("INFO com.clouds42.ExclusionReport - team.json → includePrefix[0]: "
                + "Документ matches 4 module(s)"), output);
        assertTrue(output.contains("INFO com.clouds42.ExclusionReport - team.json → includePrefix[1]: "
                + "Подч matches 1 subsystem(s): ПерваяПодсистема.ПодчиненнаяПодсистема"), output);
    }

    @Test
    void warnsAboutEmptyCoverageScope() throws Exception {
        File config = config("{'includeSubsystems':['DEMO_*'],'includePrefix':['DEMO_']}");
        Path report = tempDir.resolve("exclusions.json");

        int exitCode = validate(new StringWriter(), "--config", config.getPath(), "--exclusion-report",
                report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        assertEquals(List.of("team.json → includeSubsystems[0]: rule does not match anything",
                "team.json → includePrefix[0]: rule does not match anything",
                "Coverage scope is empty: no module matches includeSubsystems or includePrefix"), warnings(report));
    }

    static final String ROUTINE_MODULE_TEXT = String.join("\n", "Перем Счётчик;", "Процедура Типовая() Экспорт",
            "\tБ();", "КонецПроцедуры", "", "Процедура тестОбработать(Документ) Экспорт", "\tВ();", "\tГ();",
            "КонецПроцедуры", "", "Функция тестПолучить()", "\tВозврат 1;", "КонецФункции", "", "Д();") + "\n";

    @Test
    void logsEffectivePrefixScopeWithSource() throws Exception {
        File config = config("{'includePrefix':['Документ'],'prefixScope':{'includeSubsystemContents':false}}");
        Path report = tempDir.resolve("exclusions.json");

        String output = captureLog(() -> assertEquals(CommandLine.ExitCode.OK, validate(new StringWriter(),
                "--config", config.getPath(), "--exclusion-report", report.toString())));

        assertTrue(output.contains("INFO com.clouds42.ExclusionReport - Prefix scope: subsystems=true, "
                + "includeSubsystemContents=false, modules=true, routines=false (team.json → prefixScope)"), output);
        JsonNode scope = new ObjectMapper().readTree(report.toFile()).get("scope").get("prefixScope");
        assertFalse(scope.get("includeSubsystemContents").asBoolean());
        assertEquals("team.json → prefixScope", scope.get("sources").get(0).asText());
    }

    @Test
    void logsDefaultPrefixScopeWhenIncludePrefixIsSet() throws Exception {
        File config = config("{'includePrefix':['Документ']}");

        String output = captureLog(() -> validate(new StringWriter(), "--config", config.getPath()));

        assertTrue(output.contains("Prefix scope: subsystems=true, includeSubsystemContents=true, modules=true, "
                + "routines=false (default)"), output);
    }

    @Test
    void subsystemMatchWithoutContentsIsLoggedButIncludesNothing() throws Exception {
        File config = config("{'includePrefix':['Подч'],'prefixScope':{'includeSubsystemContents':false}}");
        Path report = tempDir.resolve("exclusions.json");

        String output = captureLog(() -> validate(new StringWriter(), "--config", config.getPath(),
                "--exclusion-report", report.toString()));

        assertTrue(output.contains("team.json → includePrefix[0]: Подч matches 1 subsystem(s) (contents not included): "
                + "ПерваяПодсистема.ПодчиненнаяПодсистема"), output);
        assertEquals(List.of("team.json → includePrefix[0]: rule does not match anything",
                "Coverage scope is empty: no module matches includeSubsystems or includePrefix"), warnings(report));
    }

    @Test
    void prefixedMethodsCountAsMatchAndFillScope() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE), ROUTINE_MODULE_TEXT);
        File config = config("{'includePrefix':['тест'],'prefixScope':{'routines':true}}");
        Path report = tempDir.resolve("exclusions.json");

        String output = captureLog(() -> assertEquals(CommandLine.ExitCode.OK, validateProject(project,
                new StringWriter(), "--config", config.getPath(), "--strict", "--exclusion-report", report.toString())));

        assertEquals(List.of(), warnings(report));
        assertTrue(output.contains("team.json → includePrefix[0]: тест matches 2 method(s) in 1 module(s)"), output);
        assertFalse(output.contains("тест matches 0 module(s)"), output);
        assertTrue(output.contains("Prefix routines: 5 line(s) in 2 method(s) of 1 module(s)"), output);
        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertEquals(5, root.get("totalLines").asInt());
        assertEquals(SIMPLE_MODULE, root.get("prefixRoutines").get(0).get("modules").get(0).asText());
        assertTrue(root.get("scope").get("modulesOutOfScope").asInt() > 0);
    }

    @Test
    void overlappingPrefixesMatchWithoutDoublingRoutineTotals() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE),
                "Процедура тестРассчитать()\n\tРассчитать();\nКонецПроцедуры\n");
        File config = config("{'includePrefix':['тест','тестРас'],'prefixScope':"
                + "{'subsystems':false,'modules':false,'routines':true}}");
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        String output = captureLog(() -> assertEquals(CommandLine.ExitCode.OK, validateProject(project, stderr,
                "--config", config.getPath(), "--strict", "--exclusion-report", report.toString()), stderr.toString()));

        assertEquals(List.of(), warnings(report));
        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertEquals(2, root.get("totalLines").asInt());
        JsonNode routines = root.get("prefixRoutines");
        assertEquals(2, routines.size());
        assertEquals(1, routines.get(0).get("routines").asInt());
        assertEquals(1, routines.get(1).get("routines").asInt());
        assertEquals(2, routines.get(0).get("includedLines").asInt());
        assertEquals(2, routines.get(1).get("includedLines").asInt());
        assertTrue(output.contains("Prefix routines: 2 line(s) in 1 method(s) of 1 module(s)"), output);
    }

    @Test
    void conflictingPrefixScopeInCatalogFails() throws Exception {
        Path directory = tempDir.resolve("configs");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("a.json"), "{\"includePrefix\":[\"тест\"],\"prefixScope\":{\"routines\":true}}");
        Files.writeString(directory.resolve("b.json"),
                "{\"exclude\":{\"objects\":[\"Catalog.Справочник1\"]},\"prefixScope\":{\"routines\":false}}");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.SOFTWARE, validate(stderr, "--config", directory.toString()));
        assertTrue(stderr.toString().contains("prefixScope.routines: true in a.json conflicts with false in b.json"),
                stderr.toString());
    }

    @Test
    void prefixScopeWithoutIncludePrefixIsWarningAndFailsStrict() throws Exception {
        File config = config("{'exclude':{'objects':['Catalog.Справочник1']},'prefixScope':{'routines':true}}");
        Path report = tempDir.resolve("exclusions.json");

        assertEquals(CommandLine.ExitCode.OK, validate(new StringWriter(), "--config", config.getPath(),
                "--exclusion-report", report.toString()));
        assertEquals(List.of("team.json → prefixScope: no effect without includePrefix"), warnings(report));
        assertEquals(CommandLine.ExitCode.SOFTWARE, validate(new StringWriter(), "--config", config.getPath(), "--strict"));
    }

    @Test
    void removeSupportBeatsPrefixedMethods() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(SIMPLE_MODULE), ROUTINE_MODULE_TEXT);
        Files.writeString(project.resolve("configuration/Ext/ParentConfigurations.bin"),
                "{6,0,1,46c7c1d0-b04d-4295-9b04-ae3207c18d29,0,11111111-1111-1111-1111-111111111111,"
                        + "\"1.0\",\"Вендор\",\"Поставка\",1,"
                        + "0,0,1be4af7e-334e-49fa-a9f9-d80c737ff954,1be4af7e-334e-49fa-a9f9-d80c737ff954,0}");
        File config = config("{'includePrefix':['тест'],'prefixScope':{'routines':true}}");
        Path report = tempDir.resolve("exclusions.json");

        assertEquals(CommandLine.ExitCode.OK, validateProject(project, new StringWriter(), "-r", "NOT_EDITABLE",
                "--config", config.getPath(), "--exclusion-report", report.toString()));

        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertEquals(0, root.get("totalLines").asInt());
        assertEquals(0, root.get("prefixRoutines").size());
        assertEquals(List.of("team.json → includePrefix[0]: rule does not match anything",
                "Coverage scope is empty: no module matches includeSubsystems or includePrefix"), warnings(report));
    }

    @Test
    void prefixedMethodsWorkForEdtProject() throws Exception {
        Path edt = tempDir.resolve("edt");
        TestUtils.copyDirectory(Path.of("src/test/resources/edt/pc"), edt);
        Files.writeString(edt.resolve("src/CommonModules/ПростойОбщийМодуль/Module.bsl"), ROUTINE_MODULE_TEXT);
        File config = config("{'includePrefix':['тест'],'prefixScope':{'routines':true}}");
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND, "-P", tempDir.toAbsolutePath().toString(),
                "-s", "edt", "--config", config.getPath(), "--strict", "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertEquals(5, root.get("totalLines").asInt());
        assertEquals("edt/src/CommonModules/ПростойОбщийМодуль/Module.bsl",
                root.get("prefixRoutines").get(0).get("modules").get(0).asText());
    }

    private static String captureLog(Runnable action) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setErr(originalErr);
        }
        return log.toString(StandardCharsets.UTF_8);
    }

    private static List<String> warnings(Path report) throws IOException {
        List<String> result = new ArrayList<>();
        new ObjectMapper().readTree(report.toFile()).get("warnings").forEach(node -> result.add(node.asText()));
        return result;
    }

    @Test
    void ruleOnlyOnModulesRemovedBySupportIsNotWarned() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve("configuration/Ext/ParentConfigurations.bin"),
                "{6,0,1,46c7c1d0-b04d-4295-9b04-ae3207c18d29,0,11111111-1111-1111-1111-111111111111,"
                        + "\"1.0\",\"Вендор\",\"Поставка\",1,"
                        + "0,0,1be4af7e-334e-49fa-a9f9-d80c737ff954,1be4af7e-334e-49fa-a9f9-d80c737ff954,0}");
        File config = config("{'exclude':{'objects':['CommonModule.ПростойОбщийМодуль']}}");
        Path report = tempDir.resolve("exclusions.json");

        int exitCode = validateProject(project, new StringWriter(), "-r", "NOT_EDITABLE", "--config", config.getPath(),
                "--strict", "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertEquals(0, root.get("entries").size());
        assertEquals(0, root.get("warnings").size());
    }

    @Test
    void rulesWorkForEdtProject() throws Exception {
        File config = config("{'exclude':{'objects':['Catalog.Справочник1'],"
                + "'paths':['src/test/resources/edt/pc/src/CommonModules/**']}}");
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-P", new File(".").getAbsolutePath(), "-s", "src/test/resources/edt/pc",
                "--config", config.getPath(), "--strict", "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        JsonNode entries = new ObjectMapper().readTree(report.toFile()).get("entries");
        assertEquals(2, entries.size());
        for (JsonNode entry : entries) {
            assertTrue(entry.get("modules").size() > 0, entry.toString());
        }
        assertTrue(entries.get(0).get("modules").get(0).asText()
                .startsWith("src/test/resources/edt/pc/src/Catalogs/Справочник1/"), entries.toString());
    }

    @Test
    void helpDescribesOptions() {
        StringWriter out = new StringWriter();
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setOut(new PrintWriter(out, true));

        assertEquals(CommandLine.ExitCode.OK, commandLine.execute(PipeMessages.VALIDATE_COMMAND, "-h"));
        for (String option : new String[]{"--strict", "--config", "--exclusion-report", "--removeSupport"}) {
            assertTrue(out.toString().contains(option), out.toString());
        }
    }

    private static int validateProject(Path project, StringWriter stderr, String... options) {
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.VALIDATE_COMMAND,
                "-P", project.toAbsolutePath().toString(),
                "-s", "configuration"));
        arguments.addAll(Arrays.asList(options));
        return execute(stderr, arguments.toArray(new String[0]));
    }

    private File config(String json) throws IOException {
        Path file = tempDir.resolve("team.json");
        Files.writeString(file, json.replace('\'', '"'));
        return file.toFile();
    }

    @Test
    void includeUsersIsReportedAsStartOnlyAndPassesStrict() throws Exception {
        Path config = tempDir.resolve("users.json");
        Files.writeString(config, "{\"includeUsers\":[\"unit\",\"ui\"]}");
        String log = captureLog(() -> assertEquals(CommandLine.ExitCode.OK,
                validate(new StringWriter(), "--config", config.toString(), "--strict")));
        String message = "users.json → includeUsers: applies only to start, measures are not filtered here";
        assertTrue(log.contains(message), log);
        assertEquals(1, log.split(Pattern.quote(message), -1).length - 1, log);
    }

    @Test
    void invalidIncludeUsersFailsValidate() throws Exception {
        Path config = tempDir.resolve("users.json");
        Files.writeString(config, "{\"includeUsers\":[]}");
        StringWriter err = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE, validate(err, "--config", config.toString()));
        assertTrue(err.toString().contains("users.json → includeUsers: empty list"), err.toString());
    }

    private static int validate(StringWriter stderr, String... options) {
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.VALIDATE_COMMAND,
                "-P", new File(".").getAbsolutePath(),
                "-s", CONFIGURATION.getPath()));
        arguments.addAll(Arrays.asList(options));
        return execute(stderr, arguments.toArray(new String[0]));
    }

    private static int execute(StringWriter stderr, String... arguments) {
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments);
    }
}
