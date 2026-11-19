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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github._1c_syntax.bsl.mdclasses.CF;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

final class ExclusionRules {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int LINE_PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    // Default comment styles: //++ … //-- and //{{ … //}}; "// -----" is a separator, not an end.
    private static final String[][] DEFAULT_CHANGE_MARKERS = {
            {"^\\s*//\\s*\\+\\+", "^\\s*//\\s*--(?!-)"},
            {"^\\s*//\\s*\\{\\{", "^\\s*//\\s*\\}\\}"}};
    // A pair with one pattern takes the other one from any default pair.
    private static final String DEFAULT_BEGIN = "^\\s*//\\s*(\\+\\+|\\{\\{)";
    private static final String DEFAULT_END = "^\\s*//\\s*(--(?!-)|\\}\\})";

    private final boolean filtersScope;
    private final Set<String> includedSubsystems;
    private final List<ScopePrefix> prefixes;
    private final List<String> includeSources;
    private final List<Rule> rules;
    private final File exclusionReportFile;
    private final List<ChangeMarker> changeMarkers;
    private final PrefixScope prefixScope;
    private final Pattern routinePrecheck;
    private final List<String> includeUsersSources;

    private ExclusionRules(boolean filtersScope, Set<String> includedSubsystems, List<ScopePrefix> prefixes,
                           List<String> includeSources, List<Rule> rules, File exclusionReportFile,
                           List<ChangeMarker> changeMarkers, PrefixScope prefixScope,
                           List<String> includeUsersSources) {
        this.filtersScope = filtersScope;
        this.includeUsersSources = includeUsersSources;
        this.includedSubsystems = includedSubsystems;
        this.prefixes = prefixes;
        this.includeSources = includeSources;
        this.rules = rules;
        this.exclusionReportFile = exclusionReportFile;
        this.changeMarkers = changeMarkers;
        this.prefixScope = prefixScope;
        this.routinePrecheck = scopesRoutines() ? routinePrecheck(prefixes) : null;
    }

    static ExclusionRules empty() {
        return new ExclusionRules(false, Collections.emptySet(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), null, Collections.emptyList(), PrefixScope.DEFAULT, Collections.emptyList());
    }

    static ExclusionRules load(File path, Path projectDir, CF configuration, ExclusionReport report)
            throws IOException {
        return load(path, projectDir, configuration, report, false);
    }

