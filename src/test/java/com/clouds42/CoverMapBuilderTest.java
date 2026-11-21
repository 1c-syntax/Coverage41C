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
import com.github._1c_syntax.bsl.mdclasses.MDClasses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverMapBuilderTest {

    @Test
    void excludesLinesBetweenOffAndOn() {
        assertEquals(Map.of(2, 0, 6, 0, 7, 0), cover(
                "Процедура А()", "\tБ();", "\t// Cover:off", "\tВ();", "\t// Cover:on", "\tГ();", "КонецПроцедуры"));
    }

    @Test
    void marksAutoLinesWithMinusOne() {
        assertEquals(Map.of(2, 0, 4, -1, 6, 0, 7, 0), cover(
                "Процедура А()", "\tБ();", "\t// Cover:auto", "\tВ();", "\t// Cover:on", "\tГ();", "КонецПроцедуры"));
    }

    @Test
    void acceptsRussianSynonyms() {
        assertEquals(Map.of(2, 0, 6, 0, 7, 0), cover(
                "Процедура А()", "\tБ();", "\t// Cover:выкл", "\tВ();", "\t// Cover:вкл", "\tГ();", "КонецПроцедуры"));
        assertEquals(Map.of(2, 0, 4, -1, 6, 0, 7, 0), cover(
                "Процедура А()", "\tБ();", "\t// Cover:авто", "\tВ();", "\t// Cover:вкл", "\tГ();", "КонецПроцедуры"));
    }

    @Test
    void ignoresMarkerInsideStringLiteral() {
        assertEquals(Map.of(2, 0, 3, 0, 4, 0), cover(
                "Процедура А()", "\tСообщить(\"// Cover:off\");", "\tВ();", "КонецПроцедуры"));
    }

    @Test
    void unclosedOffRunsToEndOfModule() {
        assertEquals(Map.of(2, 0), cover(
                "Процедура А()", "\tБ();", "\t// Cover:off", "\tВ();", "КонецПроцедуры"));
    }

    @Test
    void inlineOffExcludesOnlyItsLine() {
        assertEquals(Map.of(3, 0, 4, 0), cover(
                "Процедура А()", "\tБ(); // Cover:off", "\tВ();", "КонецПроцедуры"));
    }

    @Test
    void onClosesOnlyLastOpenRange() {
        assertEquals(Map.of(9, 0, 10, 0), cover(
                "Процедура А()", "\t// Cover:off", "\tБ();", "\t// Cover:off", "\tВ();", "\t// Cover:on",
                "\tГ();", "\t// Cover:on", "\tД();", "КонецПроцедуры"));
    }

    @Test
    void offIsStrongerThanAuto() {
        assertEquals(Map.of(4, -1, 6, 0), cover(
                "Процедура А()", "\t// Cover:auto", "\tБ(); // Cover:off", "\tВ();", "\t// Cover:on",
                "КонецПроцедуры"));
    }

    @Test
    void recordsMarkerInReport() {
        ExclusionReport report = new ExclusionReport();
        cover(report, "Процедура А()", "\tБ();", "\t// Cover:off — не тестируется", "\tВ();", "\t// Cover:on",
                "\tГ();", "КонецПроцедуры");

        assertEquals(4, report.getTotalLines());
        assertEquals(1, report.getExcludedLines());
        ExclusionReport.Entry entry = report.getEntries().iterator().next();
        assertEquals("Module.bsl:3 → Cover:off", entry.getSource());
        assertEquals("", entry.getTeam());
        assertEquals("не тестируется", entry.getReason());
        assertEquals(1, entry.getExcludedLines());
        assertEquals(Set.of("Module.bsl"), entry.getModules());
    }

    @Test
    void warnsAboutMarkerThatExcludesNothing() {
        ExclusionReport report = new ExclusionReport();
        cover(report, "Процедура А()", "\t// Cover:off", "\t// комментарий", "\t// Cover:on", "\tБ();",
                "КонецПроцедуры");

        assertTrue(report.getEntries().isEmpty());
        assertEquals(List.of("Module.bsl:2 → Cover:off does not exclude any line"), report.getWarnings());
    }

    @Test
    void parserWarningsNameTheModule() {
        ExclusionReport report = new ExclusionReport();
        cover(report, "Процедура А()", "\tБ();", "\t// Cover:off", "\tВ();", "КонецПроцедуры");

        assertEquals(List.of("Module.bsl:3: Cover:off is not closed, excluded up to end of module"),
                report.getWarnings());
    }

    @Test
    void lineRuleExcludesMatchingLinesAndIsReported() throws Exception {
        ExclusionReport report = new ExclusionReport();

        Map<Integer, Integer> cover = cover(
                rules("{'team':'Команда А','exclude':{'lines':['^\\\\s*Б\\\\(\\\\);']}}"), report, MODULE,
                text("Процедура А()", "\tБ();", "\tВ();", "\tб();", "КонецПроцедуры"));

        assertEquals(Map.of(3, 0, 5, 0), cover);
        ExclusionReport.Entry entry = report.getEntries().iterator().next();
        assertEquals("team.json → exclude.lines[0]", entry.getSource());
        assertEquals("Команда А", entry.getTeam());
        assertEquals("off", entry.getKind());
        assertEquals(2, entry.getExcludedLines());
    }

    @Test
    void autoLineRuleMarksLinesWithMinusOne() throws Exception {
        assertEquals(Map.of(2, 0, 3, -1, 4, 0), cover(rules("{'auto':{'lines':['В\\\\(\\\\)']}}"),
                new ExclusionReport(), MODULE, text("Процедура А()", "\tБ();", "\tВ();", "КонецПроцедуры")));
    }

    @Test
    void lineRuleSeesCrlfLinesWithoutCarriageReturn() throws Exception {
        assertEquals(Map.of(3, 0, 4, 0), cover(rules("{'exclude':{'lines':['Б\\\\(\\\\);$']}}"),
                new ExclusionReport(), MODULE, "Процедура А()\r\n\tБ();\r\n\tВ();\r\nКонецПроцедуры\r\n"));
    }

    @Test
    void lineRuleSeesFirstLineAfterByteOrderMark() throws Exception {
        assertEquals(Map.of(2, 0), cover(rules("{'exclude':{'lines':['^А = 1;$']}}"),
                new ExclusionReport(), MODULE, "\uFEFFА = 1;\nБ = 2;\n"));
    }

    @Test
    void moduleRuleExcludesWholeModule() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ModuleId module = new ModuleId("Catalogs/Справочник1/Ext/ObjectModule.bsl",
                List.of("Catalog.Справочник1.ObjectModule", "Справочник.Справочник1.ObjectModule"), Set.of());

        Map<Integer, Integer> cover = cover(rules("{'exclude':{'objects':['Catalog.Справочник1']}}"), report,
                module, text("Процедура А()", "\tБ();", "КонецПроцедуры"));

        assertEquals(Map.of(), cover);
        assertEquals(2, report.getTotalLines());
        assertEquals(2, report.getExcludedLines());
        ExclusionReport.Entry entry = report.getEntries().iterator().next();
        assertEquals("team.json → exclude.objects[0]", entry.getSource());
        assertEquals(Set.of("Catalogs/Справочник1/Ext/ObjectModule.bsl"), entry.getModules());
    }

    @Test
    void moduleRuleIsRecordedForModuleWithoutLines() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ModuleId module = new ModuleId("Catalogs/Справочник1/Ext/ObjectModule.bsl",
                List.of("Catalog.Справочник1.ObjectModule"), Set.of());

        cover(rules("{'exclude':{'objects':['Catalog.Справочник1']}}"), report, module, text("// пусто"));

        assertTrue(report.hasEntry("team.json → exclude.objects[0]"));
        assertEquals(0, report.getEntries().iterator().next().getExcludedLines());
    }

    @Test
    void ruleAndMarkerOnSameLineCountLineOnce() throws Exception {
        ExclusionReport report = new ExclusionReport();

        Map<Integer, Integer> cover = cover(rules("{'exclude':{'lines':['Б\\\\(\\\\);']}}"), report, MODULE,
                text("Процедура А()", "\tБ(); // Cover:off", "КонецПроцедуры"));

        assertEquals(Map.of(3, 0), cover);
        assertEquals(1, report.getExcludedLines());
        assertEquals(2, report.getEntries().size());
        report.getEntries().forEach(entry -> assertEquals(1, entry.getExcludedLines()));
    }

    @Test
    void lineRuleWithoutMatchesIsNotRecorded() throws Exception {
        ExclusionReport report = new ExclusionReport();

        cover(rules("{'exclude':{'lines':['Ж\\\\(\\\\);']}}"), report, MODULE,
                text("Процедура А()", "\tБ();", "КонецПроцедуры"));

        assertTrue(report.getEntries().isEmpty());
    }

    @Test
    void autoMarkerInsideModuleExcludedByRuleIsNotWarned() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ModuleId module = new ModuleId("CommonModules/уч_Сервер/Ext/Module.bsl",
                List.of("CommonModule.уч_Сервер.CommonModule"), Set.of());

        cover(rules("{'exclude':{'objects':['CommonModule.уч_*']}}"), report, module,
                text("Процедура А()", "\t// Cover:auto", "\tБ();", "\t// Cover:on", "КонецПроцедуры"));

        assertEquals(List.of(), report.getWarnings());
        assertTrue(report.hasEntry("CommonModules/уч_Сервер/Ext/Module.bsl:2 → Cover:auto"));
    }

    @Test
    void autoLineRuleOverlappedByOffMarkerIsRecorded() throws Exception {
        ExclusionReport report = new ExclusionReport();

        cover(rules("{'auto':{'lines':['Б\\\\(\\\\);']}}"), report, MODULE,
                text("Процедура А()", "\tБ(); // Cover:off", "КонецПроцедуры"));

        assertEquals(0, report.getEntries().stream()
                .filter(entry -> entry.getSource().equals("team.json → auto.lines[0]"))
                .findFirst().orElseThrow().getExcludedLines());
        assertEquals(0, report.getAutoLines());
        assertEquals(1, report.getExcludedLines());
        assertEquals(List.of(), report.getWarnings());
    }

    @Test
    void markerInModuleWithoutExecutableLinesExcludesNothing() {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), cover(report, "Перем А;", "// Cover:off", "Перем Б;", "// Cover:on"));
        assertTrue(report.getEntries().isEmpty());
    }

    @Test
    void unclosedMarkerInModuleWithoutExecutableLinesIsWarned() {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), cover(report, "// Cover:off"));
        assertEquals(List.of("Module.bsl:1: Cover:off is not closed, excluded up to end of module"), report.getWarnings());
    }

    @Test
    void inlineMarkerOnMultilineDeclarationExcludesProcedure() {
        assertEquals(Map.of(6, 0, 7, 0), cover(
                "Процедура А(Пар1, // Cover:off", "\tПар2)", "\tБ();", "КонецПроцедуры", "Процедура В()", "\tГ();",
                "КонецПроцедуры"));
    }

    @Test
    void inlineMarkerOnAsyncProcedureExcludesProcedure() {
        assertEquals(Map.of(5, 0, 6, 0), cover(
                "Асинх Процедура А() // Cover:off", "\tБ();", "КонецПроцедуры", "Процедура В()", "\tГ();",
                "КонецПроцедуры"));
    }

    @Test
    void inlineMarkerOnBranchExcludesOnlyThatLine() {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(2, 0, 3, 0, 5, 0, 6, 0, 7, 0), cover(report,
                "Процедура А()", "\tЕсли Б Тогда", "\t\tВ();", "\tИначе // Cover:off", "\t\tГ();", "\tКонецЕсли;",
                "КонецПроцедуры"));
        assertEquals(List.of("Module.bsl:4 → Cover:off does not exclude any line"), report.getWarnings());
    }

    @Test
    void inlineMarkerOnEndOfProcedureExcludesOnlyThatLine() {
        assertEquals(Map.of(2, 0), cover("Процедура А()", "\tБ();", "КонецПроцедуры // Cover:off"));
    }

    @Test
    void fileOffAfterLongHeaderExcludesWholeModule() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            lines.add("// Copyright, строка " + i);
        }
        lines.addAll(List.of("", "// Cover:file-off — сгенерировано", "Процедура А()", "\tБ();", "КонецПроцедуры"));

        assertEquals(Map.of(), cover(lines.toArray(new String[0])));
    }

    @Test
    void lineRuleCanRequireCase() throws Exception {
        assertEquals(Map.of(3, 0, 4, 0), cover(rules("{'exclude':{'lines':['(?-i)^\\\\s*ВызватьИсключение']}}"),
                new ExclusionReport(), MODULE,
                text("Процедура А()", "\tВызватьИсключение \"а\";", "\tвызватьисключение \"б\";", "КонецПроцедуры")));
    }

    // Строка «Исключение» не исполняемая: правило по строкам её не видит, ветку исключают маркерами.
    @Test
    void lineRuleDoesNotSeeExceptKeywordLine() throws Exception {
        ExclusionReport report = new ExclusionReport();

        Map<Integer, Integer> cover = cover(rules("{'auto':{'lines':['^\\\\s*Исключение\\\\s*$']}}"), report, MODULE,
                text("Процедура А()", "\tПопытка", "\t\tБ();", "\tИсключение", "\t\tВ();", "\tКонецПопытки;",
                        "КонецПроцедуры"));

        assertFalse(cover.containsKey(4));
        assertFalse(cover.containsValue(-1));
        assertTrue(report.getEntries().isEmpty());
    }

    // Исполняемые строки: 2, 4, 5, 7; блок доработки — строки 4 и 5.
    private static final String[] CHANGED = {
            "Процедура А()", "\tТиповой();", "\t// ++ уч", "\tНаш();", "\tВызватьИсключение \"Ошибка\";",
            "\t// -- уч", "КонецПроцедуры"};
    private static final ModuleId COMMON_MODULE =
            new ModuleId("Module.bsl", List.of("CommonModule.М.Module"), Set.of());
    private static final String EXCLUDED_WITH_MARKERS =
            "{'exclude':{'objects':['CommonModule.М']},'changeMarkers':{}}";

    @Test
    void changeBlockReturnsLinesOfModuleExcludedByRule() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(4, 0, 5, 0), cover(rules(EXCLUDED_WITH_MARKERS), report, COMMON_MODULE, text(CHANGED)));
        assertEquals(4, report.getTotalLines());
        assertEquals(2, report.getExcludedLines());
        assertEquals(2, report.getIncludedLines());
        ExclusionReport.Entry entry = report.getEntries().iterator().next();
        assertEquals("team.json → exclude.objects[0]", entry.getSource());
        assertEquals(2, entry.getExcludedLines());
        ExclusionReport.ChangeBlocksEntry blocks = report.getChangeBlocks().iterator().next();
        assertEquals("team.json → changeMarkers[0]", blocks.getSource());
        assertEquals(1, blocks.getBlocks());
        assertEquals(2, blocks.getIncludedLines());
        assertEquals(Set.of("Module.bsl"), blocks.getModules());
    }

    @Test
    void lineRuleStillAppliesInsideChangeBlock() throws Exception {
        ExclusionRules rules = rules("{'exclude':{'objects':['CommonModule.М'],'lines':['^\\\\s*ВызватьИсключение']},"
                + "'changeMarkers':{}}");

        assertEquals(Map.of(4, 0), cover(rules, new ExclusionReport(), COMMON_MODULE, text(CHANGED)));
    }

    @Test
    void coverOffStillAppliesInsideChangeBlock() throws Exception {
        String[] lines = CHANGED.clone();
        lines[3] = "\tНаш(); // Cover:off";

        assertEquals(Map.of(5, 0),
                cover(rules(EXCLUDED_WITH_MARKERS), new ExclusionReport(), COMMON_MODULE, text(lines)));
    }

    @Test
    void fileOffIsStrongerThanChangeBlock() throws Exception {
        String moduleText = "// Cover:file-off\n" + text(CHANGED);

        assertEquals(Map.of(), cover(rules(EXCLUDED_WITH_MARKERS), new ExclusionReport(), COMMON_MODULE, moduleText));
    }

    @Test
    void fileOffOutsideRecordsChangeBlockWithoutIncludingItsLines() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), outside(rules("{'changeMarkers':{}}"), report, "// Cover:file-off\n" + text(CHANGED)));
        assertEquals(0, report.getTotalLines());
        assertTrue(report.getEntries().isEmpty());
        ExclusionReport.ChangeBlocksEntry entry = report.getChangeBlocks().iterator().next();
        assertEquals(1, entry.getBlocks());
        assertEquals(0, entry.getIncludedLines());
        assertEquals(0, report.getIncludedLines());
    }

    @Test
    void coverOffOverWholeModuleOutsideStillCountsBlocks() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), outside(rules("{'changeMarkers':{}}"), report, "// Cover:off\n" + text(CHANGED)));
        assertEquals(1, report.getChangeBlocks().iterator().next().getBlocks());
        assertEquals(List.of("Module.bsl:1: Cover:off is not closed, excluded up to end of module"),
                report.getWarnings());
    }

    @Test
    void autoRuleLeavesOnlyLinesOutsideBlockOptional() throws Exception {
        ExclusionRules rules = rules("{'auto':{'objects':['CommonModule.М']},'changeMarkers':{}}");

        assertEquals(Map.of(2, -1, 4, 0, 5, 0, 7, -1),
                cover(rules, new ExclusionReport(), COMMON_MODULE, text(CHANGED)));
    }

    @Test
    void moduleOutsideKeepsOnlyChangeBlockLines() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(4, 0, 5, 0), outside(rules("{'changeMarkers':{}}"), report, text(CHANGED)));
        assertEquals(2, report.getTotalLines());
        assertEquals(0, report.getExcludedLines());
        assertEquals(2, report.getIncludedLines());
    }

    @Test
    void moduleOutsideReturnsInlineMarkerLines() throws Exception {
        ExclusionReport report = new ExclusionReport();
        Map<Integer, Integer> map = outside(rules("{'changeMarkers':{}}"), report,
                text("Процедура А() // ++ уч", "\tНаш();", "КонецПроцедуры // -- уч", "Процедура Б()", "\tЧужой();",
                        "КонецПроцедуры"));

        assertEquals(Map.of(2, 0, 3, 0), map);   // строка 1 не исполняемая, 3 — КонецПроцедуры входит
        assertEquals(2, report.getIncludedLines());
        assertTrue(report.getWarnings().isEmpty());
    }

    @Test
    void moduleOutsideWithoutBlocksIsEmptyAndSilent() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), outside(rules("{'changeMarkers':{}}"), report,
                text("Процедура А()", "\t// Cover:off", "\tБ();", "КонецПроцедуры")));
        assertTrue(report.getWarnings().isEmpty());
        assertEquals(0, report.getTotalLines());
        assertTrue(report.getChangeBlocks().isEmpty());
    }

    @Test
    void moduleOutsideDoesNotWarnAboutMarkersOutsideBlocks() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(6, 0), outside(rules("{'changeMarkers':{}}"), report, text("Процедура А()",
                "\t// Cover:off", "\tТиповой();", "\t// Cover:on", "\t// ++ уч", "\tНаш();", "\t// -- уч",
                "КонецПроцедуры")));
        assertTrue(report.getWarnings().isEmpty(), report.getWarnings().toString());
    }

    @Test
    void unclosedMarkerAfterChangeBlockDoesNotWarn() throws Exception {
        ExclusionReport report = new ExclusionReport();
        assertEquals(Map.of(3, 0), outside(rules("{'changeMarkers':{}}"), report, text("Процедура А()",
                "// ++", "Б();", "// --", "// Cover:off", "В();", "КонецПроцедуры")));
        assertEquals(List.of(), report.getWarnings());
    }

    @Test
    void markerPairAcrossScopeBoundariesStillExcludesIncludedLine() throws Exception {
        ExclusionReport report = new ExclusionReport();
        assertEquals(Map.of(), outside(rules("{'changeMarkers':{}}"), report, text("Процедура А()",
                "// Cover:off", "// ++", "Б();", "// --", "// Cover:on", "В();", "КонецПроцедуры")));
        assertEquals(List.of(), report.getWarnings());
        assertEquals(1, report.getExcludedLines());
    }

    @Test
    void changeBlockInModuleInScopeChangesNothing() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(2, 0, 4, 0, 5, 0, 7, 0),
                cover(rules("{'changeMarkers':{}}"), report, COMMON_MODULE, text(CHANGED)));
        assertEquals(0, report.getIncludedLines());
        ExclusionReport.ChangeBlocksEntry blocks = report.getChangeBlocks().iterator().next();
        assertEquals(1, blocks.getBlocks());
        assertEquals(0, blocks.getIncludedLines());
        assertTrue(blocks.getModules().isEmpty());
    }

    @Test
    void ruleWhoseLinesAreAllReturnedStaysInReport() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(3, 0, 4, 0), cover(rules(EXCLUDED_WITH_MARKERS), report, COMMON_MODULE,
                text("// ++ уч", "Процедура А()", "\tНаш();", "КонецПроцедуры", "// -- уч")));
        assertTrue(report.hasEntry("team.json → exclude.objects[0]"));
        assertEquals(0, report.getEntries().iterator().next().getExcludedLines());
    }

    @Test
    void unclosedChangeBlockIsWarnedInModuleInScope() throws Exception {
        ExclusionReport report = new ExclusionReport();

        cover(rules("{'changeMarkers':{}}"), report, COMMON_MODULE,
                text("Процедура А()", "\t// ++ уч", "\tБ();", "КонецПроцедуры"));

        assertEquals(List.of("Module.bsl:2 → team.json → changeMarkers[0]: block is not closed, not counted"),
                report.getWarnings());
    }

    @Test
    void changeBlocksWorkWithCrlfAndByteOrderMark() throws Exception {
        String moduleText = "\uFEFF// ++ уч\r\nПроцедура А()\r\n\tНаш();\r\nКонецПроцедуры\r\n// -- уч\r\n";

        assertEquals(Map.of(3, 0, 4, 0),
                cover(rules(EXCLUDED_WITH_MARKERS), new ExclusionReport(), COMMON_MODULE, moduleText));
    }

    @Test
    void automaticBlocksRespectExclusionsAndDoNotDoubleCount() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ExclusionRules rules = rules("{'changeMarkers':{},'unclosedBlocks':{'enabled':true},"
                + "'exclude':{'lines':['Skip']}}");
        Map<Integer, Integer> result = outside(rules, report, text("Процедура А()", "//++", "А();",
                "//++", "Б(); // Cover:auto", "Skip();", "В(); // Cover:off", "КонецПроцедуры"));
        assertEquals(Map.of(3, 0, 5, -1, 8, 0), result);
        assertEquals(3, report.getIncludedLines());
        assertTrue(report.getWarnings().isEmpty());
        assertEquals(2, report.getAutomaticallyClosedBlocks().size());
        assertEquals(3, report.getAutomaticallyClosedBlocks().get(0).getIncludedLines());
        assertEquals(2, report.getAutomaticallyClosedBlocks().get(1).getIncludedLines());
        Path json = tempDir.resolve("automatic-report.json");
        report.writeJson(json.toFile());
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(json.toFile());
        assertEquals(3, root.path("automaticallyClosedSummary").path("includedLines").asInt());
        assertEquals(2, root.path("automaticallyClosedSummary").path("blocks").asInt());
        assertTrue(root.path("automaticallyClosedBlocks").get(0).path("automaticallyClosed").asBoolean());
        assertEquals("method", root.path("automaticallyClosedBlocks").get(0).path("boundary").asText());
    }

    @Test
    void enabledPreservesClosedBlocksAndReportsZeroWhenAlreadyInScope() throws Exception {
        ExclusionReport report = new ExclusionReport();
        String module = text("Процедура А()", "//++", "А();", "//++", "Б();", "//--", "КонецПроцедуры");
        Map<Integer, Integer> result = cover(rules("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}"),
                report, COMMON_MODULE, module);
        assertEquals(cover(module.split("\n")), result);
        assertEquals(2, report.getChangeBlocks().iterator().next().getBlocks());
        assertEquals(1, report.getAutomaticallyClosedBlocks().size());
        assertEquals(0, report.getAutomaticallyClosedBlocks().get(0).getIncludedLines());
    }

    @Test
    void automaticBlockRestoresWholeModuleExclusionAndHonorsFileOff() throws Exception {
        String module = text("Процедура А()", "Типовой();", "//++", "Наш();", "КонецПроцедуры");
        ExclusionRules rules = rules("{'exclude':{'objects':['CommonModule.М']},'changeMarkers':{},"
                + "'unclosedBlocks':{'enabled':true}}");
        ExclusionReport report = new ExclusionReport();
        assertEquals(Map.of(4, 0, 5, 0), cover(rules, report, COMMON_MODULE, module));
        assertEquals(2, report.getIncludedLines());
        assertEquals(2, report.getAutomaticallyClosedBlocks().get(0).getIncludedLines());
        ExclusionReport offReport = new ExclusionReport();
        assertEquals(Map.of(), outside(rules, offReport, "// Cover:file-off\n" + module));
        assertEquals(0, offReport.getAutomaticallyClosedBlocks().get(0).getIncludedLines());
        assertEquals(0, offReport.getTotalLines());
    }

    private static final String ROUTINES = "{'includePrefix':['тест'],'prefixScope':{'routines':true}}";
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

    private static CF configuration;

    @BeforeAll
    static void readConfiguration() {
        configuration = (CF) MDClasses.createConfiguration(Path.of("src/test/resources/configuration"));
    }

    @Test
    void moduleOutsideScopeKeepsOnlyLinesOfPrefixedMethods() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(7, 0, 8, 0, 9, 0, 12, 0, 13, 0),
                outsideScope(scoped(ROUTINES), report, text(ROUTINE_MODULE)));
        assertEquals(5, report.getTotalLines());
        assertEquals(0, report.getIncludedLines());
        assertTrue(report.getWarnings().isEmpty(), report.getWarnings().toString());
        ExclusionReport.PrefixRoutinesEntry entry = report.getPrefixRoutines().iterator().next();
        assertEquals("team.json → includePrefix[0]", entry.getSource());
        assertEquals(2, entry.getRoutines());
        assertEquals(5, entry.getIncludedLines());
        assertEquals(Set.of("Module.bsl"), entry.getModules());
        assertTrue(report.moduleHasPrefixRoutines("Module.bsl"));
    }

    @Test
    void moduleOutsideScopeWithoutPrefixedMethodsIsEmptyAndSilent() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), outsideScope(scoped(ROUTINES), report, text("Процедура А()", "\tБ();", "КонецПроцедуры")));
        assertEquals(0, report.getTotalLines());
        assertTrue(report.getPrefixRoutines().isEmpty());
        assertFalse(report.moduleHasPrefixRoutines("Module.bsl"));
    }

    @Test
    void markersAndLineRulesApplyInsidePrefixedMethods() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ExclusionRules rules = scoped("{'includePrefix':['тест'],'prefixScope':{'routines':true},"
                + "'exclude':{'lines':['^\\\\s*Г\\\\(']}}");
        String[] module = ROUTINE_MODULE.clone();
        module[6] = "\tВ(); // Cover:off";

        assertEquals(Map.of(9, 0, 12, 0, 13, 0), outsideScope(rules, report, text(module)));
        assertEquals(5, report.getTotalLines());
        assertEquals(2, report.getExcludedLines());
        assertEquals(3, report.getPrefixRoutines().iterator().next().getIncludedLines());
        assertTrue(report.hasEntry("team.json → exclude.lines[0]"));
    }

    @Test
    void moduleRuleExcludesPrefixedMethodsButNotChangeBlocks() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ExclusionRules rules = scoped("{'includePrefix':['тест'],'prefixScope':{'routines':true},"
                + "'exclude':{'objects':['CommonModule.М']},'changeMarkers':{}}");

        assertEquals(Map.of(4, 0), outsideScope(rules, report, text("Процедура тестХ()", "\tА();", "\t// ++ уч",
                "\tБ();", "\t// -- уч", "КонецПроцедуры")));
        assertEquals(2, report.getEntries().iterator().next().getExcludedLines());
        assertEquals(1, report.getIncludedLines());
        assertEquals(1, report.getPrefixRoutines().iterator().next().getIncludedLines());
    }

    @Test
    void autoRuleMakesPrefixedMethodLinesOptional() throws Exception {
        ExclusionRules rules = scoped("{'includePrefix':['тест'],'prefixScope':{'routines':true},"
                + "'auto':{'objects':['CommonModule.М']}}");

        assertEquals(Map.of(2, -1, 3, -1), outsideScope(rules, new ExclusionReport(),
                text("Процедура тестХ()", "\tА();", "КонецПроцедуры")));
    }

    @Test
    void unclosedMarkerInPrefixedMethodOutsideScopeIsWarned() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), outsideScope(scoped(ROUTINES), report,
                text("Процедура тестХ()", "// Cover:off", "\tА();", "КонецПроцедуры")));
        assertEquals(List.of("Module.bsl:2: Cover:off is not closed, excluded up to end of module"), report.getWarnings());
    }

    @Test
    void fileOffBeatsPrefixedMethods() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), outsideScope(scoped(ROUTINES), report,
                text("// Cover:file-off", "Процедура тестХ()", "\tА();", "КонецПроцедуры")));
        assertEquals(0, report.getTotalLines());
        assertTrue(report.getPrefixRoutines().isEmpty());
        assertFalse(report.moduleHasPrefixRoutines("Module.bsl"));
    }

    @Test
    void moduleInScopeIsNotNarrowedToPrefixedMethods() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ModuleId own = new ModuleId("Module.bsl", List.of("Catalog.тестТовары.ObjectModule"), Set.of());

        assertEquals(Map.of(3, 0, 4, 0, 7, 0, 8, 0, 9, 0, 12, 0, 13, 0, 15, 0),
                cover(scoped(ROUTINES), report, own, text(ROUTINE_MODULE)));
        assertTrue(report.getPrefixRoutines().isEmpty());
    }

    @Test
    void moduleUnderRemoveSupportIgnoresPrefixedMethods() throws Exception {
        assertEquals(Map.of(), outside(scoped(ROUTINES), new ExclusionReport(), text(ROUTINE_MODULE)));
    }

    @Test
    void lineInMethodAndChangeBlockIsCountedOnce() throws Exception {
        ExclusionReport report = new ExclusionReport();
        ExclusionRules rules = scoped("{'includePrefix':['тест'],'prefixScope':{'routines':true},'changeMarkers':{}}");

        assertEquals(Map.of(3, 0, 5, 0, 6, 0), outsideScope(rules, report, text("Процедура тестХ()", "\t// ++ уч",
                "\tА();", "\t// -- уч", "\tБ();", "КонецПроцедуры")));
        assertEquals(3, report.getTotalLines());
        assertEquals(1, report.getIncludedLines());
        assertEquals(3, report.getPrefixRoutines().iterator().next().getIncludedLines());
    }

    @Test
    void methodWithoutEndIsReportedAsWarning() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(), outsideScope(scoped(ROUTINES), report, text("Процедура тестХ()", "\tА();", "",
                "Процедура Б()", "\tВ();", "КонецПроцедуры")));
        assertEquals(List.of("Module.bsl:1 → team.json → includePrefix[0]: method тестХ has no end, not counted"),
                report.getWarnings());
    }

    @Test
    void methodWithoutExecutableLinesStillCountsAsMatch() throws Exception {
        ExclusionReport report = new ExclusionReport();

        assertEquals(Map.of(2, 0), outsideScope(scoped(ROUTINES), report, text("Процедура тестХ()", "КонецПроцедуры")));
        ExclusionReport report2 = new ExclusionReport();
        ExclusionRules excluded = scoped("{'includePrefix':['тест'],'prefixScope':{'routines':true},"
                + "'exclude':{'objects':['CommonModule.М']}}");
        assertEquals(Map.of(), outsideScope(excluded, report2, text("Процедура тестХ()", "\tА();", "КонецПроцедуры")));
        assertTrue(report2.moduleHasPrefixRoutines("Module.bsl"));
        ExclusionReport.PrefixRoutinesEntry entry = report2.getPrefixRoutines().iterator().next();
        assertEquals(1, entry.getRoutines());
        assertEquals(0, entry.getIncludedLines());
        assertTrue(entry.getModules().isEmpty());
    }

    private static Map<Integer, Integer> outsideScope(ExclusionRules rules, ExclusionReport report, String moduleText) {
        Map<Integer, Integer> result = new TreeMap<>();
        CoverMapBuilder.buildOutsideScope(moduleText, COMMON_MODULE, rules, report)
                .forEach((line, value) -> result.put(line.intValue(), value));
        return result;
    }

    private ExclusionRules scoped(String json) throws IOException {
        Path file = tempDir.resolve("team.json");
        Files.writeString(file, json.replace('\'', '"'));
        return ExclusionRules.load(file.toFile(), tempDir, configuration, new ExclusionReport());
    }

    private static Map<Integer, Integer> outside(ExclusionRules rules, ExclusionReport report, String moduleText) {
        Map<Integer, Integer> result = new TreeMap<>();
        CoverMapBuilder.build(moduleText, COMMON_MODULE, rules, report, true)
                .forEach((line, value) -> result.put(line.intValue(), value));
        return result;
    }

    private static final ModuleId MODULE = new ModuleId("Module.bsl", List.of(), Set.of());

    @TempDir
    Path tempDir;

    static Map<Integer, Integer> cover(String... lines) {
        return cover(new ExclusionReport(), lines);
    }

    static Map<Integer, Integer> cover(ExclusionReport report, String... lines) {
        return cover(ExclusionRules.empty(), report, MODULE, text(lines));
    }

    static Map<Integer, Integer> cover(ExclusionRules rules, ExclusionReport report, ModuleId module,
                                       String moduleText) {
        Map<Integer, Integer> result = new TreeMap<>();
        CoverMapBuilder.build(moduleText, module, rules, report)
                .forEach((line, value) -> result.put(line.intValue(), value));
        return result;
    }

    static String text(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private ExclusionRules rules(String json) throws IOException {
        Path file = tempDir.resolve("team.json");
        Files.writeString(file, json.replace('\'', '"'));
        return ExclusionRules.load(file.toFile(), tempDir, null, new ExclusionReport());
    }
}
