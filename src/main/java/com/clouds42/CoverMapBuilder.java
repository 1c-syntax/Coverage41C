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

import com.github._1c_syntax.bsl.parser.BSLTokenizer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

final class CoverMapBuilder {

    private CoverMapBuilder() {
    }

    static Map<BigDecimal, Integer> build(String moduleText, ModuleId module, ExclusionRules rules,
                                          ExclusionReport report) {
        return build(moduleText, module, rules, report, false, false);
    }

    // A module outside coverage (out of scope or under --removeSupport) gives only the lines of its change blocks.
    static Map<BigDecimal, Integer> build(String moduleText, ModuleId module, ExclusionRules rules,
                                          ExclusionReport report, boolean outside) {
        return build(moduleText, module, rules, report, outside, false);
    }

    // A module outside includeSubsystems/includePrefix: change blocks and, with prefixScope.routines,
    // procedures and functions named with a prefix.
    static Map<BigDecimal, Integer> buildOutsideScope(String moduleText, ModuleId module, ExclusionRules rules,
                                                      ExclusionReport report) {
        return build(moduleText, module, rules, report, true, rules.scopesRoutines());
    }

    private static Map<BigDecimal, Integer> build(String moduleText, ModuleId module, ExclusionRules rules,
                                                  ExclusionReport report, boolean outside, boolean routines) {
        String[] textLines = textLines(moduleText);
        if (outside && rules.getChangeMarkers().isEmpty() && !routines) {
            return new HashMap<>();
        }
        BSLTokenizer tokenizer = LinesToCoverage.createTokenizer(moduleText);
        ChangeBlocks blocks = rules.getChangeMarkers().isEmpty()
                ? ChangeBlocks.NONE
                : ChangeBlocks.parse(textLines, rules.getChangeMarkers(), module.getPath(),
                        tokenizer::getTokens, tokenizer::getAst);
        blocks.getWarnings().forEach(report::warn);
        PrefixRoutines found = routines
                ? PrefixRoutines.parse(moduleText, tokenizer::getAst, rules.getPrefixes(), rules.getRoutinePrecheck(),
                        module.getPath())
                : PrefixRoutines.NONE;
        found.getWarnings().forEach(report::warn);
        if (outside && blocks.isEmpty() && found.isEmpty()) {
            return new HashMap<>();
        }
        CoverageMarkers markers = CoverageMarkers.parse(tokenizer);
        // Cover:file-off in a module outside coverage keeps it out of the totals, as today.
        List<CoverageMarker> fileOff = outside
                ? markers.getMarkers().stream().filter(CoverageMarker::isFileOff).toList()
                : List.of();
        if (!fileOff.isEmpty()) {
            for (CoverageMarker marker : fileOff) {
                Source source = Source.marker(module, marker);
                report.record(source.name, source.team, source.reason, source.kind, module.getPath(), 0);
            }
            recordMarkerWarnings(markers, blocks, found, module, report, outside);
            recordChangeBlocks(blocks, module, report, Map.of());
            recordPrefixRoutines(found, module, report, Map.of(), 0, false);
            recordAutomaticallyClosedBlocks(blocks, module, report, Set.of());
            return new HashMap<>();
        }

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());
        PrefixRoutines.LineSweep routineSweep = found.isEmpty() ? null : found.lineSweep();
        if (routineSweep != null) {
            Arrays.sort(linesToCover);
        }
        List<Source> sources = new ArrayList<>();
        boolean hasModuleRule = false;
        // Rules over the whole module exclude lines of prefixed methods, but not lines of change blocks.
        if (!outside || !found.isEmpty()) {
            for (ExclusionRules.Rule rule : rules.getRules()) {
                if (!rule.isLineRule() && rule.matches(module)) {
                    // Change blocks take their lines back from a rule that excludes the whole module.
                    sources.add(Source.rule(rule, true, line -> !blocks.covers(line)));
                    hasModuleRule = true;
                }
            }
        }
        if (linesToCover.length > 0) {
            markers.getMarkers().forEach(marker -> sources.add(Source.marker(module, marker)));
            for (ExclusionRules.Rule rule : rules.getRules()) {
                if (rule.isLineRule()) {
                    sources.add(Source.rule(rule, false,
                            line -> line <= textLines.length && rule.matchesLine(textLines[line - 1])));
                }
            }
        }

        Map<BigDecimal, Integer> coverMap = new HashMap<>();
        Map<String, Integer> includedBySource = new HashMap<>();
        int counted = 0;
        int excluded = 0;
        int auto = 0;
        Set<Integer> linesReturnedByBlocks = blocks.isEmpty() ? Set.of() : new HashSet<>();
        int prefixedLines = 0;
        Map<String, Integer> routineLines = new HashMap<>();
        List<Source> matchedSources = new ArrayList<>();
        for (int line : linesToCover) {
            boolean inBlock = blocks.covers(line);
            Set<String> prefixSources = routineSweep == null ? Set.of() : routineSweep.prefixSourcesAt(line);
            if (outside && !inBlock && prefixSources.isEmpty()) {
                continue;
            }
            counted++;
            CoverageMarker.Kind kind = applyRules(sources, matchedSources, line);
            if (kind == CoverageMarker.Kind.OFF) {
                excluded++;
                continue;
            }
            if (kind == CoverageMarker.Kind.AUTO) {
                auto++;
                coverMap.put(new BigDecimal(line), -1);
            } else {
                coverMap.put(new BigDecimal(line), 0);
            }
            if (inBlock && (hasModuleRule || (outside && prefixSources.isEmpty()))) {
                linesReturnedByBlocks.add(line);
                blocks.sourcesCovering(line).forEach(source -> includedBySource.merge(source, 1, Integer::sum));
            }
            if (!prefixSources.isEmpty()) {
                prefixedLines++;
                prefixSources.forEach(source -> routineLines.merge(source, 1, Integer::sum));
            }
        }
        recordMarkerWarnings(markers, blocks, found, module, report, outside);
        report.addModuleTotals(counted, excluded, auto);
        report.addIncludedLines(module.getPath(), linesReturnedByBlocks.size());
        recordChangeBlocks(blocks, module, report, includedBySource);
        recordPrefixRoutines(found, module, report, routineLines, prefixedLines, true);

