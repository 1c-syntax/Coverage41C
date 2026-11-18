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

import com.github._1c_syntax.bsl.parser.BSLLexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// Blocks of code between change markers: "// ++ demo" … "// -- demo". Each marker pair is parsed on its own.
final class ChangeBlocks {

    static final ChangeBlocks NONE = new ChangeBlocks(Collections.emptyList(), Collections.emptyList());

    private final List<Block> blocks;
    private final List<String> warnings;

    private ChangeBlocks(List<Block> blocks, List<String> warnings) {
        this.blocks = blocks;
        this.warnings = warnings;
    }

    static ChangeBlocks parse(String[] lines, List<ExclusionRules.ChangeMarker> markers, String modulePath,
                              Supplier<List<Token>> tokens, Supplier<? extends ParseTree> astSupplier) {
        List<Block> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<CoverageMarkers.BlockRange> scopes = null;
        List<Comment> comments = markerComments(lines, markers, tokens);
        for (ExclusionRules.ChangeMarker marker : markers) {
            Deque<Comment> open = new ArrayDeque<>();
            for (Comment comment : comments) {
                // The end is checked first, so one pattern in begin and end opens and closes blocks in turn.
                if (!open.isEmpty() && marker.isEnd(comment.text)) {
                    blocks.add(new Block(marker, open.pop(), comment));
                } else if (marker.isBegin(comment.text)) {
                    open.push(comment);
                } else if (marker.isEnd(comment.text)) {
                    warnings.add(modulePath + ":" + comment.line + " → " + marker.getSource() + ": end without begin");
                }
            }
            Iterator<Comment> unclosed = open.descendingIterator();
            while (unclosed.hasNext()) {
                Comment beginComment = unclosed.next();
                int begin = beginComment.line;
                if (marker.closesUnclosed()) {
                    if (scopes == null) {
                        scopes = CoverageMarkers.blockRanges(astSupplier.get());
                    }
                    CoverageMarkers.BlockRange boundary = new CoverageMarkers.BlockRange(1, lines.length, "module", true);
                    for (CoverageMarkers.BlockRange scope : scopes) {
                        if (scope.kind.equals("statement")) {
                            continue;
                        }
                        if (scope.begin <= begin && begin <= scope.end
                                && (!boundary.kind.equals("method") || scope.kind.equals("method"))) {
                            if (scope.kind.equals("method") || scope.begin >= boundary.begin) {
                                boundary = scope;
                            }
                        }
                    }
                    if (boundary.complete) {
                        blocks.add(new Block(marker, beginComment, boundary.end, boundary.kind));
                    } else {
                        warnings.add(modulePath + ":" + begin + " → " + marker.getSource()
                                + ": method boundary is incomplete, block is not closed, not counted");
                    }
                } else {
                    warnings.add(modulePath + ":" + begin + " → " + marker.getSource()
                            + ": block is not closed, not counted");
                }
            }
        }
        return new ChangeBlocks(blocks, warnings);
    }

    // Line comments that may be markers, in line order. A cheap text check comes first: the lexer runs only
    // for a module with a marker-like "//" somewhere, and a "//" inside a string literal is not a comment.
    private static List<Comment> markerComments(String[] lines, List<ExclusionRules.ChangeMarker> markers,
                                                Supplier<List<Token>> tokensSupplier) {
        List<Comment> comments = new ArrayList<>();
        List<Token> tokens = null;
        Map<Integer, Token> lineComments = null;
        for (int index = 0; index < lines.length; index++) {
            if (!mayHoldMarker(lines[index], markers)) {
                continue;
            }
            if (tokens == null) {
                tokens = tokensSupplier.get();
                lineComments = new HashMap<>();
                for (Token token : tokens) {
                    if (token.getType() == BSLLexer.LINE_COMMENT) {
                        lineComments.put(token.getLine(), token);
                    }
                }
            }
            Token token = lineComments.get(index + 1);
            if (token != null) {
                comments.add(new Comment(index + 1, token.getText(), CoverageMarkers.isInline(tokens, token)));
            }
        }
        return comments;
    }

    private static boolean mayHoldMarker(String text, List<ExclusionRules.ChangeMarker> markers) {
        for (int index = text.indexOf("//"); index >= 0; index = text.indexOf("//", index + 1)) {
            String tail = text.substring(index);
            for (ExclusionRules.ChangeMarker marker : markers) {
                if (marker.isBegin(tail) || marker.isEnd(tail)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean covers(int line) {
        return blocks.stream().anyMatch(block -> block.covers(line));
    }

    boolean isEmpty() {
        return blocks.isEmpty();
    }

    Set<String> sourcesCovering(int line) {
        Set<String> sources = new LinkedHashSet<>();
        blocks.stream().filter(block -> block.covers(line))
                .forEach(block -> sources.add(block.getMarker().getSource()));
        return sources;
    }

    Map<String, List<Block>> bySource() {
        Map<String, List<Block>> result = new LinkedHashMap<>();
        blocks.forEach(block -> result.computeIfAbsent(block.getMarker().getSource(), key -> new ArrayList<>())
                .add(block));
        return result;
    }

    List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    private static final class Comment {
        private final int line;
        private final String text;
        // The comment follows code on the same line.
        private final boolean inline;

        private Comment(int line, String text, boolean inline) {
            this.line = line;
            this.text = text;
            this.inline = inline;
        }
    }

    static final class Block {
        private final ExclusionRules.ChangeMarker marker;
        private final int beginLine;
        private final boolean beginInline;
        private final int endLine;
        private final boolean endInline;
        private final String boundary;

        private Block(ExclusionRules.ChangeMarker marker, Comment begin, Comment end) {
            this(marker, begin, end.line, end.inline, null);
        }

        private Block(ExclusionRules.ChangeMarker marker, Comment begin, int endLine, String boundary) {
            this(marker, begin, endLine, false, boundary);
        }

        private Block(ExclusionRules.ChangeMarker marker, Comment begin, int endLine, boolean endInline,
                      String boundary) {
            this.boundary = boundary;
            this.marker = marker;
            this.beginLine = begin.line;
            this.beginInline = begin.inline;
            this.endLine = endLine;
            this.endInline = endInline;
        }

        ExclusionRules.ChangeMarker getMarker() {
            return marker;
        }

        int getBeginLine() {
            return beginLine;
        }

        int getEndLine() {
            return endLine;
        }

        boolean isAutomaticallyClosed() {
            return boundary != null;
        }

        String getBoundary() {
            return boundary;
        }

        // A comment-only marker line stays outside the block; a marker after code keeps its line in the block.
        boolean covers(int line) {
            boolean afterBegin = line > beginLine || (line == beginLine && beginInline);
            boolean beforeEnd = line < endLine || (line == endLine && (endInline || isAutomaticallyClosed()));
            return afterBegin && beforeEnd;
        }
    }
}
