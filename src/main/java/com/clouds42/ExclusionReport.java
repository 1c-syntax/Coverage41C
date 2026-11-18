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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class ExclusionReport {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final int SUMMARY_TOP = 10;

    private final Map<String, Entry> entries = new TreeMap<>();
    private final List<String> warnings = new ArrayList<>();
    private long totalLines;
    private long excludedLines;
    private long autoLines;
    private final List<String> scopeSources = new ArrayList<>();
    private final Map<String, Boolean> scopeMatches = new TreeMap<>();
    private final Map<String, String> exactSubsystemNames = new TreeMap<>();
    private final Set<String> matchedExactSubsystems = new TreeSet<>();
    private long modulesOutOfScope;
    private final Map<String, ChangeBlocksEntry> changeBlocks = new TreeMap<>();
    private final Set<String> includedModules = new TreeSet<>();
    private long includedLines;
    private final List<AutomaticallyClosedBlock> automaticallyClosedBlocks = new ArrayList<>();
    private final Set<String> automaticallyIncludedLines = new TreeSet<>();
    private final Map<String, PrefixRoutinesEntry> prefixRoutines = new TreeMap<>();
    private final Set<String> routineModules = new TreeSet<>();
    private long totalPrefixRoutines;
    private long totalPrefixRoutineLines;
    private ExclusionRules.PrefixScope prefixScope;

    void setPrefixScope(ExclusionRules.PrefixScope prefixScope) {
        this.prefixScope = prefixScope;
    }

    // Methods named with a prefix in a module outside scope; lines are those left in the coverage map.
    void recordPrefixRoutines(String source, String module, int routines, int lines) {
        PrefixRoutinesEntry entry = prefixRoutines.computeIfAbsent(source, PrefixRoutinesEntry::new);
        entry.routines += routines;
        entry.includedLines += lines;
        if (routines > 0) {
            entry.routineModules.add(module);
            routineModules.add(module);
        }
        if (lines > 0) {
            entry.modules.add(module);
        }
    }

    // Each module contributes these totals once even if one method matches several prefixes.
    void addPrefixRoutineTotals(int routines, int lines) {
        totalPrefixRoutines += routines;
        totalPrefixRoutineLines += lines;
    }

    boolean hasPrefixRoutines(String source) {
        PrefixRoutinesEntry entry = prefixRoutines.get(source);
        return entry != null && entry.routines > 0;
    }

    boolean moduleHasPrefixRoutines(String module) {
        return routineModules.contains(module);
    }

    PrefixRoutinesEntry prefixRoutines(String source) {
        return prefixRoutines.get(source);
    }

    Collection<PrefixRoutinesEntry> getPrefixRoutines() {
        return Collections.unmodifiableCollection(prefixRoutines.values());
    }

    void recordAutomaticallyClosedBlock(String module, ChangeBlocks.Block block, Set<Integer> lines) {
        AutomaticallyClosedBlock entry = new AutomaticallyClosedBlock(module, block, lines.size());
        automaticallyClosedBlocks.add(entry);
        lines.forEach(line -> automaticallyIncludedLines.add(module + ":" + line));
        logger.info("{}:{}–{} → {}: automaticallyClosed=true, boundary={}, includedLines={}", module,
                entry.getBeginLine(), entry.getEndLine(), entry.getSource(), entry.getBoundary(), lines.size());
    }

    List<AutomaticallyClosedBlock> getAutomaticallyClosedBlocks() {
        return Collections.unmodifiableList(automaticallyClosedBlocks);
    }


    void record(String source, String team, String reason, CoverageMarker.Kind kind, String module, int lines) {
        Entry entry = entries.computeIfAbsent(source, key -> new Entry(source, team, reason, kind));
        entry.excludedLines += lines;
        entry.modules.add(module);
    }

    void addModuleTotals(int lines, int excluded, int auto) {
        totalLines += lines;
        excludedLines += excluded;
        autoLines += auto;
    }

    void scopeMatch(String source, boolean matches) {
        scopeMatches.merge(source, matches, (first, second) -> first || second);
    }

    void exactSubsystemMatch(String source, String name, boolean matches) {
        exactSubsystemNames.put(source, name.trim());
        if (matches) {
            matchedExactSubsystems.add(source);
        }
    }

    void verifyExactSubsystems() {
        exactSubsystemNames.forEach((source, name) -> {
            if (!matchedExactSubsystems.contains(source)) {
                throw new IllegalArgumentException(source + ": Unknown subsystem: " + name);
            }
        });
    }

    void warnUnmatchedScopes() {
        scopeMatches.forEach((source, matches) -> {
            if (!matches) {
                warn(source + ": rule does not match anything");
            }
        });
    }

    void addScopeSource(String source) {
        if (!scopeSources.contains(source)) {
            scopeSources.add(source);
        }
    }

    void addModuleOutOfScope() {
        modulesOutOfScope++;
    }

    void info(String message) {
        logger.info(message);
    }

    void warn(String message) {
        if (!warnings.contains(message)) {
            warnings.add(message);
            logger.warn(message);
        }
    }

    void recordChangeBlocks(String source, String team, String module, int blocks, int lines) {
        ChangeBlocksEntry entry = changeBlocks.computeIfAbsent(source, key -> new ChangeBlocksEntry(source, team));
        entry.blocks += blocks;
        entry.includedLines += lines;
        if (lines > 0) {
            entry.modules.add(module);
        }
    }

    void addIncludedLines(String module, int lines) {
        if (lines > 0) {
            includedLines += lines;
            includedModules.add(module);
        }
    }

    boolean hasChangeBlocks(String source) {
        ChangeBlocksEntry entry = changeBlocks.get(source);
        return entry != null && entry.blocks > 0;
    }

    Collection<ChangeBlocksEntry> getChangeBlocks() {
        return Collections.unmodifiableCollection(changeBlocks.values());
    }

    long getIncludedLines() {
        return includedLines;
    }

    boolean hasEntry(String source) {
        return entries.containsKey(source);
    }

    Collection<Entry> getEntries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    long getTotalLines() {
        return totalLines;
    }

    long getExcludedLines() {
        return excludedLines;
    }

    long getAutoLines() {
        return autoLines;
    }

    double getExcludedShare() {
        if (totalLines == 0) {
            return 0;
        }
        return Math.round((excludedLines + autoLines) * 10000.0 / totalLines) / 10000.0;
    }

    void logSummary() {
        logger.info("Coverage exclusions: {} of {} lines excluded, {} marked auto ({}%)",
                excludedLines, totalLines, autoLines,
                String.format(Locale.ROOT, "%.2f", getExcludedShare() * 100));
        entries.values().stream()
                .sorted(Comparator.comparingInt(Entry::getExcludedLines).reversed()
                        .thenComparing(Entry::getSource))
                .limit(SUMMARY_TOP)
                .forEach(entry -> logger.info("  {} lines: {}", entry.getExcludedLines(), entry.getSource()));
        if (!changeBlocks.isEmpty()) {
            logger.info("Change blocks: {} line(s) returned to coverage from {} block(s) in {} module(s)",
                    includedLines, changeBlocks.values().stream().mapToInt(ChangeBlocksEntry::getBlocks).sum(),
                    includedModules.size());
            changeBlocks.values().stream()
                    .filter(entry -> entry.getIncludedLines() > 0)
                    .sorted(Comparator.comparingInt(ChangeBlocksEntry::getIncludedLines).reversed()
                            .thenComparing(ChangeBlocksEntry::getSource))
                    .limit(SUMMARY_TOP)
                    .forEach(entry -> logger.info("  {} lines: {}", entry.getIncludedLines(), entry.getSource()));
        }
        if (!automaticallyClosedBlocks.isEmpty()) {
            logger.info("Automatically closed change blocks: {} block(s), {} unique line(s) returned to coverage",
                    automaticallyClosedBlocks.size(), automaticallyIncludedLines.size());
        }
        if (prefixScope != null) {
            logger.info("Prefix scope: subsystems={}, includeSubsystemContents={}, modules={}, routines={} ({})",
                    prefixScope.isSubsystems(), prefixScope.isIncludeSubsystemContents(), prefixScope.isModules(),
                    prefixScope.isRoutines(), prefixScope.getSources().isEmpty()
                            ? "default" : String.join(", ", prefixScope.getSources()));
        }
        if (!prefixRoutines.isEmpty()) {
            logger.info("Prefix routines: {} line(s) in {} method(s) of {} module(s)",
                    totalPrefixRoutineLines, totalPrefixRoutines,
                    routineModules.size());
        }
        if (!warnings.isEmpty()) {
            logger.info("Coverage exclusion warnings: {}", warnings.size());
        }
        if (!scopeSources.isEmpty()) {
            logger.info("Coverage scope: {} module(s) outside includeSubsystems/includePrefix of {}",
                    modulesOutOfScope, String.join(", ", scopeSources));
        }
    }

    void writeJson(File file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("totalLines", totalLines);
        root.put("excludedLines", excludedLines);
        root.put("autoLines", autoLines);
        root.put("includedLines", includedLines);
        root.put("excludedShare", getExcludedShare());
        root.put("entries", new ArrayList<>(entries.values()));
        root.put("changeBlocks", new ArrayList<>(changeBlocks.values()));
        root.put("automaticallyClosedBlocks", automaticallyClosedBlocks);
        Map<String, Object> automaticSummary = new LinkedHashMap<>();
        automaticSummary.put("blocks", automaticallyClosedBlocks.size());
        automaticSummary.put("includedLines", automaticallyIncludedLines.size());
        root.put("automaticallyClosedSummary", automaticSummary);
        root.put("prefixRoutines", new ArrayList<>(prefixRoutines.values()));
        root.put("warnings", warnings);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("sources", scopeSources);
        scope.put("modulesOutOfScope", modulesOutOfScope);
        if (prefixScope != null) {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("subsystems", prefixScope.isSubsystems());
            settings.put("includeSubsystemContents", prefixScope.isIncludeSubsystemContents());
            settings.put("modules", prefixScope.isModules());
            settings.put("routines", prefixScope.isRoutines());
            settings.put("sources", prefixScope.getSources());
            scope.put("prefixScope", settings);
        }
        root.put("scope", scope);
        File directory = file.getAbsoluteFile().getParentFile();
        if (directory != null) {
            Files.createDirectories(directory.toPath());
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file, root);
    }

    @JsonPropertyOrder({"source", "team", "reason", "kind", "excludedLines", "modules"})
    public static final class Entry {
        private final String source;
        private final String team;
        private final String reason;
        private final CoverageMarker.Kind kind;
        private final Set<String> modules = new TreeSet<>();
        private int excludedLines;

        private Entry(String source, String team, String reason, CoverageMarker.Kind kind) {
            this.source = source;
            this.team = team;
            this.reason = reason;
            this.kind = kind;
        }

        public String getSource() {
            return source;
        }

        public String getTeam() {
            return team;
        }

        public String getReason() {
            return reason;
        }

        public String getKind() {
            return kind.name().toLowerCase(Locale.ROOT);
        }

        public int getExcludedLines() {
            return excludedLines;
        }

        public Set<String> getModules() {
            return Collections.unmodifiableSet(modules);
        }
    }

    public static final class AutomaticallyClosedBlock {
        private final String module;
        private final ChangeBlocks.Block block;
        private final int includedLines;

        private AutomaticallyClosedBlock(String module, ChangeBlocks.Block block, int includedLines) {
            this.module = module;
            this.block = block;
            this.includedLines = includedLines;
        }

        public String getModule() {
            return module;
        }

        public String getSource() {
            return block.getMarker().getSource();
        }

        public int getBeginLine() {
            return block.getBeginLine();
        }

        public int getEndLine() {
            return block.getEndLine();
        }

        public String getBoundary() {
            return block.getBoundary();
        }

        public int getIncludedLines() {
            return includedLines;
        }

        public boolean isAutomaticallyClosed() {
            return true;
        }
    }

    @JsonPropertyOrder({"source", "team", "blocks", "includedLines", "modules"})
    public static final class ChangeBlocksEntry {
        private final String source;
        private final String team;
        private final Set<String> modules = new TreeSet<>();
        private int blocks;
        private int includedLines;

        private ChangeBlocksEntry(String source, String team) {
            this.source = source;
            this.team = team;
        }

        public String getSource() {
            return source;
        }

        public String getTeam() {
            return team;
        }

        public int getBlocks() {
            return blocks;
        }

        public int getIncludedLines() {
            return includedLines;
        }

        public Set<String> getModules() {
            return Collections.unmodifiableSet(modules);
        }
    }

    @JsonPropertyOrder({"source", "routines", "includedLines", "modules"})
    public static final class PrefixRoutinesEntry {
        private final String source;
        private final Set<String> modules = new TreeSet<>();
        private final Set<String> routineModules = new TreeSet<>();
        private int routines;
        private int includedLines;

        private PrefixRoutinesEntry(String source) {
            this.source = source;
        }

        public String getSource() {
            return source;
        }

        public int getRoutines() {
            return routines;
        }

        public int getIncludedLines() {
            return includedLines;
        }

        public Set<String> getModules() {
            return Collections.unmodifiableSet(modules);
        }

        // Modules with methods of the prefix, including methods without lines left in coverage.
        @JsonIgnore
        public int getRoutineModules() {
            return routineModules.size();
        }
    }
}