        recordAutomaticallyClosedBlocks(blocks, module, report, linesReturnedByBlocks);
        for (Source source : sources) {
            if (source.matched || source.recordWithoutHits) {
                report.record(source.name, source.team, source.reason, source.kind, module.getPath(), source.hits);
            } else if (source.marker && !outside) {
                report.warn(source.name + " does not exclude any line");
            }
        }
        return coverMap;
    }

    private static void recordMarkerWarnings(CoverageMarkers markers, ChangeBlocks blocks, PrefixRoutines found,
                                             ModuleId module, ExclusionReport report, boolean outside) {
        // Parse the whole module so markers can pair across scope boundaries. Report only
        // diagnostics whose location or affected range intersects a selected block or method.
        List<String> warnings = outside
                ? markers.getWarningsIntersecting((first, last) -> blocks.intersects(first, last)
                        || found.intersects(first, last))
                : markers.getWarnings();
        warnings.forEach(warning -> report.warn(module.getPath() + ":" + warning));
    }

    private static void recordPrefixRoutines(PrefixRoutines found, ModuleId module, ExclusionReport report,
                                             Map<String, Integer> routineLines, int prefixedLines, boolean inScope) {
        if (found.isEmpty()) {
            return;
        }
        Map<String, Integer> routinesBySource = new LinkedHashMap<>();
        found.getRoutines().forEach(routine -> routine.getPrefixes()
                .forEach(prefix -> routinesBySource.merge(prefix.getSource(), 1, Integer::sum)));
        report.addPrefixRoutineTotals(module.getPath(), found.getRoutines().size(), prefixedLines, inScope);
        routinesBySource.forEach((source, count) -> report.recordPrefixRoutines(source, module.getPath(), count,
                routineLines.getOrDefault(source, 0)));
    }

    private static void recordChangeBlocks(ChangeBlocks blocks, ModuleId module, ExclusionReport report,
                                           Map<String, Integer> includedBySource) {
        blocks.bySource().forEach((source, sourceBlocks) -> report.recordChangeBlocks(source,
                sourceBlocks.get(0).getMarker().getTeam(), module.getPath(), sourceBlocks.size(),
                includedBySource.getOrDefault(source, 0)));
    }

    private static void recordAutomaticallyClosedBlocks(ChangeBlocks blocks, ModuleId module,
                                                         ExclusionReport report,
                                                         Set<Integer> linesReturnedByBlocks) {
        blocks.bySource().values().forEach(sourceBlocks -> sourceBlocks.stream()
                .filter(ChangeBlocks.Block::isAutomaticallyClosed).forEach(block -> {
                    Set<Integer> included = new HashSet<>();
                    linesReturnedByBlocks.stream().filter(block::covers).forEach(included::add);
                    report.recordAutomaticallyClosedBlock(module.getPath(), block, included);
                }));
    }

    private static String[] textLines(String moduleText) {
        String[] lines = moduleText.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].endsWith("\r")) {
                lines[index] = lines[index].substring(0, lines[index].length() - 1);
            }
        }
        if (lines[0].startsWith("\uFEFF")) {
            lines[0] = lines[0].substring(1);
        }
        return lines;
    }

    private static CoverageMarker.Kind applyRules(List<Source> sources, List<Source> matchedSources, int line) {
        matchedSources.clear();
        CoverageMarker.Kind kind = null;
        for (Source source : sources) {
            if (source.covers.test(line)) {
                source.matched = true;
                matchedSources.add(source);
                if (kind == null || source.kind == CoverageMarker.Kind.OFF) {
                    kind = source.kind;
                }
            }
        }
        for (Source source : matchedSources) {
            if (source.kind == kind) {
                source.hits++;
            }
        }
        return kind;
    }

    private static final class Source {
        private final String name;
        private final String team;
        private final String reason;
        private final CoverageMarker.Kind kind;
        private final boolean marker;
        private final boolean recordWithoutHits;
        private final IntPredicate covers;
        private int hits;
        private boolean matched;

        private Source(String name, String team, String reason, CoverageMarker.Kind kind,
                       boolean marker, boolean recordWithoutHits, IntPredicate covers) {
            this.name = name;
            this.team = team;
            this.reason = reason;
            this.kind = kind;
            this.marker = marker;
            this.recordWithoutHits = recordWithoutHits;
            this.covers = covers;
        }

        private static Source rule(ExclusionRules.Rule rule, boolean recordWithoutHits, IntPredicate covers) {
            return new Source(rule.getSource(), rule.getTeam(), "", rule.getKind(), false, recordWithoutHits, covers);
        }

        private static Source marker(ModuleId module, CoverageMarker marker) {
            return new Source(module.getPath() + ":" + marker.getLine() + " → " + marker.getLabel(),
                    "", marker.getReason(), marker.getKind(), true, false, marker::covers);
        }
    }
}
