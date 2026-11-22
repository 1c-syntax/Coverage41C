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

import com.github._1c_syntax.bsl.parser.BSLTokenizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeBlocksTest {

    private static final String DEFAULT = "{'changeMarkers':{}}";
    private static final String DEMO = "{'changeMarkers':{'begin':'^\\\\s*//\\\\s*\\\\+\\\\+\\\\s*тест(?![\\\\p{L}\\\\p{N}_])','end':'^\\\\s*//\\\\s*--\\\\s*тест(?![\\\\p{L}\\\\p{N}_])'}}";

    @TempDir
    Path tempDir;

    @Test
    void blockCoversLinesBetweenMarkers() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT,
                "Процедура А()", "\tТиповой();", "\t// ++ уч", "\tНаш();", "\t// -- уч", "КонецПроцедуры");

        assertTrue(blocks.covers(4));
        assertFalse(blocks.covers(2));
        assertFalse(blocks.covers(3));
        assertFalse(blocks.covers(5));
        assertEquals(1, blocks.bySource().get("team.json → changeMarkers[0]").size());
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void nestedBlocksOfOnePairAreCounted() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "//++", "А();", "//++", "Б();", "//--", "В();", "//--");

        assertTrue(blocks.covers(2));
        assertTrue(blocks.covers(4));
        assertTrue(blocks.covers(6));
        assertEquals(2, blocks.bySource().get("team.json → changeMarkers[0]").size());
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void unclosedBlockIsNotCountedWithWarning() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "// ++ уч", "А();");

        assertFalse(blocks.covers(2));
        assertTrue(blocks.isEmpty());
        assertEquals(List.of("Module.bsl:1 → team.json → changeMarkers[0]: block is not closed, not counted"),
                blocks.getWarnings());
    }

    @Test
    void closedBlockInsideUnclosedOneIsCounted() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "//++", "//++", "А();", "//--", "Б();");

        assertTrue(blocks.covers(3));
        assertFalse(blocks.covers(5));
        assertEquals(List.of("Module.bsl:1 → team.json → changeMarkers[0]: block is not closed, not counted"),
                blocks.getWarnings());
    }

    @Test
    void endWithoutBeginIsWarned() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "А();", "// -- уч");

        assertTrue(blocks.isEmpty());
        assertEquals(List.of("Module.bsl:2 → team.json → changeMarkers[0]: end without begin"), blocks.getWarnings());
    }

    @Test
    void pairsAreParsedSeparately() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "// {{DEMO", "//++", "А();", "// }}DEMO", "Б();", "//--");

        assertTrue(blocks.covers(3));
        assertTrue(blocks.covers(5));
        List<ChangeBlocks.Block> pairs = blocks.bySource().get("team.json → changeMarkers[0]");
        assertEquals(2, pairs.size());
        assertEquals(Set.of(List.of(1, 4), List.of(2, 6)), pairs.stream()
                .map(block -> List.of(block.getBeginLine(), block.getEndLine())).collect(Collectors.toSet()));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void markersInsideDeletedExtensionTextDoNotOpenOrCloseBlocks() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT,
                "Процедура Обработать()", "#Удаление", "//++", "#КонецУдаления", "ПервыйВызов();", "//--",
                "//++", "#Delete", "//--", "#EndDelete", "ВторойВызов();", "//--", "КонецПроцедуры");

        assertFalse(blocks.covers(5));
        assertTrue(blocks.covers(11));
        assertEquals(1, blocks.bySource().get("team.json → changeMarkers[0]").size());
        assertEquals(List.of("Module.bsl:6 → team.json → changeMarkers[0]: end without begin"), blocks.getWarnings());
    }

    @Test
    void separatorIsNotEnd() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "//++", "// -------------", "А();", "//--");

        assertTrue(blocks.covers(3));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void markerInsideMultilineStringIsIgnored() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "Текст = \"", "\t|// ++ уч", "\t|\";");

        assertTrue(blocks.isEmpty());
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void sameMarkerOpensAndClosesBlock() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{'begin':'^\\\\s*//\\\\s*DEMO','end':'^\\\\s*//\\\\s*DEMO'}}",
                "// DEMO", "А();", "// DEMO", "Б();");

        assertTrue(blocks.covers(2));
        assertFalse(blocks.covers(4));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void sourcesCoveringNameEveryPairOnce() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "// {{DEMO", "//++", "А();", "//--", "// }}DEMO");

        assertEquals(List.of("team.json → changeMarkers[0]"), List.copyOf(blocks.sourcesCovering(3)));
    }

    @Test
    void enabledClosesAtMethodRegionAndModuleIncludingBoundary() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "#Область Внешняя", "#Область Внутренняя", "//++", "А();", "#КонецОбласти",
                "Б();", "#КонецОбласти", "Процедура Тест()", "//++", "В();", "КонецПроцедуры",
                "//++", "Г();");
        assertTrue(blocks.covers(4));
        assertFalse(blocks.covers(6));
        assertTrue(blocks.covers(10));
        assertTrue(blocks.covers(11));
        assertFalse(blocks.covers(12));
        assertTrue(blocks.covers(13));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void scopeKeywordsInCommentsAndStringsDoNotCloseBlock() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "Procedure Test()", "//++", "// EndProcedure", "Text = \"EndProcedure\";",
                "Text = \"", "|#EndRegion", "|EndProcedure\";", "Call();", "EndProcedure", "Other();");
        assertTrue(blocks.covers(8));
        assertTrue(blocks.covers(9));
        assertFalse(blocks.covers(10));
    }

    @Test
    void disabledKeepsWarning() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':false}}", "//++", "А();");
        assertTrue(blocks.isEmpty());
        assertEquals(1, blocks.getWarnings().size());
    }

    @Test
    void functionTakesPriorityOverNestedRegionAndClosedBlocksAreUnchanged() throws Exception {
        String[] lines = {"Function Test()", "#Region Inner", "//++", "Return 1;", "#EndRegion", "EndFunction"};
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}", lines);
        assertTrue(blocks.covers(6));
        assertEquals("method", blocks.bySource().values().iterator().next().get(0).getBoundary());
        String[] closed = {"//++", "Call();", "//--", "Other();"};
        ChangeBlocks enabled = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}", closed);
        ChangeBlocks disabled = parse(DEFAULT, closed);
        for (int line = 1; line <= closed.length; line++) {
            assertEquals(disabled.covers(line), enabled.covers(line));
        }
        assertFalse(enabled.bySource().values().iterator().next().get(0).isAutomaticallyClosed());
    }

    @Test
    void severalUnclosedMethodsKeepIndependentBoundaries() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "Procedure First()", "//++", "Call();", "EndProcedure",
                "Procedure Second()", "Before();", "//++", "Call();", "EndProcedure");
        assertTrue(blocks.covers(4));
        assertFalse(blocks.covers(5));
        assertFalse(blocks.covers(6));
        assertTrue(blocks.covers(9));
        assertEquals(2, blocks.bySource().values().iterator().next().size());
    }

    @Test
    void closedNestedBlockKeepsItsEndInsideAutomaticallyClosedBlock() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "//++", "//++", "Call();", "//--", "Other();");
        List<ChangeBlocks.Block> found = blocks.bySource().values().iterator().next();
        assertEquals(2, found.size());
        assertEquals(4, found.get(0).getEndLine());
        assertFalse(found.get(0).isAutomaticallyClosed());
        assertTrue(blocks.covers(5));
        assertEquals("module", found.get(1).getBoundary());
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void regionBetweenMethodsDoesNotBelongToFollowingMethod() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "#Область Внешняя", "Процедура А()", "КонецПроцедуры", "#Область Внутренняя",
                "//++", "Процедура Б()", "Наш();", "КонецПроцедуры",
                "Процедура В()", "ЕщеНаш();", "КонецПроцедуры", "#КонецОбласти", "#КонецОбласти");
        ChangeBlocks.Block block = blocks.bySource().values().iterator().next().get(0);
        assertEquals("region", block.getBoundary());
        assertEquals(12, block.getEndLine());
        assertTrue(blocks.covers(10));
        assertFalse(blocks.covers(13));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void methodWithoutEndDoesNotReturnFollowingMethodToCoverage() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "Процедура А()", "//++", "Б();", "// хвост повреждённого метода",
                "Процедура В()", "Чужой();", "КонецПроцедуры");
        assertTrue(blocks.isEmpty());
        assertFalse(blocks.covers(6));
        assertEquals(1, blocks.getWarnings().size());
    }

    @Test
    void markerAfterLastStatementOfBrokenMethodDoesNotFallBackToModule() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "Функция А()", "Б();", "// хвост повреждённого метода", "//++",
                "Процедура В()", "Чужой();", "КонецПроцедуры");
        assertTrue(blocks.isEmpty());
        assertFalse(blocks.covers(6));
        assertEquals(1, blocks.getWarnings().size());
        assertTrue(blocks.getWarnings().get(0).contains("method boundary is incomplete"));
    }

    @Test
    void recoveredSyntaxErrorKeepsRealMethodBoundary() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "Процедура А(", "//++", "Б();", "Если Тогда", "КонецПроцедуры",
                "Процедура В()", "Чужой();", "КонецПроцедуры");
        ChangeBlocks.Block block = blocks.bySource().values().iterator().next().get(0);
        assertEquals("method", block.getBoundary());
        assertEquals(5, block.getEndLine());
        assertFalse(blocks.covers(7));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void methodEndAfterStatementOnSameLineIsIncluded() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "Функция А()", "//++", "Возврат 1;КонецФункции",
                "Процедура Б()", "Чужой();", "КонецПроцедуры");
        ChangeBlocks.Block block = blocks.bySource().values().iterator().next().get(0);
        assertEquals("method", block.getBoundary());
        assertEquals(3, block.getEndLine());
        assertTrue(blocks.covers(3));
        assertFalse(blocks.covers(5));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void skipsAstWithoutUnclosedBlocks() throws Exception {
        String config = "{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}";
        Supplier<ParseTree> unusedAst = () -> {
            throw new AssertionError("AST must not be requested without unclosed blocks");
        };
        assertTrue(parseWithAst(config, unusedAst, "Call();").isEmpty());
        ChangeBlocks closed = parseWithAst(config, unusedAst, "//++", "Call();", "//--");
        assertTrue(closed.covers(2));
        assertTrue(closed.getWarnings().isEmpty());
        ChangeBlocks unmatchedEnd = parseWithAst(config, unusedAst, "//--");
        assertEquals(1, unmatchedEnd.getWarnings().size());
    }

    @Test
    void skipsAstWhenAutomaticClosureIsDisabled() throws Exception {
        ChangeBlocks blocks = parseWithAst(DEFAULT, () -> {
            throw new AssertionError("AST must not be requested when automatic closure is disabled");
        }, "//++", "Call();");
        assertTrue(blocks.isEmpty());
        assertEquals(1, blocks.getWarnings().size());
    }

    @Test
    void requestsAstOnceForMultipleUnclosedMarkerPairs() throws Exception {
        String[] lines = {"Procedure Test()", "//++", "// {{DEMO", "Call();", "EndProcedure"};
        AtomicInteger requests = new AtomicInteger();
        BSLTokenizer tokenizer = new BSLTokenizer(String.join("\n", lines));
        ChangeBlocks blocks = parseWithAst("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}", () -> {
            requests.incrementAndGet();
            return tokenizer.getAst();
        }, lines);
        assertEquals(1, requests.get());
        assertEquals(2, blocks.bySource().values().iterator().next().size());
        assertTrue(blocks.covers(5));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void endMarkerAfterCodeIncludesItsLine() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "Процедура А()", "\t// ++ уч", "\tНаш();", "КонецПроцедуры // -- уч");
        assertFalse(blocks.covers(1));
        assertFalse(blocks.covers(2));
        assertTrue(blocks.covers(3));
        assertTrue(blocks.covers(4));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void beginMarkerAfterCodeIncludesItsLine() throws Exception {
        ChangeBlocks blocks = parse(DEMO,
                "Процедура тестВыполнить(П1, П2) // ++ тест", "\tНаш();", "КонецПроцедуры // -- тест");
        assertTrue(blocks.covers(1));
        assertTrue(blocks.covers(2));
        assertTrue(blocks.covers(3));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void markersAfterRegionAndPreprocessorDirectivesAreRecognized() throws Exception {
        ChangeBlocks blocks = parse(DEMO, "#Область тестОбработчики // ++ тест", "Процедура тестВыполнить()",
                "\tНаш();", "КонецПроцедуры", "#КонецОбласти // -- тест",
                "#Если Сервер Тогда // ++ тест", "\tСервер();", "#КонецЕсли // -- тест", "Чужой();");
        for (int line = 1; line <= 8; line++) {
            assertTrue(blocks.covers(line), "line " + line);
        }
        assertFalse(blocks.covers(9));
        assertEquals(2, blocks.bySource().get("team.json → changeMarkers[0]").size());
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void markerInsideStringLiteralOnCodeLineIsIgnored() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "Текст = \"// ++ уч\";", "\tНаш();", "Текст2 = \"// ++ уч\"; // -- уч");
        assertTrue(blocks.isEmpty());
        assertEquals(1, blocks.getWarnings().size());
        assertTrue(blocks.getWarnings().get(0).endsWith("end without begin"), blocks.getWarnings().get(0));
    }

    @Test
    void commentOnlyMarkerLinesStayOutsideBlock() throws Exception {
        ChangeBlocks blocks = parse(DEFAULT, "А(); // ++", "Б();", "// --", "В();");
        assertTrue(blocks.covers(1));
        assertTrue(blocks.covers(2));
        assertFalse(blocks.covers(3));
        assertFalse(blocks.covers(4));
    }

    @Test
    void inlineBeginOnProcedureLineClosesAtMethodEnd() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "Процедура А() // ++", "\tНаш();", "КонецПроцедуры", "Процедура Б()", "\tЧужой();", "КонецПроцедуры");
        ChangeBlocks.Block block = blocks.bySource().values().iterator().next().get(0);
        assertEquals("method", block.getBoundary());
        assertEquals(3, block.getEndLine());
        assertTrue(blocks.covers(1));
        assertTrue(blocks.covers(2));
        assertTrue(blocks.covers(3));
        assertFalse(blocks.covers(5));
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void inlineBeginOnRegionLineClosesAtRegionEnd() throws Exception {
        ChangeBlocks blocks = parse("{'changeMarkers':{},'unclosedBlocks':{'enabled':true}}",
                "#Область тестХ // ++", "А();", "#КонецОбласти", "Б();");
        assertEquals("region", blocks.bySource().values().iterator().next().get(0).getBoundary());
        assertTrue(blocks.covers(1));
        assertTrue(blocks.covers(2));
        assertTrue(blocks.covers(3));
        assertFalse(blocks.covers(4));
    }

    @Test
    void tokensAreNotRequestedWithoutMarkerLikeComments() throws Exception {
        Supplier<List<Token>> unusedTokens = () -> {
            throw new AssertionError("tokens must not be requested without marker-like comments");
        };
        ChangeBlocks blocks = parseWithTokens(DEFAULT, unusedTokens, "Call(); // обычный комментарий", "// ------", "Call2();");
        assertTrue(blocks.isEmpty());
        assertTrue(blocks.getWarnings().isEmpty());
    }

    @Test
    void tokensAreRequestedOnceForSeveralMarkers() throws Exception {
        String[] lines = {"А(); // ++ уч", "Б();", "В(); // -- уч", "// {{DEMO", "Г();", "// }}DEMO"};
        AtomicInteger requests = new AtomicInteger();
        BSLTokenizer tokenizer = new BSLTokenizer(String.join("\n", lines));
        ChangeBlocks blocks = parseWithTokens(DEFAULT, () -> {
            requests.incrementAndGet();
            return tokenizer.getTokens();
        }, lines);
        assertEquals(1, requests.get());
        assertEquals(2, blocks.bySource().values().iterator().next().size());
    }

    private ChangeBlocks parse(String json, String... lines) throws IOException {
        BSLTokenizer tokenizer = new BSLTokenizer(String.join("\n", lines));
        return parseWith(json, tokenizer::getTokens, tokenizer::getAst, lines);
    }

    private ChangeBlocks parseWithAst(String json, Supplier<? extends ParseTree> ast, String... lines)
            throws IOException {
        BSLTokenizer tokenizer = new BSLTokenizer(String.join("\n", lines));
        return parseWith(json, tokenizer::getTokens, ast, lines);
    }

    private ChangeBlocks parseWithTokens(String json, Supplier<List<Token>> tokens, String... lines)
            throws IOException {
        BSLTokenizer tokenizer = new BSLTokenizer(String.join("\n", lines));
        return parseWith(json, tokens, tokenizer::getAst, lines);
    }

    private ChangeBlocks parseWith(String json, Supplier<List<Token>> tokens, Supplier<? extends ParseTree> ast,
                                   String... lines) throws IOException {
        Path file = tempDir.resolve("team.json");
        Files.writeString(file, json.replace('\'', '"'));
        List<ExclusionRules.ChangeMarker> markers =
                ExclusionRules.load(file.toFile(), tempDir, null, new ExclusionReport()).getChangeMarkers();
        return ChangeBlocks.parse(lines, markers, "Module.bsl", tokens, ast);
    }
}
