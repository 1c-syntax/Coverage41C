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

import com.github._1c_syntax.bsl.parser.BSLParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Trees;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

// Procedures and functions whose names start with an includePrefix prefix. Boundaries come from the BSL parser,
// so names inside strings and comments are not methods.
final class PrefixRoutines {

    static final PrefixRoutines NONE = new PrefixRoutines(Collections.emptyList(), Collections.emptyList());

    private final List<Routine> routines;
    private final List<String> warnings;

    private PrefixRoutines(List<Routine> routines, List<String> warnings) {
        this.routines = routines;
        this.warnings = warnings;
    }

    // The parse tree is requested only when the text has a method keyword followed by a prefix.
    static PrefixRoutines parse(String moduleText, Supplier<? extends ParseTree> ast,
                                List<ExclusionRules.ScopePrefix> prefixes, Pattern precheck, String modulePath) {
        if (prefixes.isEmpty() || precheck == null || !precheck.matcher(moduleText).find()) {
            return NONE;
        }
        List<Routine> routines = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ParseTree node : Trees.getDescendants(ast.get())) {
            ParserRuleContext declaration;
            TerminalNode keyword;
            TerminalNode end;
            if (node instanceof BSLParser.ProcedureContext) {
                BSLParser.ProcedureContext procedure = (BSLParser.ProcedureContext) node;
                declaration = procedure.procDeclaration();
                keyword = declaration == null ? null : procedure.procDeclaration().PROCEDURE_KEYWORD();
                end = procedure.ENDPROCEDURE_KEYWORD();
            } else if (node instanceof BSLParser.FunctionContext) {
                BSLParser.FunctionContext function = (BSLParser.FunctionContext) node;
                declaration = function.funcDeclaration();
                keyword = declaration == null ? null : function.funcDeclaration().FUNCTION_KEYWORD();
                end = function.ENDFUNCTION_KEYWORD();
            } else {
                continue;
            }
            String name = name(declaration);
            if (name == null) {
                continue;
            }
            List<ExclusionRules.ScopePrefix> matches = prefixes.stream()
                    .filter(candidate -> candidate.matchesRoutine(name)).toList();
            if (matches.isEmpty()) {
                continue;
            }
            ParserRuleContext context = (ParserRuleContext) node;
            int begin = keyword != null && keyword.getSymbol().getTokenIndex() >= 0
                    ? keyword.getSymbol().getLine() : context.getStart().getLine();
            if (end == null || end.getSymbol().getTokenIndex() < 0) {
                warnings.add(modulePath + ":" + begin + " → " + matches.get(0).getSource() + ": method " + name
                        + " has no end, not counted");
                continue;
            }
            routines.add(new Routine(name, begin, end.getSymbol().getLine(), matches));
        }
        routines.sort(Comparator.comparingInt(Routine::getBeginLine));
        return new PrefixRoutines(Collections.unmodifiableList(routines), Collections.unmodifiableList(warnings));
    }

    private static String name(ParserRuleContext declaration) {
        if (declaration == null) {
            return null;
        }
        BSLParser.SubNameContext subName = declaration instanceof BSLParser.ProcDeclarationContext
                ? ((BSLParser.ProcDeclarationContext) declaration).subName()
                : ((BSLParser.FuncDeclarationContext) declaration).subName();
        if (subName == null || subName.IDENTIFIER() == null || subName.IDENTIFIER().getSymbol().getTokenIndex() < 0) {
            return null;
        }
        return subName.IDENTIFIER().getText();
    }

    boolean isEmpty() {
        return routines.isEmpty();
    }

    boolean covers(int line) {
        return routineAt(line) != null;
    }

    boolean intersects(int firstLine, int lastLine) {
        return routines.stream().anyMatch(routine -> routine.beginLine <= lastLine && firstLine <= routine.endLine);
    }

    Routine routineAt(int line) {
        for (Routine routine : routines) {
            if (routine.beginLine <= line && line <= routine.endLine) {
                return routine;
            }
        }
        return null;
    }

    List<Routine> getRoutines() {
        return routines;
    }

    List<String> getWarnings() {
        return warnings;
    }

    static final class Routine {
        private final String name;
        private final int beginLine;
        private final int endLine;
        private final List<ExclusionRules.ScopePrefix> prefixes;

        private Routine(String name, int beginLine, int endLine, List<ExclusionRules.ScopePrefix> prefixes) {
            this.name = name;
            this.beginLine = beginLine;
            this.endLine = endLine;
            this.prefixes = prefixes;
        }

        String getName() {
            return name;
        }

        int getBeginLine() {
            return beginLine;
        }

        int getEndLine() {
            return endLine;
        }

        List<ExclusionRules.ScopePrefix> getPrefixes() {
            return prefixes;
        }
    }
}