    static ExclusionRules load(File path, Path projectDir, CF configuration, ExclusionReport report,
                               boolean multipleSources) throws IOException {
        boolean filtersScope = false;
        Set<String> includedSubsystems = new HashSet<>();
        List<ScopePrefix> prefixes = new ArrayList<>();
        List<String> includeSources = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();
        File exclusionReportFile = null;
        String exclusionReportSource = null;
        List<ChangeMarker> changeMarkers = new ArrayList<>();
        // Prefixes are resolved after all files are read: prefixScope of any file applies to all of them.
        List<String[]> prefixValues = new ArrayList<>();
        PrefixScope.Builder prefixScope = new PrefixScope.Builder();
        List<String> includeUsersSources = new ArrayList<>();
        for (File file : configFiles(path)) {
            String fileName = file.getName();
            CoverageConfig config = read(file);
            config.getUnknownKeys().forEach(key -> report.warn(fileName + ": unknown key " + key));
            if (config.isEmpty()) {
                throw new IllegalArgumentException("Coverage configuration " + fileName + " contains no rules");
            }
            if (config.getIncludeUsers() != null) {
                // Validated here for convert and validate; the filter itself is applied by start.
                IncludeUsers.parse(fileName, config.getIncludeUsers());
                includeUsersSources.add(fileName + " → includeUsers");
            }
            if (!config.getExclusionReport().isEmpty()) {
                // A relative path belongs to the project root (-P), not to the config file or the working directory.
                File reportFile = projectDir.toAbsolutePath()
                        .resolve(config.getExclusionReport()).normalize().toFile();
                rejectReportInsideConfigDirectory(path, reportFile, fileName + " → exclusionReport");
                if (exclusionReportFile != null && !exclusionReportFile.equals(reportFile)) {
                    throw new IllegalArgumentException(fileName + " → exclusionReport: " + reportFile
                            + " conflicts with " + exclusionReportFile + " from " + exclusionReportSource);
                }
                exclusionReportFile = reportFile;
                exclusionReportSource = fileName;
            }
            List<String> include = config.getIncludeSubsystems();
            for (int index = 0; index < include.size(); index++) {
                String source = source(fileName, "includeSubsystems", index);
                Set<String> references = subsystems(configuration, source, include.get(index), report, multipleSources);
                if (multipleSources) {
                    report.scopeMatch(source, !references.isEmpty());
                } else if (references.isEmpty()) {
                    report.warn(source + ": rule does not match anything");
                }
                includedSubsystems.addAll(references);
                includeSources.add(source);
                filtersScope = true;
            }
            List<String> includePrefix = config.getIncludePrefix();
            for (int index = 0; index < includePrefix.size(); index++) {
                String source = source(fileName, "includePrefix", index);
                String prefix = value(source, includePrefix.get(index)).trim();
                if (configuration == null) {
                    throw new IllegalArgumentException(source + ": prefixes require configuration sources");
                }
                prefixValues.add(new String[]{source, prefix});
                includeSources.add(source);
                filtersScope = true;
            }
            if (config.getPrefixScope() != null) {
                prefixScope.add(fileName, config.getPrefixScope(), report);
            }
            addRules(rules, fileName, "exclude", config.getExclude(), config.getTeam(),
                    CoverageMarker.Kind.OFF, configuration, report, multipleSources);
            addRules(rules, fileName, "auto", config.getAuto(), config.getTeam(),
                    CoverageMarker.Kind.AUTO, configuration, report, multipleSources);
            addChangeMarkers(changeMarkers, fileName, config.getChangeMarkers(), config.getTeam(),
                    config.getUnclosedBlocks().isEnabled());
        }
        PrefixScope scope = prefixScope.build();
        if (prefixValues.isEmpty()) {
            scope.getSources().forEach(source -> report.warn(source + ": no effect without includePrefix"));
        } else if (!scope.isSubsystems() && !scope.isModules() && !scope.isRoutines()) {
            report.warn(String.join(", ", scope.getSources()) + ": every way of inclusion is disabled");
        }
        for (String[] value : prefixValues) {
            ScopePrefix prefix = prefix(configuration, value[0], value[1], scope, report);
            if (scope.coversSubsystemContents()) {
                includedSubsystems.addAll(prefix.subsystems);
            }
            prefixes.add(prefix);
        }
        return new ExclusionRules(filtersScope, Collections.unmodifiableSet(includedSubsystems),
                Collections.unmodifiableList(prefixes), Collections.unmodifiableList(includeSources),
                Collections.unmodifiableList(rules), exclusionReportFile, Collections.unmodifiableList(changeMarkers),
                scope, Collections.unmodifiableList(includeUsersSources));
    }

    boolean filtersScope() {
        return filtersScope;
    }

    // Scope is the union of includeSubsystems and includePrefix; without them every module is in scope.
    boolean inScope(ModuleId module) {
        return !filtersScope
                || module.getSubsystems().stream().anyMatch(includedSubsystems::contains)
                || prefixes.stream().anyMatch(prefix -> prefix.matchesName(module));
    }

    List<ScopePrefix> getPrefixes() {
        return prefixes;
    }

    PrefixScope getPrefixScope() {
        return prefixScope;
    }

    // Modules outside scope are read for methods named with a prefix.
    boolean scopesRoutines() {
        return prefixScope.isRoutines() && !prefixes.isEmpty();
    }

    // A cheap text check before parsing: a method keyword followed by one of the prefixes.
    Pattern getRoutinePrecheck() {
        return routinePrecheck;
    }

    static Pattern routinePrecheck(List<ScopePrefix> prefixes) {
        return Pattern.compile("(?iu:процедура|функция|procedure|function)(?=\\s|//)"
                + "\\s*+(?://[^\\r\\n]*+[\\r\\n]\\s*+)*+(?:" + prefixes.stream()
                .map(prefix -> Pattern.quote(prefix.getPrefix())).collect(Collectors.joining("|")) + ")");
    }

    boolean needsSubsystemMembership() {
        return !includedSubsystems.isEmpty() || rules.stream()
                .anyMatch(rule -> rule.getSource().contains(".subsystems["));
    }

    Set<String> getIncludedSubsystems() {
        return includedSubsystems;
    }

    // Files with includeUsers: the key is checked for every command but filters measures only in start.
    List<String> getIncludeUsersSources() {
        return includeUsersSources;
    }

    List<String> getIncludeSources() {
        return includeSources;
    }

