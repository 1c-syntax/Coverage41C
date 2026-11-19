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

import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetId;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoMeasure;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

// Debug targets of a start run and their infobase users. Decides which targets to attach and whether
// a measure packet belongs to an allowed user before its hits are merged into the coverage map.
// Written from the ping thread, read from the command thread and the shutdown hook.
public final class UserTargets {

    public enum Verdict { ACCEPTED, REJECTED, UNKNOWN }

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String NO_USER = "";
    private static final long LOOKUP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final IncludeUsers users;
    private final LongSupplier nanoTime;
    private final Map<String, String> usersById = new ConcurrentHashMap<>();
    private final Map<String, Long> unknownIds = new ConcurrentHashMap<>();
    private final Set<String> attachedIds = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedPackets = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedConflicts = ConcurrentHashMap.newKeySet();
    private final LongAdder acceptedPackets = new LongAdder();
    private final LongAdder rejectedPackets = new LongAdder();
    private final LongAdder unknownPackets = new LongAdder();
    private final Map<String, LongAdder> packetsByUser = new ConcurrentHashMap<>();
    private volatile boolean matchedUser;

    public UserTargets(IncludeUsers users) {
        this(users, System::nanoTime);
    }

    public UserTargets(IncludeUsers users, LongSupplier nanoTime) {
        this.users = users;
        this.nanoTime = nanoTime;
    }

    public boolean isActive() {
        return users.isActive();
    }

    // Remembers the target and tells whether to attach it: without a filter every target, with a filter
    // targets of allowed users; a target is attached once, after attached() confirms success.
    public boolean register(DebugTargetId target) {
        String id = target == null ? null : Utils.normalizeUuid(target.getId());
        if (id == null) {
            logger.warn("Debug target without id skipped");
            return false;
        }
        String user = userOf(target);
        String previous = usersById.get(id);
        if (previous == null || (previous.isEmpty() && user != null)) {
            usersById.put(id, user == null ? NO_USER : user);
            if (!isActive()) {
                logger.info("Target {}: found", describe(target));
            } else if (user == null) {
                logger.info("Target {}: no user name reported, not attached", describe(target));
            } else {
                logger.info("Target {}: user '{}' {}", describe(target), user,
                        users.accepts(user) ? "accepted" : "rejected, not attached");
            }
        }
        boolean wanted = !isActive() || users.accepts(usersById.get(id));
        if (wanted) {
            matchedUser = true;
        }
        return wanted && !attachedIds.contains(id);
    }

    // The target was attached; later announcements of it do not attach it again.
    public void attached(String targetId) {
        attachedIds.add(Utils.normalizeUuid(targetId));
    }

    // A nameless packet may need a fresh list even if dbgs already announced the target id.
    public boolean needsLookup(DBGUIExtCmdInfoMeasure command) {
        if (!isActive()) {
            return false;
        }
        DebugTargetId target = targetOf(command);
        String id = target == null ? null : Utils.normalizeUuid(target.getId());
        if (id == null || packetUserOf(command, target, id) != null) {
            return false;
        }
        Long lastLookup = unknownIds.get(id);
        return usersById.getOrDefault(id, NO_USER).isEmpty()
                && (lastLookup == null || nanoTime.getAsLong() - lastLookup >= LOOKUP_INTERVAL_NANOS);
    }

    public boolean knows(String targetId) {
        String id = Utils.normalizeUuid(targetId);
        return id != null && (usersById.containsKey(id) || unknownIds.containsKey(id));
    }

    // A successful lookup found no user. Give dbgs time to learn the name before trying again.
    public void markUnknown(String targetId) {
        String id = Utils.normalizeUuid(targetId);
        if (id != null && usersById.getOrDefault(id, NO_USER).isEmpty()) {
            unknownIds.put(id, nanoTime.getAsLong());
        }
    }

    public Verdict verdict(DBGUIExtCmdInfoMeasure command, boolean verbose) {
        if (!isActive()) {
            return Verdict.ACCEPTED;
        }
        DebugTargetId target = targetOf(command);
        String id = target == null ? null : Utils.normalizeUuid(target.getId());
        String user = target == null ? null : packetUserOf(command, target, id);
        if (id != null) {
            String known = usersById.get(id);
            if (user == null && known != null && !known.isEmpty()) {
                user = known;
            } else if (user != null && known != null && !known.isEmpty() && !known.equals(user)) {
                // The packet is trusted, for this and later packets of the target.
                usersById.put(id, user);
                if (loggedConflicts.add(id)) {
                    logger.warn("Target {}: measures report user '{}', dbgs listed '{}'; the measures are trusted",
                            id, user, known);
                }
            } else if (user != null && (known == null || known.isEmpty())) {
                // A target that was never announced: the packet itself tells who it belongs to.
                usersById.put(id, user);
                logger.info("Target {}: user '{}' known from its measures", describe(target), user);
            }
        }
        Verdict verdict = user == null ? Verdict.UNKNOWN : users.accepts(user) ? Verdict.ACCEPTED : Verdict.REJECTED;
        if (verdict == Verdict.ACCEPTED) {
            matchedUser = true;
        }
        count(verdict, user);
        log(verdict, id, user, verbose);
        return verdict;
    }

