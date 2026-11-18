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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusionReportTest {

    private static final String SOURCE = "a.json → exclude.paths[0]";

    @TempDir
    Path tempDir;

    @Test
    void accumulatesLinesAndModulesBySource() {
        ExclusionReport report = new ExclusionReport();
        report.record(SOURCE, "Команда А", "", CoverageMarker.Kind.OFF, "B/Module.bsl", 3);
        report.record(SOURCE, "Команда А", "", CoverageMarker.Kind.OFF, "A/Module.bsl", 2);

        assertEquals(1, report.getEntries().size());
        ExclusionReport.Entry entry = report.getEntries().iterator().next();
        assertEquals(5, entry.getExcludedLines());
        assertEquals(List.of("A/Module.bsl", "B/Module.bsl"), new ArrayList<>(entry.getModules()));
        assertEquals("off", entry.getKind());
        assertEquals("Команда А", entry.getTeam());
        assertTrue(report.hasEntry(SOURCE));
        assertFalse(report.hasEntry("b.json → exclude.paths[0]"));
    }

    @Test
    void shareCountsExcludedAndAutoAgainstAllLines() {
        ExclusionReport report = new ExclusionReport();
        report.addModuleTotals(10, 2, 1);
        report.addModuleTotals(10, 0, 0);

        assertEquals(20, report.getTotalLines());
        assertEquals(2, report.getExcludedLines());
        assertEquals(1, report.getAutoLines());
        assertEquals(0.15, report.getExcludedShare());
    }

    @Test
    void shareIsZeroWithoutLines() {
        assertEquals(0.0, new ExclusionReport().getExcludedShare());
    }

    @Test
    void keepsWarnings() {
        ExclusionReport report = new ExclusionReport();
        report.warn("a.json: unknown key foo");
        assertEquals(List.of("a.json: unknown key foo"), report.getWarnings());
    }

    @Test
    void writesJson() throws Exception {
        ExclusionReport report = new ExclusionReport();
        report.record(SOURCE, "Команда А", "", CoverageMarker.Kind.AUTO, "A/Module.bsl", 1);
        report.addModuleTotals(152, 0, 1);
        report.warn("b.json → exclude.objects[0]: rule does not match anything");
        File file = tempDir.resolve("report.json").toFile();

        report.writeJson(file);

        JsonNode root = new ObjectMapper().readTree(file);
        assertEquals(List.of("totalLines", "excludedLines", "autoLines", "includedLines", "excludedShare", "entries",
                "changeBlocks", "automaticallyClosedBlocks", "automaticallyClosedSummary", "prefixRoutines", "warnings",
                "scope"), fieldNames(root));
        assertEquals(152, root.get("totalLines").asInt());
        assertEquals(0, root.get("excludedLines").asInt());
        assertEquals(1, root.get("autoLines").asInt());
        assertEquals(0.0066, root.get("excludedShare").asDouble());
        JsonNode entry = root.get("entries").get(0);
        assertEquals(List.of("source", "team", "reason", "kind", "excludedLines", "modules"), fieldNames(entry));
        assertEquals(SOURCE, entry.get("source").asText());
        assertEquals("auto", entry.get("kind").asText());
        assertEquals("A/Module.bsl", entry.get("modules").get(0).asText());
        assertEquals("b.json → exclude.objects[0]: rule does not match anything",
                root.get("warnings").get(0).asText());
    }

    @Test
    void writesCoverageScope() throws Exception {
        ExclusionReport report = new ExclusionReport();
        report.addScopeSource("a.json → includeSubsystems[0]");
        report.addModuleOutOfScope();
        report.addModuleOutOfScope();
        File file = tempDir.resolve("report.json").toFile();

        report.writeJson(file);

        JsonNode scope = new ObjectMapper().readTree(file).get("scope");
        assertEquals("a.json → includeSubsystems[0]", scope.get("sources").get(0).asText());
        assertEquals(2, scope.get("modulesOutOfScope").asInt());
    }

    @Test
    void writesPrefixScopeAndPrefixRoutines() throws Exception {
        ExclusionReport report = new ExclusionReport();
        report.setPrefixScope(new ExclusionRules.PrefixScope(true, false, true, true, List.of("a.json → prefixScope")));
        report.recordPrefixRoutines("a.json → includePrefix[0]", "B/Module.bsl", 2, 5);
        report.recordPrefixRoutines("a.json → includePrefix[0]", "A/Module.bsl", 1, 0);
        File file = tempDir.resolve("report.json").toFile();

        report.writeJson(file);

        JsonNode root = new ObjectMapper().readTree(file);
        JsonNode scope = root.get("scope").get("prefixScope");
        assertTrue(scope.get("subsystems").asBoolean());
        assertFalse(scope.get("includeSubsystemContents").asBoolean());
        assertTrue(scope.get("modules").asBoolean());
        assertTrue(scope.get("routines").asBoolean());
        assertEquals("a.json → prefixScope", scope.get("sources").get(0).asText());
        JsonNode routines = root.get("prefixRoutines").get(0);
        assertEquals("a.json → includePrefix[0]", routines.get("source").asText());
        assertEquals(3, routines.get("routines").asInt());
        assertEquals(5, routines.get("includedLines").asInt());
        assertEquals(1, routines.get("modules").size());
        assertEquals("B/Module.bsl", routines.get("modules").get(0).asText());
        assertEquals(List.of("source", "routines", "includedLines", "modules"), fieldNames(routines));
        assertTrue(report.hasPrefixRoutines("a.json → includePrefix[0]"));
        assertFalse(report.hasPrefixRoutines("a.json → includePrefix[1]"));
        assertTrue(report.moduleHasPrefixRoutines("A/Module.bsl"));
        assertEquals(2, report.prefixRoutines("a.json → includePrefix[0]").getRoutineModules());
    }

    @Test
    void reportWithoutPrefixScopeHasEmptyRoutinesAndNoScopeObject() throws Exception {
        ExclusionReport report = new ExclusionReport();
        File file = tempDir.resolve("report.json").toFile();

        report.writeJson(file);

        JsonNode root = new ObjectMapper().readTree(file);
        assertEquals(0, root.get("prefixRoutines").size());
        assertFalse(root.get("scope").has("prefixScope"));
    }

    @Test
    void accumulatesChangeBlocksBySource() {
        ExclusionReport report = new ExclusionReport();
        String source = "a.json → changeMarkers[0]";

        report.recordChangeBlocks(source, "Команда А", "B/Module.bsl", 2, 3);
        report.recordChangeBlocks(source, "Команда А", "A/Module.bsl", 1, 0);
        report.addIncludedLines("B/Module.bsl", 3);

        ExclusionReport.ChangeBlocksEntry entry = report.getChangeBlocks().iterator().next();
        assertEquals(3, entry.getBlocks());
        assertEquals(3, entry.getIncludedLines());
        assertEquals(List.of("B/Module.bsl"), new ArrayList<>(entry.getModules()));
        assertEquals("Команда А", entry.getTeam());
        assertEquals(3, report.getIncludedLines());
        assertTrue(report.hasChangeBlocks(source));
        assertFalse(report.hasChangeBlocks("b.json → changeMarkers[0]"));
    }

    @Test
    void sourceWithoutClosedBlocksHasNoChangeBlocks() {
        ExclusionReport report = new ExclusionReport();

        report.recordChangeBlocks("a.json → changeMarkers[0]", "", "A/Module.bsl", 0, 0);

        assertFalse(report.hasChangeBlocks("a.json → changeMarkers[0]"));
    }

    @Test
    void writesChangeBlocks() throws Exception {
        ExclusionReport report = new ExclusionReport();
        report.recordChangeBlocks("a.json → changeMarkers[0]", "Команда А", "A/Module.bsl", 1, 2);
        report.addIncludedLines("A/Module.bsl", 2);
        File file = tempDir.resolve("report.json").toFile();

        report.writeJson(file);

        JsonNode root = new ObjectMapper().readTree(file);
        assertEquals(2, root.get("includedLines").asInt());
        JsonNode entry = root.get("changeBlocks").get(0);
        assertEquals(List.of("source", "team", "blocks", "includedLines", "modules"), fieldNames(entry));
        assertEquals(1, entry.get("blocks").asInt());
        assertEquals("A/Module.bsl", entry.get("modules").get(0).asText());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