    List<Rule> getRules() {
        return rules;
    }

    File getExclusionReportFile() {
        return exclusionReportFile;
    }

    List<ChangeMarker> getChangeMarkers() {
        return changeMarkers;
    }

    // A report written into the config directory would be read as a config on the next run and fail it.
    static void rejectReportInsideConfigDirectory(File configPath, File reportFile, String source)
            throws IOException {
        if (configPath == null || reportFile == null || !configPath.isDirectory()) {
            return;
        }
        Path report = reportFile.toPath().toAbsolutePath();
        Path configDirectory = configPath.toPath().toAbsolutePath();
        if (Files.notExists(report)) {
            // A JSON link in the rules directory may point out of it to a future report.
            try (DirectoryStream<Path> links = Files.newDirectoryStream(configDirectory,
                    path -> isConfigFileName(path.toFile()) && Files.isSymbolicLink(path) && Files.notExists(path))) {
                for (Path link : links) {
                    if (Utils.sameOutputFile(link, report)) {
                        throw new IllegalArgumentException(source + ": " + report + " is the target of " + link
                                + " and would be read as a config on the next run");
                    }
                }
            }
        }
        Set<Path> visited = new HashSet<>();
        for (Path candidate = report; candidate.getParent() != null; ) {
            Path parent = candidate.getParent();
            if (isConfigFileName(candidate.toFile()) && (configDirectory.normalize().equals(parent.normalize())
                    || Files.exists(parent) && Files.isSameFile(configDirectory, parent))) {
                throw new IllegalArgumentException(source + ": " + report
                        + " is inside the configuration directory and would be read as a config on the next run");
            }
            if (!Files.isSymbolicLink(candidate)) {
                return;
            }

            // Resolve the parent only, as the final link may point to a file that does not exist yet.
            Path link = parent.toRealPath().resolve(candidate.getFileName());
            if (!visited.add(link)) {
                throw new FileSystemLoopException(report.toString());
            }
            candidate = link.resolveSibling(Files.readSymbolicLink(link));
        }
    }

    private static boolean isConfigFileName(File file) {
        return !file.getName().startsWith(".") && file.getName().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    static List<File> configFiles(File path) {
        if (!path.exists()) {
            throw new IllegalArgumentException("Coverage configuration path does not exist: " + path.getAbsolutePath());
        }
        if (!path.isDirectory()) {
            return List.of(path);
        }
        File[] files = path.listFiles(file -> file.isFile() && isConfigFileName(file));
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException(
                    "Coverage configuration directory contains no *.json files: " + path.getAbsolutePath());
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        return Arrays.asList(files);
    }

    static CoverageConfig read(File file) throws IOException {
        try {
            CoverageConfig config = OBJECT_MAPPER.readValue(file, CoverageConfig.class);
            if (config == null) {
                throw new IllegalArgumentException(
                        "Cannot read coverage configuration " + file.getName() + ": empty document");
            }
            List<CoverageConfig.MarkerPair> pairs = config.getChangeMarkers();
            for (int index = 0; index < pairs.size(); index++) {
                if (pairs.get(index) == null) {
                    throw new IllegalArgumentException(source(file.getName(), "changeMarkers", index) + ": empty value");
                }
            }
            return config;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot read coverage configuration " + file.getName() + ": " + e.getOriginalMessage(), e);
        }
    }

    private static void addRules(List<Rule> rules, String fileName, String section, CoverageConfig.Rules config,
                                 String team, CoverageMarker.Kind kind, CF configuration,
                                 ExclusionReport report, boolean multipleSources) {
        List<String> subsystems = config.getSubsystems();
        for (int index = 0; index < subsystems.size(); index++) {
            String source = source(fileName, section + ".subsystems", index);
            Set<String> references = subsystems(configuration, source, subsystems.get(index), report, multipleSources);
            rules.add(Rule.module(source, team, kind,
                    module -> module.getSubsystems().stream().anyMatch(references::contains)));
        }
        List<String> objects = config.getObjects();
        for (int index = 0; index < objects.size(); index++) {
            String source = source(fileName, section + ".objects", index);
            Pattern mask = objectMask(value(source, objects.get(index)).trim());
            rules.add(Rule.module(source, team, kind,
                    module -> module.getNames().stream().anyMatch(name -> matchesObject(mask, name))));
        }
        List<String> paths = config.getPaths();
        for (int index = 0; index < paths.size(); index++) {
            String source = source(fileName, section + ".paths", index);
            Pattern glob = globPattern(source, value(source, paths.get(index)).trim());
            rules.add(Rule.module(source, team, kind, module -> glob.matcher(module.getPath()).matches()));
        }
        List<String> lines = config.getLines();
        for (int index = 0; index < lines.size(); index++) {
            String source = source(fileName, section + ".lines", index);
            rules.add(Rule.line(source, team, kind, linePattern(source, value(source, lines.get(index)))));
        }
    }

    private static void addChangeMarkers(List<ChangeMarker> markers, String fileName,
                                         List<CoverageConfig.MarkerPair> pairs, String team, boolean closeUnclosed) {
        for (int index = 0; index < pairs.size(); index++) {
            String source = source(fileName, "changeMarkers", index);
            CoverageConfig.MarkerPair pair = pairs.get(index);
            if (pair.getBegin() == null && pair.getEnd() == null) {
                for (String[] defaults : DEFAULT_CHANGE_MARKERS) {
                    markers.add(new ChangeMarker(source, team, Pattern.compile(defaults[0], LINE_PATTERN_FLAGS),
                            Pattern.compile(defaults[1], LINE_PATTERN_FLAGS), closeUnclosed));
                }
                continue;
            }
            markers.add(new ChangeMarker(source, team,
                    markerPattern(source + ".begin", pair.getBegin(), DEFAULT_BEGIN),
                    markerPattern(source + ".end", pair.getEnd(), DEFAULT_END), closeUnclosed));
        }
    }

    private static Pattern markerPattern(String source, String regex, String defaultRegex) {
        if (regex == null) {
            return Pattern.compile(defaultRegex, LINE_PATTERN_FLAGS);
        }
        return linePattern(source, value(source, regex));
    }

    private static String source(String fileName, String key, int index) {
        return fileName + " → " + key + "[" + index + "]";
    }

    private static String value(String source, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(source + ": empty value");
        }
        return value;
    }

