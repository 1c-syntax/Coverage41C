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
import com.github._1c_syntax.bsl.parser.BSLParser;
import com.github._1c_syntax.bsl.parser.BSLTokenizer;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Trees;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class CoverageMarkers {

    private static final Pattern MARKER = Pattern.compile(
            "(?<![\\p{L}\\p{N}\\p{M}_])Cover:(?:(?<fileOff>file-off|файл-выкл)|(?<off>off|выкл)"
                    + "|(?<on>on|вкл)|(?<auto>auto|авто))(?![\\p{L}\\p{N}\\p{M}_])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern REASON_PREFIX = Pattern.compile("^[\\s—–:-]+");

    private final List<CoverageMarker> markers;
    private final List<Warning> warnings;

    private CoverageMarkers(List<CoverageMarker> markers, List<Warning> warnings) {
        this.markers = markers;
        this.warnings = warnings;
    }

    static CoverageMarkers parse(BSLTokenizer tokenizer) {
        List<Token> tokens = tokenizer.getTokens();
        int lastLine = tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).getLine();
        List<CoverageMarker> markers = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        Deque<Open> open = new ArrayDeque<>();
        Map<Integer, BlockRange> blocks = null;
        Map<Integer, BlockRange> blocksByHeaderEnd = new HashMap<>();
        int firstCodeIndex = firstCodeIndex(tokens);

        for (Token token : activeLineComments(tokens)) {
            Matcher matcher = MARKER.matcher(token.getText());
            if (!matcher.find()) {
                continue;
            }
            String label = matcher.group();
            int line = token.getLine();
            if (matcher.group("fileOff") != null) {
                if (token.getTokenIndex() < firstCodeIndex) {
                    markers.add(new CoverageMarker(CoverageMarker.Kind.OFF, label, line, 1, lastLine,
                            reason(token.getText().substring(matcher.end())), true));
                } else {
                    warnings.add(new Warning(line, line, label + " ignored, it must precede module code"));
                }
                continue;
            }
            if (matcher.group("on") != null) {
                if (open.isEmpty()) {
                    warnings.add(new Warning(line, line, label + " without open Cover:off or Cover:auto"));
                } else {
                    markers.add(open.pop().close(line));
                }
                continue;
            }
            CoverageMarker.Kind kind = matcher.group("off") != null ? CoverageMarker.Kind.OFF : CoverageMarker.Kind.AUTO;
            String reason = reason(token.getText().substring(matcher.end()));
            Token previousCode = previousCodeTokenOnLine(tokens, token);
            if (previousCode != null) {
                if (blocks == null) {
                    blocks = new HashMap<>();
                    for (BlockRange block : blockRanges(tokenizer.getAst())) {
                        if (block.complete) {
                            blocks.merge(block.begin, block,
                                    (current, candidate) -> current.end >= candidate.end ? current : candidate);
                            if (block.headerEndToken >= 0) {
                                blocksByHeaderEnd.put(block.headerEndToken, block);
                            }
                        }
                    }
                }
                int previousIndex = previousCode.getTokenIndex();
                BlockRange endingHeader = blocksByHeaderEnd.get(previousIndex);
                BlockRange block = blocks.get(line);
                // Keep the longest block starting on the line when a marker follows a nested header.
                // A body statement after the header on that line limits the marker to the line.
                if (block == null || (block.headerEndToken >= 0
                        && previousIndex > block.headerEndToken && endingHeader == null)) {
                    block = endingHeader;
                }
                int firstLine = block == null ? line : block.begin;
                int endLine = block == null ? line : block.end;
                markers.add(new CoverageMarker(kind, label, line, firstLine, endLine, reason));
            } else {
                open.push(new Open(kind, label, line, reason));
            }
        }
        while (!open.isEmpty()) {
            Open unclosed = open.pop();
            markers.add(unclosed.close(lastLine));
            String effect = unclosed.kind == CoverageMarker.Kind.AUTO ? "lines are optional" : "excluded";
            warnings.add(new Warning(unclosed.line, lastLine,
                    unclosed.label + " is not closed, " + effect + " up to end of module"));
        }
        markers.sort(Comparator.comparingInt(CoverageMarker::getLine));
        return new CoverageMarkers(markers, warnings);
    }

    List<CoverageMarker> getMarkers() {
        return markers;
    }

    List<String> getWarnings() {
        return getWarningsIntersecting((first, last) -> true);
    }

    List<String> getWarningsIntersecting(BiPredicate<Integer, Integer> intersectsScope) {
        return warnings.stream()
                .filter(warning -> intersectsScope.test(warning.line, warning.lastAffectedLine))
                .map(warning -> warning.line + ": " + warning.message)
                .collect(Collectors.toList());
    }

    private static final class Warning {
        private final int line;
        private final int lastAffectedLine;
        private final String message;

        private Warning(int line, int lastAffectedLine, String message) {
            this.line = line;
            this.lastAffectedLine = lastAffectedLine;
            this.message = message;
        }
    }

    private static String reason(String tail) {
        return REASON_PREFIX.matcher(tail).replaceFirst("").trim();
    }

    private static int firstCodeIndex(List<Token> tokens) {
        for (Token token : tokens) {
            if (token.getChannel() == Token.DEFAULT_CHANNEL && token.getType() != Token.EOF) {
                return token.getTokenIndex();
            }
        }
        return Integer.MAX_VALUE;
    }

    // The lexer emits the same LINE_COMMENT token type inside deleted extension text.
    static List<Token> activeLineComments(List<Token> tokens) {
        List<Token> comments = new ArrayList<>();
        boolean deleted = false;
        for (Token token : tokens) {
            if (token.getType() == BSLLexer.PREPROC_DELETE) {
                deleted = true;
            } else if (token.getType() == BSLLexer.PREPROC_ENDDELETE) {
                deleted = false;
            } else if (!deleted && token.getType() == BSLLexer.LINE_COMMENT) {
                comments.add(token);
            }
        }
        return comments;
    }

    static Token previousCodeTokenOnLine(List<Token> tokens, Token comment) {
        for (int index = comment.getTokenIndex() - 1; index >= 0; index--) {
            Token token = tokens.get(index);
            if (endLine(token) < comment.getLine()) {
                break;
            }
            if (token.getChannel() == Token.DEFAULT_CHANNEL && token.getType() != Token.EOF) {
                return token;
            }
        }
        return null;
    }

    private static int endLine(Token token) {
        String text = token.getText();
        int line = token.getLine();
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    static List<BlockRange> blockRanges(ParseTree ast) {
        List<BlockRange> blocks = new ArrayList<>();
        List<BlockRange> methods = new ArrayList<>();
        List<ParserRuleContext> regions = new ArrayList<>();
        for (ParseTree node : Trees.getDescendants(ast)) {
            if (!(node instanceof ParserRuleContext)) {
                continue;
            }
            ParserRuleContext context = (ParserRuleContext) node;
            if (context instanceof BSLParser.RegionStartContext || context instanceof BSLParser.RegionEndContext) {
                if (context.getStart() != null) {
                    regions.add(context);
                }
                continue;
            }
            if (context instanceof BSLParser.ProcedureContext) {
                BSLParser.ProcDeclarationContext declaration = ((BSLParser.ProcedureContext) context).procDeclaration();
                addMethod(methods, declaration, BSLLexer.PROCEDURE_KEYWORD, BSLLexer.ENDPROCEDURE_KEYWORD, context);
            } else if (context instanceof BSLParser.FunctionContext) {
                BSLParser.FuncDeclarationContext declaration = ((BSLParser.FunctionContext) context).funcDeclaration();
                addMethod(methods, declaration, BSLLexer.FUNCTION_KEYWORD, BSLLexer.ENDFUNCTION_KEYWORD, context);
            } else if (isComplete(context) && (context instanceof BSLParser.IfStatementContext
                    || context instanceof BSLParser.TryStatementContext
                    || context instanceof BSLParser.WhileStatementContext
                    || context instanceof BSLParser.ForStatementContext
                    || context instanceof BSLParser.ForEachStatementContext)) {
                TerminalNode headerEnd = switch (context) {
                    case BSLParser.IfStatementContext condition when condition.ifBranch() != null ->
                            condition.ifBranch().THEN_KEYWORD();
                    case BSLParser.TryStatementContext attempt -> attempt.TRY_KEYWORD();
                    default -> context.getToken(BSLLexer.DO_KEYWORD, 0);
                };
                int headerEndToken = headerEnd == null ? -1 : headerEnd.getSymbol().getTokenIndex();
                blocks.add(new BlockRange(context.getStart().getLine(), context.getStop().getLine(),
                        "statement", true, headerEndToken));
            }
        }
        methods.sort(Comparator.comparingInt(method -> method.begin));
        for (int index = 0; index < methods.size(); index++) {
            BlockRange method = methods.get(index);
            // A recovered method without a real end must also guard comments after its last statement.
            int end = method.complete ? method.end
                    : index + 1 < methods.size() ? methods.get(index + 1).begin - 1 : Integer.MAX_VALUE;
            blocks.add(new BlockRange(method.begin, end, "method", method.complete,
                    method.headerEndToken, method.endToken));
        }
        regions.sort(Comparator.comparingInt(region -> region.getStart().getTokenIndex()));
        Deque<ParserRuleContext> regionStarts = new ArrayDeque<>();
        for (ParserRuleContext region : regions) {
            if (region instanceof BSLParser.RegionStartContext) {
                regionStarts.push(region);
            } else if (!regionStarts.isEmpty()) {
                blocks.add(new BlockRange(regionStarts.pop().getStart().getLine(), region.getStart().getLine(), "region", true));
            }
        }
        return blocks;
    }

    private static void addMethod(List<BlockRange> methods, ParserRuleContext declaration, int keyword,
                                  int endKeyword, ParserRuleContext context) {
        if (context.getStart() == null) {
            return;
        }
        TerminalNode end = context.getToken(endKeyword, 0);
        boolean complete = isComplete(context) && end != null && end.getSymbol().getTokenIndex() >= 0;
        int headerEndToken = isComplete(declaration) ? declaration.getStop().getTokenIndex() : -1;
        methods.add(new BlockRange(keywordLine(declaration, keyword, context),
                complete ? end.getSymbol().getLine() : Integer.MAX_VALUE, "method", complete, headerEndToken,
                complete ? end.getSymbol().getTokenIndex() : -1));
    }

    static final class BlockRange {
        final int begin;
        final int end;
        final String kind;
        final boolean complete;
        final int headerEndToken;
        private final int endToken;

        BlockRange(int begin, int end, String kind, boolean complete) {
            this(begin, end, kind, complete, -1);
        }

        private BlockRange(int begin, int end, String kind, boolean complete, int headerEndToken) {
            this(begin, end, kind, complete, headerEndToken, -1);
        }

        private BlockRange(int begin, int end, String kind, boolean complete, int headerEndToken, int endToken) {
            this.begin = begin;
            this.end = end;
            this.kind = kind;
            this.complete = complete;
            this.headerEndToken = headerEndToken;
            this.endToken = endToken;
        }

        boolean containsMarker(int line, int precedingCodeToken) {
            return begin <= line && line <= end
                    && (line < end || endToken < 0 || precedingCodeToken <= endToken);
        }
    }

    private static boolean isComplete(ParserRuleContext context) {
        return context != null && context.getStart() != null && context.getStop() != null
                && context.getStop().getTokenIndex() >= context.getStart().getTokenIndex();
    }

    private static int keywordLine(ParserRuleContext declaration, int keyword, ParserRuleContext block) {
        if (declaration != null) {
            TerminalNode node = declaration.getToken(keyword, 0);
            if (node != null) {
                return node.getSymbol().getLine();
            }
        }
        return block.getStart().getLine();
    }

    private static final class Open {
        private final CoverageMarker.Kind kind;
        private final String label;
        private final int line;
        private final String reason;

        private Open(CoverageMarker.Kind kind, String label, int line, String reason) {
            this.kind = kind;
            this.label = label;
            this.line = line;
            this.reason = reason;
        }

        private CoverageMarker close(int endLine) {
            return new CoverageMarker(kind, label, line, line, endLine, reason);
        }
    }
}
