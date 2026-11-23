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

final class CoverageMarker {

    enum Kind { OFF, AUTO }

    private final Kind kind;
    private final String label;
    private final int line;
    private final int startLine;
    private final int endLine;
    private final String reason;
    private final boolean fileOff;

    CoverageMarker(Kind kind, String label, int line, int startLine, int endLine, String reason) {
        this(kind, label, line, startLine, endLine, reason, false);
    }

    CoverageMarker(Kind kind, String label, int line, int startLine, int endLine, String reason, boolean fileOff) {
        this.kind = kind;
        this.label = label;
        this.line = line;
        this.startLine = startLine;
        this.endLine = endLine;
        this.reason = reason;
        this.fileOff = fileOff;
    }

    Kind getKind() {
        return kind;
    }

    String getLabel() {
        return label;
    }

    int getLine() {
        return line;
    }

    int getStartLine() {
        return startLine;
    }

    int getEndLine() {
        return endLine;
    }

    String getReason() {
        return reason;
    }

    // Cover:file-off and its Russian synonym exclude the whole module.
    boolean isFileOff() {
        return fileOff;
    }

    boolean covers(int lineNumber) {
        return lineNumber >= startLine && lineNumber <= endLine;
    }
}
