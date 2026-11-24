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

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CoverageConfig {

    private String team;
    private List<String> includeSubsystems;
    private List<String> includePrefix;
    private JsonNode prefixScope;
    private boolean prefixScopeSet;
    private JsonNode includeUsers;
    private boolean includeUsersSet;
    private String exclusionReport;
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<MarkerPair> changeMarkers;
    private UnclosedBlocks unclosedBlocks;
    private Rules exclude;
    private Rules auto;
    private final List<String> unknownKeys = new ArrayList<>();

    public String getTeam() {
        return team == null ? "" : team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public List<String> getIncludeSubsystems() {
        return valueOrEmpty(includeSubsystems);
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setIncludeSubsystems(List<String> includeSubsystems) {
        this.includeSubsystems = includeSubsystems;
    }

    public List<String> getIncludePrefix() {
        return valueOrEmpty(includePrefix);
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setIncludePrefix(List<String> includePrefix) {
        this.includePrefix = includePrefix;
    }

    // Null without the key; an explicit JSON null is kept as NullNode so that it is reported as a wrong type.
    public JsonNode getPrefixScope() {
        if (!prefixScopeSet) {
            return null;
        }
        return prefixScope == null ? NullNode.getInstance() : prefixScope;
    }

    public void setPrefixScope(JsonNode prefixScope) {
        this.prefixScope = prefixScope;
        this.prefixScopeSet = true;
    }

    // Null without the key; an explicit JSON null is kept as NullNode so that it is reported as a wrong type.
    public JsonNode getIncludeUsers() {
        if (!includeUsersSet) {
            return null;
        }
        return includeUsers == null ? NullNode.getInstance() : includeUsers;
    }

    public void setIncludeUsers(JsonNode includeUsers) {
        this.includeUsers = includeUsers;
        this.includeUsersSet = true;
    }

    public String getExclusionReport() {
        return exclusionReport == null || exclusionReport.isBlank() ? "" : exclusionReport;
    }

    public void setExclusionReport(String exclusionReport) {
        this.exclusionReport = exclusionReport;
    }

    public List<MarkerPair> getChangeMarkers() {
        return changeMarkers == null ? Collections.emptyList() : new ArrayList<>(changeMarkers);
    }

    public void setChangeMarkers(List<MarkerPair> changeMarkers) {
        this.changeMarkers = changeMarkers;
    }

    public UnclosedBlocks getUnclosedBlocks() {
        return unclosedBlocks == null ? new UnclosedBlocks() : unclosedBlocks;
    }

    public void setUnclosedBlocks(UnclosedBlocks unclosedBlocks) {
        this.unclosedBlocks = unclosedBlocks;
    }

    public Rules getExclude() {
        return exclude == null ? new Rules() : exclude;
    }

    public void setExclude(Rules exclude) {
        this.exclude = exclude;
    }

    public Rules getAuto() {
        return auto == null ? new Rules() : auto;
    }

    public void setAuto(Rules auto) {
        this.auto = auto;
    }

    @JsonAnySetter
    public void addUnknownKey(String key, Object value) {
        unknownKeys.add(key);
    }

    @JsonIgnore
    public List<String> getUnknownKeys() {
        List<String> keys = new ArrayList<>(unknownKeys);
        getExclude().unknownKeys.forEach(key -> keys.add("exclude." + key));
        getAuto().unknownKeys.forEach(key -> keys.add("auto." + key));
        getUnclosedBlocks().unknownKeys.forEach(key -> keys.add("unclosedBlocks." + key));
        List<MarkerPair> pairs = getChangeMarkers();
        for (int index = 0; index < pairs.size(); index++) {
            String prefix = "changeMarkers[" + index + "].";
            pairs.get(index).unknownKeys.forEach(key -> keys.add(prefix + key));
        }
        return keys;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return getIncludeSubsystems().isEmpty() && getIncludePrefix().isEmpty() && getPrefixScope() == null
                && getIncludeUsers() == null
                && getExclusionReport().isEmpty()
                && getChangeMarkers().isEmpty() && getExclude().isEmpty() && getAuto().isEmpty();
    }

    private static List<String> valueOrEmpty(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    public static class Rules {

        private List<String> subsystems;
        private List<String> objects;
        private List<String> paths;
        private List<String> lines;
        private final List<String> unknownKeys = new ArrayList<>();

        public List<String> getSubsystems() {
            return valueOrEmpty(subsystems);
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setSubsystems(List<String> subsystems) {
            this.subsystems = subsystems;
        }

        public List<String> getObjects() {
            return valueOrEmpty(objects);
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setObjects(List<String> objects) {
            this.objects = objects;
        }

        public List<String> getPaths() {
            return valueOrEmpty(paths);
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setPaths(List<String> paths) {
            this.paths = paths;
        }

        public List<String> getLines() {
            return valueOrEmpty(lines);
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setLines(List<String> lines) {
            this.lines = lines;
        }

        @JsonAnySetter
        public void addUnknownKey(String key, Object value) {
            unknownKeys.add(key);
        }

        @JsonIgnore
        public boolean isEmpty() {
            return getSubsystems().isEmpty() && getObjects().isEmpty() && getPaths().isEmpty() && getLines().isEmpty();
        }
    }

    public static class UnclosedBlocks {
        private boolean enabled;
        private final List<String> unknownKeys = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @JsonAnySetter
        public void addUnknownKey(String key, Object value) {
            unknownKeys.add(key);
        }
    }

    public static class MarkerPair {

        private String begin;
        private String end;
        private final List<String> unknownKeys = new ArrayList<>();

        public String getBegin() {
            return begin;
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setBegin(String begin) {
            this.begin = begin;
        }

        public String getEnd() {
            return end;
        }

        @JsonSetter(nulls = Nulls.FAIL)
        public void setEnd(String end) {
            this.end = end;
        }

        @JsonAnySetter
        public void addUnknownKey(String key, Object value) {
            unknownKeys.add(key);
        }

        boolean hasUnknownKeys() {
            return !unknownKeys.isEmpty();
        }
    }
}
