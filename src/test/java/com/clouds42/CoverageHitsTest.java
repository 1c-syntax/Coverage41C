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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageHitsTest {

    @Test
    void accumulatesHitsOfLineToCover() {
        Map<BigDecimal, Integer> coverMap = coverMap(5, 0);

        assertTrue(CoverageHits.add(coverMap, line(5), 3, false));
        assertTrue(CoverageHits.add(coverMap, line(5), 2, false));

        assertEquals(Map.of(line(5), 5), coverMap);
    }

    @Test
    void executedAutoLineCountsAsCovered() {
        Map<BigDecimal, Integer> coverMap = coverMap(5, -1);

        assertTrue(CoverageHits.add(coverMap, line(5), 1, false));

        assertEquals(Map.of(line(5), 1), coverMap);
    }

    @Test
    void ignoresLineThatIsNotToCover() {
        Map<BigDecimal, Integer> coverMap = coverMap(5, 0);

        assertFalse(CoverageHits.add(coverMap, line(6), 1, false));

        assertEquals(Map.of(line(5), 0), coverMap);
    }

    @Test
    void ignoresModuleLeftOutOfCoverage() {
        Map<BigDecimal, Integer> coverMap = new HashMap<>();

        assertTrue(CoverageHits.add(coverMap, line(5), 1, false));

        assertEquals(Map.of(), coverMap);
    }

    @Test
    void rawModeAcceptsAnyLine() {
        Map<BigDecimal, Integer> coverMap = new HashMap<>();

        assertTrue(CoverageHits.add(coverMap, line(5), 1, true));

        assertEquals(Map.of(line(5), 1), coverMap);
    }

    private static Map<BigDecimal, Integer> coverMap(int line, int value) {
        Map<BigDecimal, Integer> coverMap = new HashMap<>();
        coverMap.put(line(line), value);
        return coverMap;
    }

    private static BigDecimal line(int number) {
        return new BigDecimal(number);
    }
}