    private static Set<String> subsystems(CF configuration, String source, String name,
                                          ExclusionReport report, boolean multipleSources) {
        value(source, name);
        if (configuration == null) {
            throw new IllegalArgumentException(source + ": subsystems require configuration sources");
        }
        if (name.contains("*")) {
            return subsystemsByMask(configuration, source, name.trim(), report);
        }
        try {
            Set<String> references = SubsystemFilter.resolve(configuration, name, multipleSources);
            if (multipleSources) {
                report.exactSubsystemMatch(source, name, !references.isEmpty());
            }
            return references;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source + ": " + e.getMessage(), e);
        }
    }

    private static ScopePrefix prefix(CF configuration, String source, String prefix,
                                      PrefixScope scope, ExclusionReport report) {
        Set<String> subsystems = new HashSet<>();
        if (scope.isSubsystems()) {
            Map<String, Set<String>> matches = SubsystemFilter.resolveMask(configuration,
                    Pattern.compile(Pattern.quote(prefix) + ".*"));
            if (!matches.isEmpty()) {
                report.info(source + ": " + prefix + " matches " + matches.size() + " subsystem(s)"
                        + (scope.isIncludeSubsystemContents() ? "" : " (contents not included)") + ": "
                        + String.join(", ", matches.keySet()));
            }
            if (scope.coversSubsystemContents()) {
                matches.values().forEach(subsystems::addAll);
            }
        }
        return new ScopePrefix(source, prefix, Collections.unmodifiableSet(subsystems), scope);
    }

    private static Set<String> subsystemsByMask(CF configuration, String source, String mask,
                                                ExclusionReport report) {
        // A mask matching nothing is left to the "rule does not match anything" warning:
        // a list of team prefixes may name one that has no subsystems yet.
        Map<String, Set<String>> matches = SubsystemFilter.resolveMask(configuration, objectMask(mask));
        if (!matches.isEmpty()) {
            report.info(source + ": " + mask + " matches " + matches.size() + " subsystem(s): "
                    + String.join(", ", matches.keySet()));
        }
        Set<String> subsystems = new HashSet<>();
        matches.values().forEach(subsystems::addAll);
        return subsystems;
    }

    private static Pattern objectMask(String mask) {
        String regex = Arrays.stream(mask.split("\\*", -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*"));
        return Pattern.compile(regex);
    }

    private static boolean matchesObject(Pattern mask, String name) {
        if (mask.matcher(name).matches()) {
            return true;
        }
        for (int dot = name.indexOf('.'); dot >= 0; dot = name.indexOf('.', dot + 1)) {
            if (mask.matcher(name.substring(0, dot)).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern globPattern(String source, String glob) {
        try {
            return Pattern.compile(globToRegex(glob));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(source + ": invalid glob: " + e.getDescription(), e);
        }
    }

    // Unix glob syntax of java.nio PathMatcher, but case-sensitive on every OS: "/" is the separator.
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        boolean inGroup = false;
        int index = 0;
        while (index < glob.length()) {
            char c = glob.charAt(index++);
            switch (c) {
                case '\\':
                    if (index == glob.length()) {
                        throw new PatternSyntaxException("No character to escape", glob, index - 1);
                    }
                    appendLiteral(regex, glob.charAt(index++));
                    break;
                case '*':
                    if (index < glob.length() && glob.charAt(index) == '*') {
                        regex.append(".*");
                        index++;
                    } else {
                        regex.append("[^/]*");
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    break;
                case '{':
                    if (inGroup) {
                        throw new PatternSyntaxException("Cannot nest groups", glob, index - 1);
                    }
                    regex.append("(?:(?:");
                    inGroup = true;
                    break;
                case '}':
                    if (inGroup) {
                        regex.append("))");
                        inGroup = false;
                    } else {
                        appendLiteral(regex, c);
                    }
                    break;
                case ',':
                    if (inGroup) {
                        regex.append(")|(?:");
                    } else {
                        appendLiteral(regex, c);
                    }
                    break;
                case '[':
                    index = appendClass(regex, glob, index);
                    break;
                default:
                    appendLiteral(regex, c);
            }
        }
        if (inGroup) {
            throw new PatternSyntaxException("Missing '}'", glob, glob.length() - 1);
        }
        return regex.toString();
    }

    private static int appendClass(StringBuilder regex, String glob, int start) {
        regex.append("[[^/]&&[");
        int index = start;
        if (index < glob.length() && glob.charAt(index) == '!') {
            regex.append('^');
            index++;
        } else if (index < glob.length() && glob.charAt(index) == '^') {
            regex.append("\\^");
            index++;
        }
        while (index < glob.length()) {
            char c = glob.charAt(index++);
            if (c == ']') {
                regex.append("]]");
                return index;
            }
            if (c == '/') {
                throw new PatternSyntaxException("Explicit 'name separator' in class", glob, index - 1);
            }
            if (c == '\\' || c == '[' || c == '&') {
                regex.append('\\');
            }
            regex.append(c);
        }
        throw new PatternSyntaxException("Missing ']'", glob, glob.length() - 1);
    }

    private static void appendLiteral(StringBuilder regex, char c) {
        if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
            regex.append('\\');
        }
        regex.append(c);
    }

    private static Pattern linePattern(String source, String regex) {
        try {
            return Pattern.compile(regex, LINE_PATTERN_FLAGS);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(source + ": invalid regular expression: " + e.getDescription(), e);
        }
    }

    // What includePrefix includes: one meaning for all files of a config directory.
    static final class PrefixScope {
        static final PrefixScope DEFAULT = new PrefixScope(true, true, true, false, Collections.emptyList());
        private static final String[] FIELDS = {"subsystems", "includeSubsystemContents", "modules", "routines"};

        private final boolean subsystems;
        private final boolean includeSubsystemContents;
        private final boolean modules;
        private final boolean routines;
        private final List<String> sources;

        PrefixScope(boolean subsystems, boolean includeSubsystemContents, boolean modules, boolean routines,
                    List<String> sources) {
            this.subsystems = subsystems;
            this.includeSubsystemContents = includeSubsystemContents;
            this.modules = modules;
            this.routines = routines;
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }

        boolean isSubsystems() {
            return subsystems;
        }

        boolean isIncludeSubsystemContents() {
            return includeSubsystemContents;
        }

        boolean isModules() {
            return modules;
        }

        boolean isRoutines() {
            return routines;
        }

        List<String> getSources() {
            return sources;
        }

        // A subsystem found by prefix brings its objects and nested subsystems.
        boolean coversSubsystemContents() {
            return subsystems && includeSubsystemContents;
        }

        static final class Builder {
            private final Map<String, Boolean> values = new LinkedHashMap<>();
            private final Map<String, String> files = new HashMap<>();
            private final List<String> sources = new ArrayList<>();

            void add(String fileName, JsonNode node, ExclusionReport report) {
                String source = fileName + " → prefixScope";
                if (!node.isObject()) {
                    throw new IllegalArgumentException(source + ": expected an object with true/false fields");
                }
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String name = field.getKey();
                    if (!Arrays.asList(FIELDS).contains(name)) {
                        report.warn(fileName + ": unknown key prefixScope." + name);
                        continue;
                    }
                    if (!field.getValue().isBoolean()) {
                        throw new IllegalArgumentException(source + "." + name + ": expected true or false");
                    }
                    boolean value = field.getValue().booleanValue();
                    Boolean previous = values.get(name);
                    if (previous != null && previous != value) {
                        throw new IllegalArgumentException("prefixScope." + name + ": " + previous + " in "
                                + files.get(name) + " conflicts with " + value + " in " + fileName);
                    }
                    values.put(name, value);
                    files.putIfAbsent(name, fileName);
                }
                sources.add(source);
            }

            PrefixScope build() {
                return new PrefixScope(values.getOrDefault("subsystems", DEFAULT.subsystems),
                        values.getOrDefault("includeSubsystemContents", DEFAULT.includeSubsystemContents),
                        values.getOrDefault("modules", DEFAULT.modules),
                        values.getOrDefault("routines", DEFAULT.routines), sources);
            }
        }
    }

    static final class ScopePrefix {
        private final String source;
        private final String prefix;
        private final Set<String> subsystems;
        private final PrefixScope scope;

        ScopePrefix(String source, String prefix, Set<String> subsystems, PrefixScope scope) {
            this.source = source;
            this.prefix = prefix;
            this.subsystems = subsystems;
            this.scope = scope;
        }

        String getSource() {
            return source;
        }

        String getPrefix() {
            return prefix;
        }

        boolean matches(ModuleId module) {
            return module.getSubsystems().stream().anyMatch(subsystems::contains) || matchesName(module);
        }

        // Names of procedures and functions are compared like object names: start of the name, case-sensitive.
        boolean matchesRoutine(String name) {
            return name.startsWith(prefix);
        }

        // Module names are <Type>.<Name>[.<NestedType>.<NestedName>].<ModuleType>: only names are compared.
        private boolean matchesName(ModuleId module) {
            if (!scope.isModules()) {
                return false;
            }
            for (String name : module.getNames()) {
                String[] parts = name.split("\\.");
                for (int index = 1; index < parts.length - 1; index += 2) {
                    if (parts[index].startsWith(prefix)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    static final class Rule {
        private final String source;
        private final String team;
        private final CoverageMarker.Kind kind;
        private final Predicate<ModuleId> module;
        private final Pattern line;

        private Rule(String source, String team, CoverageMarker.Kind kind, Predicate<ModuleId> module, Pattern line) {
            this.source = source;
            this.team = team;
            this.kind = kind;
            this.module = module;
            this.line = line;
        }

        private static Rule module(String source, String team, CoverageMarker.Kind kind, Predicate<ModuleId> module) {
            return new Rule(source, team, kind, module, null);
        }

        private static Rule line(String source, String team, CoverageMarker.Kind kind, Pattern line) {
            return new Rule(source, team, kind, module -> false, line);
        }

        String getSource() {
            return source;
        }

        String getTeam() {
            return team;
        }

        CoverageMarker.Kind getKind() {
            return kind;
        }

        boolean isLineRule() {
            return line != null;
        }

        boolean matches(ModuleId moduleId) {
            return module.test(moduleId);
        }

        boolean matchesLine(String text) {
            return line != null && line.matcher(text).find();
        }
    }

    static final class ChangeMarker {
        private final boolean closeUnclosed;
        private final String source;
        private final String team;
        private final Pattern begin;
        private final Pattern end;

        private ChangeMarker(String source, String team, Pattern begin, Pattern end, boolean closeUnclosed) {
            this.closeUnclosed = closeUnclosed;
            this.source = source;
            this.team = team;
            this.begin = begin;
            this.end = end;
        }

        boolean closesUnclosed() {
            return closeUnclosed;
        }

        String getSource() {
            return source;
        }

        String getTeam() {
            return team;
        }

        boolean isBegin(String text) {
            return begin.matcher(text).find();
        }

        boolean isEnd(String text) {
            return end.matcher(text).find();
        }
    }
}
