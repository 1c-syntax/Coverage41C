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
import com.github._1c_syntax.bsl.mdo.Subsystem;
import com.github._1c_syntax.bsl.types.MdoReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public final class SubsystemFilter {

    private SubsystemFilter() {
    }

    public static Set<String> resolve(CF configuration, String name) {
        return resolve(configuration, name, false);
    }

    static Set<String> resolve(CF configuration, String name, boolean allowMissing) {
        Map<String, List<Subsystem>> subsystemsByName = new HashMap<>();
        Map<String, List<Subsystem>> subsystemsByPath = new HashMap<>();
        indexSubsystems(configuration, subsystemsByName, subsystemsByPath);

        String subsystemName = name == null ? "" : name.trim();
        Map<String, List<Subsystem>> index = subsystemName.contains(".") ? subsystemsByPath : subsystemsByName;
        List<Subsystem> matches = index.getOrDefault(subsystemName, Collections.emptyList());
        if (matches.isEmpty()) {
            if (allowMissing) {
                return Collections.emptySet();
            }
            throw new IllegalArgumentException("Unknown subsystem: " + subsystemName);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous subsystem name: " + subsystemName);
        }
        Set<String> subsystems = new HashSet<>();
        includeSubsystemTree(matches.get(0), subsystems);
        return subsystems;
    }

    // Subsystems whose full path or name matches the mask, by full path. A match nested
    // in another match is left out: the outer one already includes it with its tree.
    public static Map<String, Set<String>> resolveMask(CF configuration, Pattern mask) {
        Map<String, List<Subsystem>> subsystemsByPath = new TreeMap<>();
        indexSubsystems(configuration, new HashMap<>(), subsystemsByPath);
        Map<String, Set<String>> result = new TreeMap<>();
        Set<String> covered = new HashSet<>();
        subsystemsByPath.forEach((path, subsystems) -> subsystems.forEach(subsystem -> {
            String reference = subsystem.getMdoReference().getMdoRef();
            if (!covered.contains(reference)
                    && (mask.matcher(path).matches() || mask.matcher(subsystem.getName()).matches())) {
                Set<String> tree = new HashSet<>();
                includeSubsystemTree(subsystem, tree);
                covered.addAll(tree);
                result.put(path, tree);
            }
        }));
        return result;
    }

    // Index direct references once per source. Resolving a parent rule already includes its descendants.
    static Map<MdoReference, Set<String>> membership(CF configuration) {
        Map<MdoReference, Set<String>> result = new HashMap<>();
        configuration.getSubsystems().forEach(subsystem -> addMembership(subsystem, result));
        return result;
    }

    private static void addMembership(Subsystem subsystem, Map<MdoReference, Set<String>> membership) {
        for (MdoReference object : subsystem.getContent()) {
            membership.computeIfAbsent(object, ignored -> new HashSet<>()).add(subsystem.getMdoRef());
        }
        subsystem.getSubsystems().forEach(child -> addMembership(child, membership));
    }

    static Set<String> subsystemsOf(MdoReference object, Map<MdoReference, Set<String>> membership) {
        Set<String> result = new HashSet<>();
        String[] parts = object.getMdoRef().split("\\.");
        for (int length = 2; length <= parts.length; length += 2) {
            MdoReference owner = MdoReference.create(String.join(".", java.util.Arrays.copyOf(parts, length)));
            result.addAll(membership.getOrDefault(owner, Set.of()));
        }
        return result;
    }

    private static void indexSubsystems(CF configuration,
                                        Map<String, List<Subsystem>> subsystemsByName,
                                        Map<String, List<Subsystem>> subsystemsByPath) {
        configuration.getSubsystems().stream()
                .forEach(subsystem -> indexSubsystems(
                        subsystem, subsystem.getName(), subsystemsByName, subsystemsByPath));
    }

    private static void indexSubsystems(Subsystem subsystem,
                                        String path,
                                        Map<String, List<Subsystem>> subsystemsByName,
                                        Map<String, List<Subsystem>> subsystemsByPath) {
        subsystemsByName.computeIfAbsent(subsystem.getName(), key -> new ArrayList<>()).add(subsystem);
        subsystemsByPath.computeIfAbsent(path, key -> new ArrayList<>()).add(subsystem);
        nestedSubsystems(subsystem).forEach(child -> indexSubsystems(
                child, path + "." + child.getName(), subsystemsByName, subsystemsByPath));
    }

    private static void includeSubsystemTree(Subsystem subsystem, Set<String> includedSubsystems) {
        includedSubsystems.add(subsystem.getMdoReference().getMdoRef());
        nestedSubsystems(subsystem).forEach(child -> includeSubsystemTree(child, includedSubsystems));
    }

    private static List<Subsystem> nestedSubsystems(Subsystem subsystem) {
        return subsystem.getSubsystems();
    }
}
