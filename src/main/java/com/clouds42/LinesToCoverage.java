/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2021
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
import com.github._1c_syntax.bsl.parser.BSLParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.antlr.v4.runtime.tree.Trees;

public class LinesToCoverage {

    static int[] getLines(BSLParserRuleContext ast) {

        return Trees.getDescendants(ast).stream()
                .filter(node -> !(node instanceof TerminalNodeImpl))
                .filter(LinesToCoverage::mustCovered)
                .mapToInt(node -> ((BSLParserRuleContext) node).getStart().getLine())
                .distinct().toArray();
    }

    static boolean mustCovered(ParseTree node) {
        return (node instanceof BSLParser.StatementContext
                && Trees.getChildren(node).stream().noneMatch(parseTree ->
                parseTree instanceof BSLParser.PreprocessorContext
                        || parseTree instanceof BSLParser.CompoundStatementContext
                        && Trees.getChildren(parseTree).stream().anyMatch(
                        child -> child instanceof BSLParser.TryStatementContext)))
                || node instanceof BSLParser.GlobalMethodCallContext;
    }

}
