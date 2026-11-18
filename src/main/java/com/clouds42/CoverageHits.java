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

import java.math.BigDecimal;
import java.util.Map;

public final class CoverageHits {

    private CoverageHits() {
    }

    /**
     * Adds measured hits of a line to the module cover map. An empty map is a module left out of coverage,
     * an executed auto line (-1) becomes covered.
     *
     * @return false if the module is covered but the line is not a line to cover
     */
    public static boolean add(Map<BigDecimal, Integer> coverMap, BigDecimal lineNo, int hits, boolean rawMode) {
        if (coverMap.isEmpty() && !rawMode) {
            return true;
        }
        if (!rawMode && !coverMap.containsKey(lineNo)) {
            return false;
        }
        coverMap.put(lineNo, Math.max(coverMap.getOrDefault(lineNo, 0), 0) + hits);
        return true;
    }
}
