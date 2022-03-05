/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2022
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
import com.github._1c_syntax.bsl.parser.BSLParserRuleContext;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Trees;

import java.util.Set;

public class LinesToCoverage {

    private static final Set<Class<? extends BSLParserRuleContext>> contexts = Set.of(
            BSLParser.AssignmentContext.class,
            BSLParser.CallStatementContext.class,
            BSLParser.GotoStatementContext.class,
            BSLParser.ReturnStatementContext.class,
            BSLParser.BreakStatementContext.class,
            BSLParser.ContinueStatementContext.class,
            BSLParser.IfStatementContext.class,
            BSLParser.ElsifBranchContext.class,
            BSLParser.RaiseStatementContext.class,
            BSLParser.ForEachStatementContext.class,
            BSLParser.ForStatementContext.class,
            BSLParser.WhileStatementContext.class,
            BSLParser.GlobalMethodCallContext.class,
            BSLParser.MethodCallContext.class,
            BSLParser.ExecuteStatementContext.class
    );

    private static final Set<Integer> tokenTypes = Set.of(
            BSLLexer.ENDDO_KEYWORD,
            BSLLexer.ENDFUNCTION_KEYWORD,
            BSLLexer.ENDPROCEDURE_KEYWORD,
            BSLLexer.ENDTRY_KEYWORD,
            BSLLexer.ENDIF_KEYWORD,
            BSLLexer.DO_KEYWORD
    );

    static int[] getLines(BSLParserRuleContext ast) {

        return Trees.getDescendants(ast).stream()
                .filter(LinesToCoverage::mustCovered)
                .filter(GlobalCallsFilter::filterByName)
                .mapToInt(LinesToCoverage::getLine)
                .filter(lineNumber -> lineNumber != 0)
                .distinct().toArray();
    }

    static boolean mustCovered(ParseTree node) {

        if (node instanceof ParserRuleContext) {
            return contexts.contains(node.getClass());
        } else if (node instanceof TerminalNode) {
            return tokenTypes.contains(
                    ((TerminalNode) node).getSymbol().getType()
            );
        }

        throw new IllegalArgumentException();
    }

    private static int getLine(ParseTree node) {

        if (node instanceof ParserRuleContext) {
            if (!(node instanceof BSLParser.MethodCallContext)) {
                return ((ParserRuleContext) node).getStart().getLine();
            }
            var methodCall = getRootParent((BSLParserRuleContext) node, BSLParser.RULE_complexIdentifier);
            if (methodCall != null) {
                return methodCall.getStart().getLine();
            }
        } else if (node instanceof TerminalNode) {
            return ((TerminalNode) node).getSymbol().getLine();
        }

        return 0;
    }

    // Удалить после переноса хелпера в парсер

    /**
     * Рекурсивно находит самого верхнего родителя текущей ноды нужного типа
     *
     * @param tnc       - нода, для которой ищем родителя
     * @param ruleindex - BSLParser.RULE_*
     * @return tnc - если родитель не найден, вернет null
     */
    public static BSLParserRuleContext getRootParent(BSLParserRuleContext tnc, int ruleindex) {
        final var parent = tnc.getParent();
        if (parent == null) {
            return null;
        }

        if (getRuleIndex(parent) == ruleindex) {
            return (BSLParserRuleContext) parent;
        } else {
            return getRootParent((BSLParserRuleContext) parent, ruleindex);
        }
    }

    private static int getRuleIndex(ParseTree node) {
        if (node instanceof TerminalNode) {
            return ((TerminalNode) node).getSymbol().getType();
        } else {
            return ((BSLParserRuleContext) node).getRuleIndex();
        }
    }
}