    // Id of the target the packet belongs to: the measure's own target first, then the command's.
    public static String targetIdOf(DBGUIExtCmdInfoMeasure command) {
        DebugTargetId target = targetOf(command);
        return target == null ? null : target.getId();
    }

    // True without a filter; with a filter, true once a target of an allowed user has been seen.
    public boolean matchedAnyTarget() {
        return !isActive() || matchedUser;
    }

    public List<String> summary() {
        if (!isActive()) {
            return List.of();
        }
        Map<String, Long> targetsByUser = usersById.values().stream()
                .collect(Collectors.groupingBy(user -> user, TreeMap::new, Collectors.counting()));
        long acceptedTargets = targetsByUser.entrySet().stream()
                .filter(entry -> users.accepts(entry.getKey())).mapToLong(Map.Entry::getValue).sum();
        long namelessTargets = targetsByUser.getOrDefault(NO_USER, 0L);
        long rejectedTargets = usersById.size() - acceptedTargets - namelessTargets;
        // Targets seen only in packets without a user and not found in dbgs' list are unknown too.
        long unknownTargets = namelessTargets + unknownIds.keySet().stream().filter(id -> !usersById.containsKey(id)).count();
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Include users: %d target(s) accepted, %d rejected, %d unknown; "
                        + "%d measure packet(s) accepted, %d rejected, %d unknown",
                acceptedTargets, rejectedTargets, unknownTargets,
                acceptedPackets.sum(), rejectedPackets.sum(), unknownPackets.sum()));
        lines.add("  accepted: " + users.getUsers().stream()
                .map(user -> perUser(user, targetsByUser)).collect(Collectors.joining(", ")));
        String rejected = targetsByUser.keySet().stream()
                .filter(user -> !user.isEmpty() && !users.accepts(user))
                .map(user -> perUser(user, targetsByUser)).collect(Collectors.joining(", "));
        if (!rejected.isEmpty()) {
            lines.add("  rejected: " + rejected);
        }
        lines.add(String.format(Locale.ROOT, "  unknown: %d target(s) without user name, %d packet(s) without target or user",
                unknownTargets, unknownPackets.sum()));
        return lines;
    }

    public String noMatchMessage() {
        String seen = usersById.values().stream().filter(user -> !user.isEmpty()).distinct().sorted()
                .collect(Collectors.joining(", "));
        return "No debug target matched includeUsers [" + String.join(", ", users.getUsers()) + "]; "
                + (seen.isEmpty() ? "no user names reported by dbgs" : "users seen: " + seen);
    }

    private String perUser(String user, Map<String, Long> targetsByUser) {
        LongAdder packets = packetsByUser.get(user);
        return String.format(Locale.ROOT, "%s (%d target(s), %d packet(s))", user, targetsByUser.getOrDefault(user, 0L),
                packets == null ? 0 : packets.sum());
    }

    private void count(Verdict verdict, String user) {
        switch (verdict) {
            case ACCEPTED:
                acceptedPackets.increment();
                break;
            case REJECTED:
                rejectedPackets.increment();
                break;
            default:
                unknownPackets.increment();
                return;
        }
        packetsByUser.computeIfAbsent(user, key -> new LongAdder()).increment();
    }

    // Accepted packets are logged only with --verbose; the first rejected or unknown packet of a target always.
    private void log(Verdict verdict, String id, String user, boolean verbose) {
        String key = verdict + ":" + (id == null ? "<none>" : id);
        if (!verbose && (verdict == Verdict.ACCEPTED || !loggedPackets.add(key))) {
            return;
        }
        switch (verdict) {
            case ACCEPTED:
                logger.info("Measures of target {} (user '{}') accepted", id, user);
                break;
            case REJECTED:
                logger.info("Measures of target {} (user '{}') rejected", id, user);
                break;
            default:
                if (id == null) {
                    logger.info("Measure without target id dropped");
                } else {
                    logger.info("Measures of target {} have no user, dropped", id);
                }
        }
    }

    private static DebugTargetId targetOf(DBGUIExtCmdInfoMeasure command) {
        PerformanceInfoMain measure = command.getMeasure();
        DebugTargetId target = measure == null ? null : measure.getTargetID();
        if (target == null || target.getId() == null) {
            target = command.getTargetID();
        }
        return target == null || target.getId() == null ? null : target;
    }

    public static boolean isValidTargetUuid(String id) {
        return Utils.isUuid(id);
    }

    private static String packetUserOf(DBGUIExtCmdInfoMeasure command, DebugTargetId target, String id) {
        String user = userOf(target);
        if (user != null) {
            return user;
        }
        DebugTargetId commandTarget = command.getTargetID();
        if (commandTarget != null && id.equals(Utils.normalizeUuid(commandTarget.getId()))) {
            return userOf(commandTarget);
        }
        return null;
    }

    private static String userOf(DebugTargetId target) {
        String user = target.isSetUserName() ? target.getUserName() : null;
        return user == null || user.isEmpty() ? null : user;
    }

    private static String describe(DebugTargetId target) {
        return target.getId() + " (" + (target.getTargetType() == null ? "?" : target.getTargetType().getName())
                + ", seance " + target.getSeanceNo() + ")";
    }
}
