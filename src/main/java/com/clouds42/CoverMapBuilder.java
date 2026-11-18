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
        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());
        CoverageMarkers markers = linesToCover.length > 0 ? CoverageMarkers.parse(tokenizer) : null;
        // Cover:file-off in a module outside coverage keeps it out of the totals, as today.
        if (outside && markers != null && markers.getMarkers().stream().anyMatch(CoverageMarker::isFileOff)) {
            recordAutomaticallyClosedBlocks(blocks, module, report, new HashMap<>(), false);
            return new HashMap<>();
        }

        List<Source> sources = new ArrayList<>();
        boolean excludedWhole = outside;
        // Rules over the whole module exclude lines of prefixed methods, but not lines of change blocks.
        if (!outside || !found.isEmpty()) {
            for (ExclusionRules.Rule rule : rules.getRules()) {
                if (!rule.isLineRule() && rule.matches(module)) {
                    // Change blocks take their lines back from a rule that excludes the whole module.
                    sources.add(Source.rule(rule, true, line -> !blocks.covers(line)));
                    excludedWhole = true;
                }
            }
        }
        if (markers != null) {
            if (!outside) {
                markers.getWarnings().forEach(warning -> report.warn(module.getPath() + ":" + warning));
            }
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
        int included = 0;
        Map<String, Integer> routineLines = new HashMap<>();
        for (int line : linesToCover) {
            boolean inBlock = blocks.covers(line);
            PrefixRoutines.Routine routine = found.routineAt(line);
            if (outside && !inBlock && routine == null) {
                continue;
            }
            counted++;
            match(sources, line);
            if (hit(sources, CoverageMarker.Kind.OFF, line)) {
                excluded++;
                continue;
            }
            if (hit(sources, CoverageMarker.Kind.AUTO, line)) {
                auto++;
                coverMap.put(new BigDecimal(line), -1);
            } else {
                coverMap.put(new BigDecimal(line), 0);
            }
            if (excludedWhole && inBlock) {
                included++;
                blocks.sourcesCovering(line).forEach(source -> includedBySource.merge(source, 1, Integer::sum));
            }
            if (routine != null) {
                routineLines.merge(routine.getPrefix().getSource(), 1, Integer::sum);
            }
        }
        report.addModuleTotals(counted, excluded, auto);
        report.addIncludedLines(module.getPath(), included);
        blocks.bySource().forEach((source, sourceBlocks) -> report.recordChangeBlocks(source,
                sourceBlocks.get(0).getMarker().getTeam(), module.getPath(), sourceBlocks.size(),
                includedBySource.getOrDefault(source, 0)));

        Map<String, Integer> routinesBySource = new LinkedHashMap<>();
        found.getRoutines().forEach(routine -> routinesBySource.merge(routine.getPrefix().getSource(), 1, Integer::sum));
        routinesBySource.forEach((source, count) -> report.recordPrefixRoutines(source, module.getPath(), count,
                routineLines.getOrDefault(source, 0)));

        recordAutomaticallyClosedBlocks(blocks, module, report, coverMap, excludedWhole);
        for (Source source : sources) {
            if (source.matched || source.recordWithoutHits) {
                report.record(source.name, source.team, source.reason, source.kind, module.getPath(), source.hits);
            } else if (source.marker && !outside) {
                report.warn(source.name + " does not exclude any line");
            }
        }
        return coverMap;
    }

    private static void recordAutomaticallyClosedBlocks(ChangeBlocks blocks, ModuleId module,
                                                         ExclusionReport report,
                                                         Map<BigDecimal, Integer> coverMap, boolean excludedWhole) {
        blocks.bySource().values().forEach(sourceBlocks -> sourceBlocks.stream()
                .filter(ChangeBlocks.Block::isAutomaticallyClosed).forEach(block -> {
                    Set<Integer> included = new HashSet<>();
                    if (excludedWhole) {
                        coverMap.keySet().stream().map(BigDecimal::intValue).filter(block::covers)
                                .forEach(included::add);
                    }
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

    private static void match(List<Source> sources, int line) {
        for (Source source : sources) {
            if (source.covers.test(line)) {
                source.matched = true;
            }
        }
    }

    private static boolean hit(List<Source> sources, CoverageMarker.Kind kind, int line) {
        boolean hit = false;
        for (Source source : sources) {
            if (source.kind == kind && source.covers.test(line)) {
                source.hits++;
                hit = true;
            }
        }
        return hit;
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
