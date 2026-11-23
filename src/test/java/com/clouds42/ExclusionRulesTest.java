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

import com.github._1c_syntax.bsl.mdclasses.CF;
import com.github._1c_syntax.bsl.mdclasses.Configuration;
import com.github._1c_syntax.bsl.mdclasses.MDClasses;
import com.github._1c_syntax.bsl.mdo.Subsystem;
import com.github._1c_syntax.bsl.types.MdoReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExclusionRulesTest {

    private static CF configuration;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void readConfiguration() {
        configuration = (CF) MDClasses.createConfiguration(Path.of("src/test/resources/configuration"));
    }

    @Test
    void compilesRulesWithSourceTeamAndKind() throws Exception {
        ExclusionRules rules = load(write("team-a.json",
                "{'team':'Команда А','exclude':{'objects':['Catalog.А'],'paths':['**/*.bsl']},'auto':{'lines':['Б']}}"));

        assertEquals(List.of("team-a.json → exclude.objects[0]", "team-a.json → exclude.paths[0]",
                "team-a.json → auto.lines[0]"), sources(rules));
        assertEquals(List.of(CoverageMarker.Kind.OFF, CoverageMarker.Kind.OFF, CoverageMarker.Kind.AUTO),
                rules.getRules().stream().map(ExclusionRules.Rule::getKind).collect(Collectors.toList()));
        assertTrue(rules.getRules().stream().allMatch(rule -> rule.getTeam().equals("Команда А")));
        assertFalse(rules.filtersScope());
    }

    @Test
    void readsDirectorySkippingOtherFilesAndSubdirectories() throws Exception {
        write("b.json", "{'exclude':{'objects':['Catalog.Б']}}");
        write("a.json", "{'exclude':{'objects':['Catalog.А']}}");
        write("notes.txt", "not a config");
        Files.createDirectory(tempDir.resolve("nested"));
        write("nested/c.json", "{'exclude':{'objects':['Catalog.В']}}");

        ExclusionRules rules = load(tempDir.toFile());

        assertEquals(List.of("a.json → exclude.objects[0]", "b.json → exclude.objects[0]"), sources(rules));
    }

    @Test
    void unclosedBlockSettingBelongsToItsConfigFile() throws Exception {
        write("a.json", "{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}");
        write("b.json", "{'changeMarkers':{}}");
        ExclusionRules rules = load(tempDir.toFile());
        List<ExclusionRules.ChangeMarker> first = rules.getChangeMarkers().stream()
                .filter(marker -> marker.getSource().startsWith("a.json")).toList();
        List<ExclusionRules.ChangeMarker> second = rules.getChangeMarkers().stream()
                .filter(marker -> marker.getSource().startsWith("b.json")).toList();
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertTrue(first.stream().allMatch(ExclusionRules.ChangeMarker::closesUnclosed));
        assertFalse(second.stream().anyMatch(ExclusionRules.ChangeMarker::closesUnclosed));
    }

    @Test
    void reportsUnknownUnclosedBlockSetting() throws Exception {
        File file = write("a.json", "{'changeMarkers':{},'unclosedBlocks':{'enabld':true}}");
        ExclusionReport report = new ExclusionReport();
        ExclusionRules.load(file, tempDir, configuration, report);
        assertEquals(List.of("a.json: unknown key unclosedBlocks.enabld"), report.getWarnings());
    }

    @Test
    void mergesIncludedSubsystemsOfAllFiles() throws Exception {
        write("a.json", "{'includeSubsystems':['ПодчиненнаяПодсистема']}");
        write("b.json", "{'includeSubsystems':['ПочиненнаяСистема2'],'exclude':{'objects':['Catalog.А']}}");

        ExclusionRules rules = load(tempDir.toFile());

        assertTrue(rules.filtersScope());
        assertEquals(Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема",
                "Subsystem.ПерваяПодсистема.Subsystem.ПочиненнаяСистема2"), rules.getIncludedSubsystems());
        assertEquals(List.of("a.json → includeSubsystems[0]", "b.json → includeSubsystems[0]"),
                rules.getIncludeSources());
    }

    @Test
    void rejectsMissingPath() {
        File missing = tempDir.resolve("missing").toFile();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(missing));

        assertEquals("Coverage configuration path does not exist: " + missing.getAbsolutePath(), error.getMessage());
    }

    @Test
    void rejectsDirectoryWithoutJsonFiles() throws Exception {
        write("notes.txt", "not a config");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(tempDir.toFile()));

        assertEquals("Coverage configuration directory contains no *.json files: "
                + tempDir.toFile().getAbsolutePath(), error.getMessage());
    }

    @Test
    void rejectsConfigWithoutRules() throws Exception {
        File file = write("a.json", "{'team':'Команда А','exclude':{'objects':[]}}");

        assertEquals("Coverage configuration a.json contains no rules",
                assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage());
    }

    @Test
    void exclusionReportIsResolvedAgainstProjectDirectory() throws Exception {
        Files.createDirectories(tempDir.resolve("tools/conf"));
        File file = write("tools/conf/a.json", "{'exclusionReport':'out/x.json','exclude':{'objects':['Catalog.А']}}");

        assertEquals(tempDir.resolve("out/x.json").toAbsolutePath().toFile(), load(file).getExclusionReportFile());
    }

    @Test
    void rejectsExclusionReportFromProjectDirectoryInsideConfigDirectory() throws Exception {
        Files.createDirectory(tempDir.resolve("conf"));
        write("conf/a.json", "{'exclusionReport':'conf/x.json','exclude':{'objects':['Catalog.А']}}");

        assertEquals("a.json → exclusionReport: " + tempDir.resolve("conf/x.json").toAbsolutePath()
                        + " is inside the configuration directory and would be read as a config on the next run",
                assertThrows(IllegalArgumentException.class,
                        () -> load(tempDir.resolve("conf").toFile())).getMessage());
    }

    @Test
    void rejectsNewJsonReportInLinkedConfigDirectory() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("conf"));
        createSymbolicLink(tempDir.resolve("alias"), directory);
        write("conf/a.json", "{'exclusionReport':'alias/report.json','exclude':{'objects':['Catalog.А']}}");

        String message = assertThrows(IllegalArgumentException.class, () -> load(directory.toFile())).getMessage();
        assertTrue(message.contains("is inside the configuration directory"), message);
        assertFalse(Files.exists(directory.resolve("report.json")));
    }

    @Test
    void configuredReportCannotCreateRulesViaFileLinks() throws Exception {
        Path rules = Files.createDirectory(tempDir.resolve("rules"));
        Path exports = Files.createDirectory(tempDir.resolve("exports"));
        Path entryPoint = tempDir.resolve("output");
        Path redirect = exports.resolve("redirect");
        Path newRulesFile = rules.resolve("summary.JSON");

        createSymbolicLink(entryPoint, redirect);
        createSymbolicLink(redirect, Path.of("..", "rules", "summary.JSON"));
        write("rules/app.json", "{'exclusionReport':'output','auto':{'lines':['Sample']}}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(rules.toFile()));
        assertTrue(error.getMessage().startsWith("app.json → exclusionReport: " + entryPoint), error.getMessage());
        assertFalse(Files.exists(newRulesFile));
    }

    @Test
    void cyclicReportLinkIsDetectedAfterResolvingItsParent() throws Exception {
        Path rules = Files.createDirectory(tempDir.resolve("rules"));
        Path output = Files.createDirectory(tempDir.resolve("output"));
        Path alias = tempDir.resolve("linked-output");
        createSymbolicLink(alias, output);
        Files.createDirectory(output.resolve("nested"));
        Path report = alias.resolve("destination");
        createSymbolicLink(report, Path.of("nested", "..", "destination"));

        FileSystemLoopException error = assertThrows(FileSystemLoopException.class,
                () -> ExclusionRules.rejectReportInsideConfigDirectory(rules.toFile(), report.toFile(), "--exclusion-report"));
        assertEquals(report.getParent().toRealPath().resolve(report.getFileName()).toString(), error.getFile());
    }

    @Test
    void unrelatedCyclicConfigLinkDoesNotPreventWritingTheReport() throws Exception {
        Path rules = Files.createDirectory(tempDir.resolve("rules"));
        Files.writeString(rules.resolve("active.json"), "{\"exclusionReport\":\"out/report.json\"}");
        createSymbolicLink(rules.resolve("old.json"), Path.of("old.json"));

        assertEquals(tempDir.resolve("out/report.json").toFile(), load(rules.toFile()).getExclusionReportFile());
    }

    @Test
    void exclusionReportKeepsSpacesInTheFileName() throws Exception {
        File file = write("rules.json", "{'exclusionReport':' reports/out .json'}");
        assertEquals(tempDir.resolve(" reports/out .json").toFile(), load(file).getExclusionReportFile());
    }

    @Test
    void absoluteExclusionReportIsKept() throws Exception {
        Path report = tempDir.resolve("elsewhere/x.json").toAbsolutePath();
        File file = tempDir.resolve("a.json").toFile();
        new ObjectMapper().writeValue(file, Map.of("exclusionReport", report.toString(),
                "exclude", Map.of("objects", List.of("Catalog.А"))));

        assertEquals(report.toFile(), load(file).getExclusionReportFile());
    }

    @Test
    void exclusionReportIsNotSetWithoutKeyOrWithEmptyValue() throws Exception {
        write("a.json", "{'exclude':{'objects':['Catalog.А']}}");
        write("b.json", "{'exclusionReport':'','exclude':{'objects':['Catalog.Б']}}");

        assertNull(load(tempDir.toFile()).getExclusionReportFile());
    }

    @Test
    void configWithOnlyExclusionReportIsAccepted() throws Exception {
        write("a.json", "{'exclude':{'objects':['Catalog.А']}}");
        write("settings.json", "{'exclusionReport':'reports/x.json'}");

        ExclusionRules rules = load(tempDir.toFile());

        assertEquals(List.of("a.json → exclude.objects[0]"), sources(rules));
        assertEquals(tempDir.resolve("reports/x.json").toAbsolutePath().toFile(), rules.getExclusionReportFile());
    }

    @Test
    void sameExclusionReportInSeveralFilesIsAccepted() throws Exception {
        write("a.json", "{'exclusionReport':'reports/x.json'}");
        write("b.json", "{'exclusionReport':'./reports/x.json','exclude':{'objects':['Catalog.А']}}");

        assertEquals(tempDir.resolve("reports/x.json").toAbsolutePath().toFile(),
                load(tempDir.toFile()).getExclusionReportFile());
    }

    @Test
    void rejectsDifferentExclusionReportsInSeveralFiles() throws Exception {
        write("a.json", "{'exclusionReport':'reports/x.json'}");
        write("b.json", "{'exclusionReport':'reports/y.json','exclude':{'objects':['Catalog.А']}}");

        assertEquals("b.json → exclusionReport: " + tempDir.resolve("reports/y.json").toAbsolutePath()
                        + " conflicts with " + tempDir.resolve("reports/x.json").toAbsolutePath() + " from a.json",
                assertThrows(IllegalArgumentException.class, () -> load(tempDir.toFile())).getMessage());
    }

    @Test
    void rejectsExclusionReportInsideConfigDirectory() throws Exception {
        write("a.json", "{'exclusionReport':'Exclusions.JSON','exclude':{'objects':['Catalog.А']}}");

        assertEquals("a.json → exclusionReport: " + tempDir.resolve("Exclusions.JSON").toAbsolutePath()
                        + " is inside the configuration directory and would be read as a config on the next run",
                assertThrows(IllegalArgumentException.class, () -> load(tempDir.toFile())).getMessage());
    }

    @Test
    void exclusionReportNextToSingleConfigFileIsAccepted() throws Exception {
        File file = write("a.json", "{'exclusionReport':'exclusions.json','exclude':{'objects':['Catalog.А']}}");

        assertEquals(tempDir.resolve("exclusions.json").toAbsolutePath().toFile(), load(file).getExclusionReportFile());
    }

    @Test
    void hiddenOrNonJsonExclusionReportInsideConfigDirectoryIsAccepted() throws Exception {
        write("a.json", "{'exclusionReport':'.exclusions.json'}");
        write("b.json", "{'exclusionReport':'.exclusions.json','exclude':{'objects':['Catalog.А']}}");
        assertEquals(tempDir.resolve(".exclusions.json").toAbsolutePath().toFile(),
                load(tempDir.toFile()).getExclusionReportFile());

        Files.delete(tempDir.resolve("a.json"));
        write("b.json", "{'exclusionReport':'exclusions.txt','exclude':{'objects':['Catalog.А']}}");
        assertEquals(tempDir.resolve("exclusions.txt").toAbsolutePath().toFile(),
                load(tempDir.toFile()).getExclusionReportFile());
    }

    @Test
    void compilesChangeMarkerPairWithSourceAndTeam() throws Exception {
        List<ExclusionRules.ChangeMarker> markers = load(write("a.json", "{'team':'Команда А','changeMarkers':"
                + "[{'begin':'^\\\\s*//\\\\s*\\\\+\\\\+\\\\s*уч(?![\\\\p{L}\\\\p{N}_])',"
                + "'end':'^\\\\s*//\\\\s*--\\\\s*уч(?![\\\\p{L}\\\\p{N}_])'}]}"))
                .getChangeMarkers();

        assertEquals(1, markers.size());
        ExclusionRules.ChangeMarker marker = markers.get(0);
        assertEquals("a.json → changeMarkers[0]", marker.getSource());
        assertEquals("Команда А", marker.getTeam());
        assertTrue(marker.isBegin("\t// ++ уч DEMO-123"));
        assertTrue(marker.isEnd("// -- УЧ"));
        assertFalse(marker.isBegin("// ++ подмодуль"));
        assertFalse(marker.isBegin("// ++ ученик"));
    }

    @Test
    void singleChangeMarkerObjectIsOnePair() throws Exception {
        assertEquals(1, load(write("a.json", "{'changeMarkers':{'begin':'А','end':'Б'}}")).getChangeMarkers().size());
    }

    @Test
    void emptyChangeMarkersObjectTakesDefaultPairs() throws Exception {
        List<ExclusionRules.ChangeMarker> markers = load(write("a.json", "{'changeMarkers':{}}")).getChangeMarkers();

        assertEquals(2, markers.size());
        assertTrue(markers.stream().allMatch(marker -> marker.getSource().equals("a.json → changeMarkers[0]")));
        ExclusionRules.ChangeMarker plus = markers.get(0);
        assertTrue(plus.isBegin("//++ DEMO_AUTHOR"));
        assertTrue(plus.isBegin("\t// ++ уч"));
        assertTrue(plus.isEnd("\t// -- уч"));
        assertFalse(plus.isEnd("// -------------------------"));
        assertFalse(plus.isBegin("// {{DEMO DEMO"));
        ExclusionRules.ChangeMarker braces = markers.get(1);
        assertTrue(braces.isBegin("// {{DEMO DEMO-42"));
        assertTrue(braces.isEnd("// }}DEMO Engineer01"));
    }

    @Test
    void changeMarkerWithOnlyBeginTakesDefaultEnd() throws Exception {
        ExclusionRules.ChangeMarker marker = load(write("a.json",
                "{'changeMarkers':{'begin':'^\\\\s*//\\\\s*\\\\+\\\\+\\\\s*уч'}}")).getChangeMarkers().get(0);

        assertTrue(marker.isEnd("// -- уч"));
        assertTrue(marker.isEnd("// }}DEMO"));
        assertFalse(marker.isEnd("// ---------"));
    }

    @Test
    void rejectsInvalidChangeMarker() throws Exception {
        File file = write("a.json", "{'changeMarkers':{'begin':'//{{DEMO'}}");

        String message = assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage();

        assertTrue(message.startsWith("a.json → changeMarkers[0].begin: invalid regular expression: "), message);
    }

    @Test
    void rejectsEmptyChangeMarker() throws Exception {
        File file = write("a.json", "{'changeMarkers':{'end':' '}}");

        assertEquals("a.json → changeMarkers[0].end: empty value",
                assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage());
    }

    @Test
    void emptyChangeMarkersListIsNoRule() throws Exception {
        File file = write("a.json", "{'changeMarkers':[]}");

        assertEquals("Coverage configuration a.json contains no rules",
                assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage());
    }

    @Test
    void warnsAboutUnknownKeyOfChangeMarkers() throws Exception {
        File file = write("a.json", "{'changeMarkers':{'bgin':'x'}}");
        ExclusionReport report = new ExclusionReport();

        ExclusionRules rules = ExclusionRules.load(file, tempDir, configuration, report);

        assertEquals(List.of("a.json: unknown key changeMarkers[0].bgin"), report.getWarnings());
        assertEquals(2, rules.getChangeMarkers().size());
    }

    @Test
    void changeMarkersOfSeveralFilesAreUnited() throws Exception {
        write("a.json", "{'team':'уч','changeMarkers':{'begin':'А','end':'Б'}}");
        write("b.json", "{'team':'подмодуль','changeMarkers':{'begin':'В','end':'Г'}}");

        assertEquals(List.of("a.json → changeMarkers[0]", "b.json → changeMarkers[0]"),
                load(tempDir.toFile()).getChangeMarkers().stream().map(ExclusionRules.ChangeMarker::getSource)
                        .collect(Collectors.toList()));
    }

    @Test
    void rejectsBooleanAndNumericRuleValuesButAcceptsStringPatterns() throws Exception {
        for (String value : List.of("true", "42", "1.5")) {
            File file = write("rules.json", "{'exclude':{'objects':['CommonModule.Sample'],'lines':[" + value + "]}}");
            assertThrows(IllegalArgumentException.class, () -> load(file), value);
        }
        ExclusionRules rules = load(write("rules.json", "{'exclude':{'lines':['true','42','1.5']}}"));
        assertEquals(3, rules.getRules().size());
    }

    @Test
    void rejectsAmbiguousShortNameEvenWhenItMatchesARootPath() {
        Subsystem nested = Subsystem.builder().name("Sales")
                .mdoReference(MdoReference.create("Subsystem.Operations.Subsystem.Sales")).build();
        Subsystem root = Subsystem.builder().name("Sales")
                .mdoReference(MdoReference.create("Subsystem.Sales")).build();
        Subsystem parent = Subsystem.builder().name("Operations")
                .mdoReference(MdoReference.create("Subsystem.Operations")).subsystem(nested).build();
        CF tree = Configuration.EMPTY.toBuilder().subsystems(List.of(root, parent)).build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SubsystemFilter.resolve(tree, "Sales"));
        assertEquals("Ambiguous subsystem name: Sales", error.getMessage());
        assertEquals(Set.of("Subsystem.Operations.Subsystem.Sales"),
                SubsystemFilter.resolve(tree, "Operations.Sales"));
    }

    @Test
    void rejectsDuplicateJsonKeysAtTheTopLevelAndInNestedRules() throws Exception {
        for (String json : List.of(
                "{'includePrefix':['Demo'],'includePrefix':['Sample']}",
                "{'includePrefix':['Demo'],'prefixScope':{'modules':false,'modules':true}}",
                "{'exclude':{'lines':['First'],'lines':['First']}}")) {
            File file = write("rules.json", json);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> load(file), json);
            assertTrue(error.getMessage().contains("Duplicate field"), error.getMessage());
        }
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        File file = write("a.json", "{'exclude':");

        String message = assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage();

        assertTrue(message.startsWith("Cannot read coverage configuration a.json: "), message);
    }

    @Test
    void rejectsNullDocument() throws Exception {
        File file = write("a.json", "null");

        assertEquals("Cannot read coverage configuration a.json: empty document",
                assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage());
    }

    @Test
    void skipsHiddenFilesOfDirectory() throws Exception {
        write("a.json", "{'exclude':{'objects':['Catalog.А']}}");
        write("._a.json", "not a config");
        write(".draft.json", "{'exclude':{'objects':['Catalog.Б']}}");

        assertEquals(List.of("a.json → exclude.objects[0]"), sources(load(tempDir.toFile())));
    }

    @Test
    void rejectsInvalidRegularExpression() throws Exception {
        File file = write("a.json", "{'exclude':{'lines':['(']}}");

        String message = assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage();

        assertTrue(message.startsWith("a.json → exclude.lines[0]: invalid regular expression: "), message);
    }

    @Test
    void rejectsEmptyValue() throws Exception {
        File file = write("a.json", "{'auto':{'paths':['  ']}}");

        assertEquals("a.json → auto.paths[0]: empty value",
                assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage());
    }

    @Test
    void rejectsUnknownSubsystemWithSource() throws Exception {
        File file = write("a.json", "{'exclude':{'subsystems':['Нет']}}");

        assertEquals("a.json → exclude.subsystems[0]: Unknown subsystem: Нет",
                assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage());
    }

    @Test
    void subsystemMaskSelectsMatchingSubsystemsWithNested() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includeSubsystems':['Перв*']}"));

        assertTrue(rules.filtersScope());
        assertEquals(Set.of("Subsystem.ПерваяПодсистема",
                "Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема",
                "Subsystem.ПерваяПодсистема.Subsystem.ПочиненнаяСистема2"), rules.getIncludedSubsystems());
    }

    @Test
    void subsystemMaskMatchesNamesOfNestedSubsystems() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includeSubsystems':['По*']}"));

        assertEquals(Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема",
                "Subsystem.ПерваяПодсистема.Subsystem.ПочиненнаяСистема2"), rules.getIncludedSubsystems());
    }

    @Test
    void subsystemMaskWithDotMatchesFullPath() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includeSubsystems':['ПерваяПодсистема.*2']}"));

        assertEquals(Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПочиненнаяСистема2"),
                rules.getIncludedSubsystems());
    }

    @Test
    void subsystemMaskInRuleCoversMatchingSubsystems() throws Exception {
        ExclusionRules.Rule rule = load(write("a.json", "{'auto':{'subsystems':['*Система2']}}")).getRules().get(0);

        assertEquals(CoverageMarker.Kind.AUTO, rule.getKind());
        assertTrue(rule.matches(new ModuleId("m.bsl", List.of(),
                Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПочиненнаяСистема2"))));
        assertFalse(rule.matches(new ModuleId("m.bsl", List.of(),
                Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"))));
    }

    @Test
    void warnsAboutIncludedSubsystemMaskMatchingNothingAndKeepsCase() throws Exception {
        File file = write("a.json", "{'includeSubsystems':['перв*']}");
        ExclusionReport report = new ExclusionReport();

        ExclusionRules rules = ExclusionRules.load(file, tempDir, configuration, report);

        assertTrue(rules.filtersScope());
        assertEquals(Set.of(), rules.getIncludedSubsystems());
        assertEquals(List.of("a.json → includeSubsystems[0]: rule does not match anything"), report.getWarnings());
    }

    @Test
    void includePrefixSelectsSubsystemsWithPrefix() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includePrefix':['Подч']}"));

        assertTrue(rules.filtersScope());
        assertEquals(Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"),
                rules.getIncludedSubsystems());
        assertEquals(List.of("a.json → includePrefix[0]"), rules.getIncludeSources());
        assertTrue(rules.inScope(new ModuleId("m.bsl", List.of("Catalog.Справочник1.ObjectModule"),
                Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"))));
        assertFalse(rules.inScope(new ModuleId("m.bsl", List.of("Catalog.Справочник1.ObjectModule"),
                Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПочиненнаяСистема2"))));
    }

    @Test
    void includePrefixMatchesNamesOfObjectsFormsAndCommands() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includePrefix':['тест']}"));

        assertTrue(rules.inScope(scopeModule("Catalog.тестТовары.ObjectModule", "Справочник.тестТовары.ObjectModule")));
        assertTrue(rules.inScope(scopeModule("CommonModule.тестСервер.CommonModule")));
        assertTrue(rules.inScope(scopeModule("Catalog.Номенклатура.Form.тестФорма.FormModule")));
        assertTrue(rules.inScope(scopeModule("Document.Реализация.Command.тестПечать.CommandModule")));
        assertFalse(rules.inScope(scopeModule("Catalog.Номенклатура.Form.ФормаЭлемента.FormModule")));
        assertFalse(rules.inScope(scopeModule("Catalog.ТестТовары.ObjectModule")));
        assertFalse(rules.inScope(scopeModule("Catalog.подмодульТовары.ObjectModule")));
    }

    @Test
    void includePrefixIgnoresTypeNames() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includePrefix':['ОбщийМодуль','Form']}"));

        assertTrue(rules.inScope(scopeModule("CommonModule.ОбщийМодульКлиент.CommonModule")));
        assertFalse(rules.inScope(scopeModule("CommonModule.Прочее.CommonModule", "ОбщийМодуль.Прочее.CommonModule")));
        assertFalse(rules.inScope(scopeModule("Catalog.А.Form.Б.FormModule")));
    }

    @Test
    void includePrefixAndIncludeSubsystemsAreUnited() throws Exception {
        ExclusionRules rules = load(write("a.json",
                "{'includeSubsystems':['ПочиненнаяСистема2'],'includePrefix':['тест']}"));

        assertTrue(rules.inScope(new ModuleId("m.bsl", List.of("Catalog.А.ObjectModule"),
                Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПочиненнаяСистема2"))));
        assertTrue(rules.inScope(scopeModule("Catalog.тестА.ObjectModule")));
        assertFalse(rules.inScope(scopeModule("Catalog.А.ObjectModule")));
        assertEquals(List.of("a.json → includeSubsystems[0]", "a.json → includePrefix[0]"),
                rules.getIncludeSources());
    }

    @Test
    void everyModuleIsInScopeWithoutIncludeKeys() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'exclude':{'objects':['Catalog.А']}}"));

        assertFalse(rules.filtersScope());
        assertTrue(rules.inScope(scopeModule("Catalog.А.ObjectModule")));
    }

    @Test
    void rejectsEmptyPrefix() throws Exception {
        File file = write("a.json", "{'includePrefix':[' ']}");

        assertEquals("a.json → includePrefix[0]: empty value",
                assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage());
    }

    @Test
    void warnsAboutUnknownKeys() throws Exception {
        File file = write("a.json",
                "{'exclude':{'objects':['Catalog.А'],'modules':[]},'owner':'x','auto':{'file':[]}}");
        ExclusionReport report = new ExclusionReport();

        ExclusionRules.load(file, tempDir, configuration, report);

        assertEquals(List.of("a.json: unknown key owner", "a.json: unknown key exclude.modules",
                "a.json: unknown key auto.file"), report.getWarnings());
    }

    @Test
    void objectMaskMatchesWholeNameOrDottedPrefixRespectingCase() throws Exception {
        ModuleId form = module("Catalogs/Номенклатура/Forms/ФормаЭлемента/Ext/Form/Module.bsl",
                "Catalog.Номенклатура.Form.ФормаЭлемента.FormModule",
                "Справочник.Номенклатура.Форма.ФормаЭлемента.FormModule");
        ModuleId common = module("CommonModules/уч_Сервер/Ext/Module.bsl",
                "CommonModule.уч_Сервер.CommonModule", "ОбщийМодуль.уч_Сервер.CommonModule");

        assertTrue(objectRule("Catalog.Номенклатура").matches(form));
        assertFalse(objectRule("catalog.номенклатура.form.*").matches(form));
        assertTrue(objectRule("Справочник.Номенклатура.Форма.*").matches(form));
        assertTrue(objectRule("*.FormModule").matches(form));
        assertFalse(objectRule("Catalog.Номен").matches(form));
        assertTrue(objectRule("CommonModule.уч_*").matches(common));
        assertFalse(objectRule("CommonModule.тест_*").matches(common));
        ModuleId demo = module("CommonModules/ТестовыйСервис/Module.bsl",
                "CommonModule.ТестовыйСервис.CommonModule", "ОбщийМодуль.ТестовыйСервис.CommonModule");
        assertFalse(objectRule("ОбщийМодуль.тест*").matches(demo));
    }

    @Test
    void pathGlobMatchesPathRelativeToProject() throws Exception {
        ExclusionRules.Rule rule = load(write("a.json", "{'exclude':{'paths':['**/Forms/**/Module.bsl']}}"))
                .getRules().get(0);

        assertTrue(rule.matches(module("src/Catalogs/Н/Forms/Ф/Ext/Form/Module.bsl")));
        assertFalse(rule.matches(module("src/Catalogs/Н/Ext/ObjectModule.bsl")));
        assertFalse(rule.matches(module("src/CommonForms/Ф/Ext/Form/Module.bsl")));
    }

    @Test
    void pathGlobRespectsCaseOnEveryOs() throws Exception {
        ModuleId form = module("src/Catalogs/Н/Forms/Ф/Ext/Form/Module.bsl");

        assertFalse(pathRule("**/forms/**/Module.bsl").matches(form));
        assertTrue(pathRule("**/Forms/**/Module.bsl").matches(form));
    }

    @Test
    void pathGlobSupportsWildcardsGroupsAndClasses() throws Exception {
        ModuleId form = module("src/Catalogs/уч_Товары/Forms/ФормаЭлемента/Ext/Form/Module.bsl");
        ModuleId common = module("src/CommonForms/Ф/Ext/Form/Module.bsl");

        assertTrue(pathRule("src/Catalogs/уч_*/**").matches(form));
        assertFalse(pathRule("src/*/Module.bsl").matches(form));
        assertTrue(pathRule("**/{Forms,CommonForms}/**/Module.bsl").matches(common));
        assertTrue(pathRule("src/Catalog?/**").matches(form));
        assertTrue(pathRule("src/[CD]atalogs/**").matches(form));
        assertFalse(pathRule("src/[!C]atalogs/**").matches(form));
        assertTrue(pathRule("src/Catalogs/уч_Товары/Forms/ФормаЭлемента/Ext/Form/Module.bsl").matches(form));
        assertFalse(pathRule("src/Catalogs/уч_Товары/Forms/ФормаЭлемента/Ext/Form/Module.bs").matches(form));
    }

    @Test
    void pathGlobPreservesPaddingCompatibilityAndMatchesExplicitSpaces() throws Exception {
        ModuleId plain = module("src/CommonModules/Module.bsl");
        ModuleId withLeadingSpace = module(" src/CommonModules/Module.bsl");
        assertTrue(pathRule("  src/**  ").matches(plain));
        assertFalse(pathRule("  src/**  ").matches(withLeadingSpace));
        assertTrue(pathRule("[ ]src/**").matches(withLeadingSpace));
        assertFalse(pathRule("[ ]src/**").matches(plain));
    }

    @Test
    void pathGlobEscapesWildcardsLiterally() throws Exception {
        assertTrue(pathRule("src/literal\\*/Module.bsl").matches(module("src/literal*/Module.bsl")));
        assertFalse(pathRule("src/literal\\*/Module.bsl").matches(module("src/literalA/Module.bsl")));
        assertTrue(pathRule("src/literal\\?/Module.bsl").matches(module("src/literal?/Module.bsl")));
        assertFalse(pathRule("src/literal\\?/Module.bsl").matches(module("src/literalA/Module.bsl")));
    }

    @Test
    void pathGlobTreatsCommaAndClosingBraceOutsideGroupAsLiterals() throws Exception {
        ExclusionRules.Rule rule = pathRule("src/name},suffix/Module.bsl");
        assertTrue(rule.matches(module("src/name},suffix/Module.bsl")));
        assertFalse(rule.matches(module("src/name/suffix/Module.bsl")));
    }

    @Test
    void pathGlobCharacterClassEscapesRegexSpecialCharacters() throws Exception {
        ExclusionRules.Rule rule = pathRule("src/[^&[]/Module.bsl");
        for (String character : List.of("^", "&", "[")) {
            assertTrue(rule.matches(module("src/" + character + "/Module.bsl")), character);
        }
        assertFalse(rule.matches(module("src/A/Module.bsl")));
    }

    @Test
    void pathGlobAcceptsValidRangesAndLiteralLeadingOrTrailingHyphens() throws Exception {
        assertTrue(pathRule("src/[a-cd-f]/Module.bsl").matches(module("src/e/Module.bsl")));
        assertFalse(pathRule("src/[a-c]/Module.bsl").matches(module("src/e/Module.bsl")));
        assertTrue(pathRule("src/[-a-c]/Module.bsl").matches(module("src/-/Module.bsl")));
        assertTrue(pathRule("src/[a-]/Module.bsl").matches(module("src/-/Module.bsl")));
        assertFalse(pathRule("src/[!a-c]/Module.bsl").matches(module("src/b/Module.bsl")));
        assertTrue(pathRule("src/[!a-c]/Module.bsl").matches(module("src/e/Module.bsl")));
    }

    @Test
    void rejectsMalformedGlobWithItsConfigurationSource() throws Exception {
        for (String glob : List.of("src/trailing\\", "src/{A,{B,C}}/**", "src/{A,B/**", "src/[A/B]/**",
                "src/[a-c-e]/**", "src/[a-c-]/**", "src/[z-a]/**", "src/[a-/]/**")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> pathRule(glob), glob);
            assertTrue(error.getMessage().startsWith("paths.json → exclude.paths[0]: invalid glob:"), error.getMessage());
        }
    }

    @Test
    void configurationDependentRulesRequireMetadata() throws Exception {
        for (String json : List.of("{'includeSubsystems':['ПерваяПодсистема']}",
                "{'includePrefix':['уч_']}", "{'exclude':{'subsystems':['ПерваяПодсистема']}}")) {
            File file = write("metadata.json", json);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> ExclusionRules.load(file, tempDir, null, new ExclusionReport()));
            assertTrue(error.getMessage().contains("configuration sources"), error.getMessage());
        }
    }

    @Test
    void rejectsInvalidGlob() throws Exception {
        File file = write("a.json", "{'exclude':{'paths':['src/[abc']}}");

        String message = assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage();

        assertTrue(message.startsWith("a.json → exclude.paths[0]: invalid glob: "), message);
    }

    @Test
    void subsystemRuleCoversNestedSubsystems() throws Exception {
        ExclusionRules.Rule rule = load(write("a.json", "{'exclude':{'subsystems':['ПерваяПодсистема']}}"))
                .getRules().get(0);

        assertTrue(rule.matches(new ModuleId("m.bsl", List.of(),
                Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"))));
        assertFalse(rule.matches(new ModuleId("m.bsl", List.of(), Set.of())));
    }

    @Test
    void lineRuleSearchesLineTextIgnoringCase() throws Exception {
        ExclusionRules.Rule rule = load(write("a.json", "{'exclude':{'lines':['^\\\\s*ВызватьИсключение']}}"))
                .getRules().get(0);

        assertTrue(rule.isLineRule());
        assertTrue(rule.matchesLine("\tвызватьисключение \"ошибка\";"));
        assertFalse(rule.matchesLine("\tА = 1; // ВызватьИсключение"));
        assertFalse(rule.matches(module("m.bsl")));
    }

    @Test
    void readsJsonExtensionInAnyCase() throws Exception {
        Path directory = tempDir.resolve("configs");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("TEAM.JSON"), "{\"exclude\":{\"objects\":[\"Catalog.А\"]}}");

        assertEquals(List.of("TEAM.JSON → exclude.objects[0]"), sources(load(directory.toFile())));
    }

    @Test
    void rejectsExplicitNullInIncludeLists() throws Exception {
        for (String field : List.of("includeSubsystems", "includePrefix")) {
            File file = write("a.json", "{'" + field + "':null,'auto':{'lines':['.*']}}");
            String message = assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage();
            assertTrue(message.contains("a.json") && message.contains(field), message);
        }
    }

    @Test
    void rejectsNullChangeMarkerWithItsIndex() throws Exception {
        File file = write("a.json", "{'changeMarkers':[{},null]}");
        String message = assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage();
        assertTrue(message.contains("a.json") && message.contains("changeMarkers[1]"), message);
    }

    @Test
    void rejectsMoreThanOneJsonDocument() throws Exception {
        File file = write("a.json", "{'auto':{'lines':['.*']}} {'exclude':{'lines':['.*']}}");
        String message = assertThrows(IllegalArgumentException.class, () -> load(file)).getMessage();
        assertTrue(message.contains("a.json") && message.contains("Trailing token"), message);
        assertEquals(1, load(write("b.json", "{'auto':{'lines':['.*']}} \n\t")).getRules().size());
    }

    @Test
    void readsJsonWithByteOrderMark() throws Exception {
        Path file = tempDir.resolve("bom.json");
        Files.writeString(file, "\uFEFF{\"exclude\":{\"objects\":[\"Catalog.А\"]}}");

        assertEquals(List.of("bom.json → exclude.objects[0]"), sources(load(file.toFile())));
    }

    @Test
    void typoInSectionNameIsWarnedAndLeavesNoRules() throws Exception {
        File file = write("a.json", "{'exlude':{'objects':['Catalog.А']}}");
        ExclusionReport report = new ExclusionReport();

        assertEquals("Coverage configuration a.json contains no rules",
                assertThrows(IllegalArgumentException.class,
                        () -> ExclusionRules.load(file, tempDir, configuration, report)).getMessage());
        assertEquals(List.of("a.json: unknown key exlude"), report.getWarnings());
    }

    @Test
    void prefixScopeDefaultsKeepCurrentBehaviour() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includePrefix':['Подч']}"));
        ExclusionRules.PrefixScope scope = rules.getPrefixScope();

        assertTrue(scope.isSubsystems());
        assertTrue(scope.isIncludeSubsystemContents());
        assertTrue(scope.isModules());
        assertFalse(scope.isRoutines());
        assertEquals(List.of(), scope.getSources());
        assertFalse(rules.scopesRoutines());
        assertNull(rules.getRoutinePrecheck());
        assertEquals(Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"),
                rules.getIncludedSubsystems());
    }

    @Test
    void prefixScopeWithoutSubsystemContentsIncludesNoSubsystemObjects() throws Exception {
        ExclusionRules rules = load(write("a.json",
                "{'includePrefix':['Подч'],'prefixScope':{'includeSubsystemContents':false}}"));

        assertTrue(rules.filtersScope());
        assertEquals(Set.of(), rules.getIncludedSubsystems());
        assertFalse(rules.inScope(new ModuleId("m.bsl", List.of("Catalog.Справочник1.ObjectModule"),
                Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"))));
        assertTrue(rules.inScope(scopeModule("Catalog.ПодчТовары.ObjectModule")));
        assertEquals(List.of("a.json → prefixScope"), rules.getPrefixScope().getSources());
    }

    @Test
    void prefixScopeWithoutSubsystemsIgnoresSubsystemNames() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includePrefix':['Подч'],'prefixScope':{'subsystems':false}}"));

        assertEquals(Set.of(), rules.getIncludedSubsystems());
        assertFalse(rules.inScope(new ModuleId("m.bsl", List.of("Catalog.Справочник1.ObjectModule"),
                Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"))));
        assertTrue(rules.inScope(scopeModule("Catalog.ПодчТовары.ObjectModule")));
    }

    @Test
    void prefixScopeWithoutModulesIgnoresObjectNames() throws Exception {
        ExclusionRules rules = load(write("a.json",
                "{'includePrefix':['тест'],'prefixScope':{'modules':false,'routines':true}}"));
        ExclusionRules.ScopePrefix prefix = rules.getPrefixes().get(0);

        assertTrue(rules.filtersScope());
        assertFalse(rules.inScope(scopeModule("Catalog.тестТовары.ObjectModule")));
        assertFalse(prefix.matches(scopeModule("Catalog.тестТовары.ObjectModule")));
        assertTrue(prefix.matchesRoutine("тестОбработать"));
        assertFalse(prefix.matchesRoutine("ТестОбработать"));
        assertTrue(rules.scopesRoutines());
        assertTrue(rules.getRoutinePrecheck().matcher("Процедура тестХ()").find());
        assertTrue(rules.getRoutinePrecheck().matcher("procedure  тестX()").find());
        assertFalse(rules.getRoutinePrecheck().matcher("Процедура Х()\n\tтестХ();").find());
    }

    @Test
    void prefixScopeFieldsOfSeveralFilesAreMerged() throws Exception {
        write("a.json", "{'includePrefix':['тест'],'prefixScope':{'routines':true}}");
        write("b.json", "{'exclude':{'objects':['Catalog.А']},'prefixScope':{'modules':false,'routines':true}}");

        ExclusionRules.PrefixScope scope = load(tempDir.toFile()).getPrefixScope();

        assertTrue(scope.isSubsystems());
        assertFalse(scope.isModules());
        assertTrue(scope.isRoutines());
        assertEquals(List.of("a.json → prefixScope", "b.json → prefixScope"), scope.getSources());
    }

    @Test
    void rejectsConflictingPrefixScopeValuesNamingBothFiles() throws Exception {
        write("a.json", "{'includePrefix':['тест'],'prefixScope':{'routines':true}}");
        write("b.json", "{'exclude':{'objects':['Catalog.А']},'prefixScope':{'routines':false}}");

        assertEquals("prefixScope.routines: true in a.json conflicts with false in b.json",
                assertThrows(IllegalArgumentException.class, () -> load(tempDir.toFile())).getMessage());
    }

    @Test
    void rejectsPrefixScopeOfWrongType() throws Exception {
        File list = write("a.json", "{'includePrefix':['тест'],'prefixScope':['routines']}");
        assertEquals("a.json → prefixScope: expected an object with true/false fields",
                assertThrows(IllegalArgumentException.class, () -> load(list)).getMessage());
        File text = write("b.json", "{'includePrefix':['тест'],'prefixScope':{'routines':'true'}}");
        assertEquals("b.json → prefixScope.routines: expected true or false",
                assertThrows(IllegalArgumentException.class, () -> load(text)).getMessage());
        File number = write("c.json", "{'includePrefix':['тест'],'prefixScope':{'modules':1}}");
        assertEquals("c.json → prefixScope.modules: expected true or false",
                assertThrows(IllegalArgumentException.class, () -> load(number)).getMessage());
        File empty = write("d.json", "{'includePrefix':['тест'],'prefixScope':null}");
        assertEquals("d.json → prefixScope: expected an object with true/false fields",
                assertThrows(IllegalArgumentException.class, () -> load(empty)).getMessage());
    }

    @Test
    void warnsAboutUnknownPrefixScopeKeyAndKeepsDefaults() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ExclusionRules rules = ExclusionRules.load(
                write("a.json", "{'includePrefix':['тест'],'prefixScope':{'routine':true}}"), tempDir, configuration, report);

        assertEquals(List.of("a.json: unknown key prefixScope.routine"), report.getWarnings());
        assertFalse(rules.scopesRoutines());
    }

    @Test
    void warnsAboutPrefixScopeWithoutIncludePrefix() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ExclusionRules rules = ExclusionRules.load(write("a.json", "{'prefixScope':{'routines':true}}"),
                tempDir, configuration, report);

        assertEquals(List.of("a.json → prefixScope: no effect without includePrefix"), report.getWarnings());
        assertFalse(rules.filtersScope());
        assertFalse(rules.scopesRoutines());
    }

    @Test
    void warnsWhenPrefixScopeDisablesEveryInclusion() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ExclusionRules.load(write("a.json",
                "{'includePrefix':['тест'],'prefixScope':{'subsystems':false,'modules':false,'routines':false}}"),
                tempDir, configuration, report);

        assertEquals(List.of("a.json → prefixScope: every way of inclusion is disabled"), report.getWarnings());
    }

    @Test
    void routinePrecheckQuotesPrefixesAndIgnoresKeywordCase() throws Exception {
        ExclusionRules rules = load(write("a.json", "{'includePrefix':['тест.','demo'],'prefixScope':{'routines':true}}"));
        Pattern precheck = rules.getRoutinePrecheck();

        assertTrue(precheck.matcher("ФУНКЦИЯ тест.А()").find());
        assertTrue(precheck.matcher("Function demoA()").find());
        assertFalse(precheck.matcher("Процедура тестА()").find());
        assertFalse(precheck.matcher("Процедура DemoA()").find());
    }

    private ExclusionRules.Rule objectRule(String mask) throws IOException {
        return load(write("objects.json", "{'exclude':{'objects':['" + mask + "']}}")).getRules().get(0);
    }

    @Test
    void includeUsersOnlyConfigIsAcceptedAndListedAsSource() throws Exception {
        ExclusionRules rules = load(write("users.json", "{'includeUsers':['unit']}"));
        assertEquals(List.of("users.json → includeUsers"), rules.getIncludeUsersSources());
        assertFalse(rules.filtersScope());
        assertTrue(rules.getRules().isEmpty());
    }

    @Test
    void invalidIncludeUsersFailsLoading() throws Exception {
        File file = write("users.json", "{'includeUsers':[''],'exclude':{'objects':['CommonModule.X']}}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> load(file));
        assertEquals("users.json → includeUsers[0]: empty value", e.getMessage());
    }

    @Test
    void pathGlobCanContainAnApostrophe() throws Exception {
        ExclusionRules.Rule rule = pathRule("src/O'Brien/**");
        assertTrue(rule.matches(module("src/O'Brien/Module.bsl")));
        assertFalse(rule.matches(module("src/OBrien/Module.bsl")));
    }

    private ExclusionRules.Rule pathRule(String glob) throws IOException {
        return load(writeJson("paths.json", new ObjectMapper().writeValueAsString(
                Map.of("exclude", Map.of("paths", List.of(glob)))))).getRules().get(0);
    }

    private File write(String name, String json) throws IOException {
        return writeJson(name, json.replace('\'', '"'));
    }

    private File writeJson(String name, String json) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, json);
        return file.toFile();
    }

    private static void createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links unavailable: " + e.getMessage());
        }
    }

    private ExclusionRules load(File path) throws IOException {
        return ExclusionRules.load(path, tempDir, configuration, new ExclusionReport());
    }

    private static ModuleId module(String path, String... names) {
        return new ModuleId(path, List.of(names), Set.of());
    }

    private static ModuleId scopeModule(String... names) {
        return module("m.bsl", names);
    }

    private static List<String> sources(ExclusionRules rules) {
        return rules.getRules().stream().map(ExclusionRules.Rule::getSource).collect(Collectors.toList());
    }
}
