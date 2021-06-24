package com.clouds42;

import com.github._1c_syntax.bsl.parser.BSLParser;
import com.github._1c_syntax.bsl.parser.BSLParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.antlr.v4.runtime.tree.Trees;

public class LinesToCover {

    static int[] getLines(BSLParserRuleContext ast) {

        return Trees.getDescendants(ast).stream()
                .filter(node -> !(node instanceof TerminalNodeImpl))
                .filter(LinesToCover::mustCovered)
                .mapToInt(node -> ((BSLParserRuleContext) node).getStart().getLine())
                .distinct().toArray();
    }

    static boolean mustCovered(ParseTree node) {
        return (node instanceof BSLParser.StatementContext
                && Trees.getChildren(node).stream().noneMatch(parseTree ->
                parseTree instanceof BSLParser.PreprocessorContext
                        || parseTree instanceof BSLParser.CompoundStatementContext
                        && Trees.getChildren(parseTree).stream().anyMatch(
                        parseTree1 -> parseTree1 instanceof BSLParser.TryStatementContext)))
                || node instanceof BSLParser.GlobalMethodCallContext;
    }

}
