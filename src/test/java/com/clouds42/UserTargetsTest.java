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

import com._1c.g5.v8.dt.debug.model.base.data.DataFactory;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetId;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.CommandsFactory;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoMeasure;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmds;
import com._1c.g5.v8.dt.debug.model.measure.MeasureFactory;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoMain;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.clouds42.UserTargets.Verdict.ACCEPTED;
import static com.clouds42.UserTargets.Verdict.REJECTED;
import static com.clouds42.UserTargets.Verdict.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Реестр предметов отладки и вердикты пакетам замеров по пользователю ИБ; объекты модели EDT без сервера.
class UserTargetsTest {

    @Test
    void registersAcceptedRejectedAndUnknownTargetsOnce() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("unit")));
        assertTrue(targets.isActive());
        assertTrue(targets.register(target("a", "unit")));
        targets.attached("a");
        assertFalse(targets.register(target("a", "unit")));
        assertFalse(targets.register(target("b", "smoke")));
        assertFalse(targets.register(target("c", null)));
        assertTrue(targets.knows("a"));
        assertTrue(targets.knows("c"));
        assertFalse(targets.knows("d"));
        assertTrue(targets.matchedAnyTarget());
    }

    @Test
    void withoutFilterEveryNewTargetIsAttachedAndEveryPacketAccepted() {
        UserTargets targets = new UserTargets(IncludeUsers.NONE);
        assertFalse(targets.isActive());
        assertTrue(targets.register(target("a", null)));
        targets.attached("a");
        assertFalse(targets.register(target("a", null)));
        assertEquals(ACCEPTED, targets.verdict(measure(null, null), false));
        assertEquals(ACCEPTED, targets.verdict(measure(target("x", "smoke"), null), false));
        assertTrue(targets.matchedAnyTarget());
        assertEquals(List.of(), targets.summary());
    }

    @Test
    void verdictTakesUserFromPacketThenCommandThenRegistry() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("unit")));
        targets.register(target("r", "unit"));
        assertEquals(ACCEPTED, targets.verdict(measure(target("x", "unit"), null), false));
        assertEquals(REJECTED, targets.verdict(measure(null, target("y", "smoke")), false));
        assertEquals(ACCEPTED, targets.verdict(measure(target("r", null), null), false));
        assertEquals(ACCEPTED, targets.verdict(measure(null, target("r", null)), false));
        assertEquals(UNKNOWN, targets.verdict(measure(target("z", null), null), false));
        assertEquals(UNKNOWN, targets.verdict(measure(null, null), false));
        var bothTargets = measure(target("x", "unit"), target("y", "smoke"));
        assertEquals("x", UserTargets.targetIdOf(bothTargets));
        assertEquals(ACCEPTED, targets.verdict(bothTargets, false));
        assertEquals("z", UserTargets.targetIdOf(measure(target("z", null), null)));
        assertEquals("y", UserTargets.targetIdOf(measure(null, target("y", null))));
        assertNull(UserTargets.targetIdOf(measure(null, null)));
    }

    @Test
    void packetUserOverridesRegistryUser() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("unit")));
        targets.register(target("r", "smoke"));
        assertFalse(targets.matchedAnyTarget());
        assertEquals(ACCEPTED, targets.verdict(measure(target("r", "unit"), null), false));
        // пакету верим и дальше: следующие пакеты с одним id тоже приняты, предмет засчитан пользователю пакета
        assertEquals(ACCEPTED, targets.verdict(measure(target("r", null), null), false));
        assertTrue(targets.matchedAnyTarget());

        assertEquals(REJECTED, targets.verdict(measure(target("r", "smoke"), null), false));
        assertEquals(REJECTED, targets.verdict(measure(target("r", null), null), false));
        assertTrue(targets.matchedAnyTarget());
    }

    // Подключение не удалось — следующее объявление того же предмета пробует снова.
    @Test
    void failedAttachIsRetriedOnNextAnnouncement() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("unit")));
        assertTrue(targets.register(target("a", "unit")));
        assertTrue(targets.register(target("a", "unit")));
        targets.attached("a");
        assertFalse(targets.register(target("a", "unit")));
    }

    // Пакет без имени требует поиска, пока имя предмета неизвестно.
    @Test
    void lookupIsNeededForTargetWithoutKnownUser() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("unit")));
        targets.register(target("k", null));
        assertTrue(targets.needsLookup(measure(target("z", null), null)));
        assertFalse(targets.needsLookup(measure(target("z", "smoke"), null)));
        assertTrue(targets.needsLookup(measure(target("k", null), null)));
        targets.markUnknown("k");
        assertFalse(targets.needsLookup(measure(target("k", null), null)));
        targets.register(target("named", "smoke"));
        assertFalse(targets.needsLookup(measure(target("named", null), null)));
        assertFalse(targets.needsLookup(measure(null, null)));
        targets.markUnknown("z");
        assertFalse(targets.needsLookup(measure(target("z", null), null)));
        assertEquals(List.of(
                "Include users: 0 target(s) accepted, 1 rejected, 2 unknown; "
                        + "0 measure packet(s) accepted, 0 rejected, 0 unknown",
                "  accepted: unit (0 target(s), 0 packet(s))",
                "  rejected: smoke (1 target(s), 0 packet(s))",
                "  unknown: 2 target(s) without user name, 0 packet(s) without target or user"), targets.summary());
        assertFalse(new UserTargets(IncludeUsers.NONE).needsLookup(measure(target("z", null), null)));
    }

    // Предмет без имени не подключался; когда dbgs назвал пользователя, подключить надо ровно один раз.
    @Test
    void registeringKnownTargetAgainFillsMissingUserAndAttachesItOnce() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("unit")));
        assertFalse(targets.register(target("a", null)));
        assertEquals(UNKNOWN, targets.verdict(measure(target("a", null), null), false));
        assertFalse(targets.matchedAnyTarget());
        assertTrue(targets.register(target("a", "unit")));
        targets.attached("a");
        assertFalse(targets.register(target("a", "unit")));
        assertEquals(ACCEPTED, targets.verdict(measure(target("a", null), null), false));
        assertTrue(targets.matchedAnyTarget());
    }

    // Пакет от предмета, которого не было ни в списке, ни в TARGET_STARTED, сам сообщает о предмете.
    @Test
    void packetWithUserRegistersItsTarget() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("unit")));
        assertEquals(ACCEPTED, targets.verdict(measure(target("p", "unit"), null), false));
        assertTrue(targets.knows("p"));
        assertTrue(targets.matchedAnyTarget());
        assertEquals(REJECTED, targets.verdict(measure(target("q", "smoke"), null), false));
        assertEquals(List.of(
                "Include users: 1 target(s) accepted, 1 rejected, 0 unknown; "
                        + "1 measure packet(s) accepted, 1 rejected, 0 unknown",
                "  accepted: unit (1 target(s), 1 packet(s))",
                "  rejected: smoke (1 target(s), 1 packet(s))",
                "  unknown: 0 target(s) without user name, 0 packet(s) without target or user"), targets.summary());
    }

    @Test
    void summaryCountsTargetsAndPacketsPerUser() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("unit", "ui")));
        targets.register(target("a", "unit"));
        targets.register(target("b", "smoke"));
        targets.register(target("c", null));
        targets.verdict(measure(target("a", null), null), false);
        targets.verdict(measure(target("a", null), null), false);
        targets.verdict(measure(target("b", null), null), false);
        targets.verdict(measure(null, null), false);
        assertEquals(List.of(
                "Include users: 1 target(s) accepted, 1 rejected, 1 unknown; "
                        + "2 measure packet(s) accepted, 1 rejected, 1 unknown",
                "  accepted: unit (1 target(s), 2 packet(s)), ui (0 target(s), 0 packet(s))",
                "  rejected: smoke (1 target(s), 1 packet(s))",
                "  unknown: 1 target(s) without user name, 1 packet(s) without target or user"), targets.summary());
    }

    @Test
    void noMatchMessageNamesSeenUsers() throws Exception {
        UserTargets targets = new UserTargets(IncludeUsers.load(null, List.of("coverage_unit")));
        assertFalse(targets.matchedAnyTarget());
        assertEquals("No debug target matched includeUsers [coverage_unit]; no user names reported by dbgs",
                targets.noMatchMessage());
        targets.register(target("b", "smoke"));
        targets.register(target("c", "admin"));
        assertEquals("No debug target matched includeUsers [coverage_unit]; users seen: admin, smoke",
                targets.noMatchMessage());
    }

    static DebugTargetId target(String id, String user) {
        DebugTargetId target = DataFactory.eINSTANCE.createDebugTargetId();
        target.setId(id);
        target.setSeanceId("seance-" + id);
        target.setSeanceNo(BigDecimal.ONE);
        target.setTargetType(DebugTargetType.SERVER);
        if (user != null) {
            target.setUserName(user);
        }
        return target;
    }

    static DBGUIExtCmdInfoMeasure measure(DebugTargetId inMeasure, DebugTargetId inCommand) {
        PerformanceInfoMain main = MeasureFactory.eINSTANCE.createPerformanceInfoMain();
        main.setTargetID(inMeasure);
        DBGUIExtCmdInfoMeasure command = CommandsFactory.eINSTANCE.createDBGUIExtCmdInfoMeasure();
        command.setCmdID(DBGUIExtCmds.MEASURE_RESULT_PROCESSING);
        command.setMeasure(main);
        command.setTargetID(inCommand);
        return command;
    }
}
