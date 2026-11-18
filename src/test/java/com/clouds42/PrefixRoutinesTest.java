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
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefixRoutinesTest {

    private static final ExclusionRules.PrefixScope SCOPE =
            new ExclusionRules.PrefixScope(true, true, true, true, List.of());
    private static final ExclusionRules.ScopePrefix DEMO =
            new ExclusionRules.ScopePrefix("a.json → includePrefix[0]", "тест", Set.of(), SCOPE);
    private static final ExclusionRules.ScopePrefix DEMO_LATIN =
            new ExclusionRules.ScopePrefix("a.json → includePrefix[1]", "demo", Set.of(), SCOPE);
    private static final List<ExclusionRules.ScopePrefix> PREFIXES = List.of(DEMO, DEMO_LATIN);

    @Test
    void findsProceduresAndFunctionsWithPrefix() {
        PrefixRoutines routines = parse("Процедура Типовая()", "\tБ();", "КонецПроцедуры", "",
                "Процедура тестОбработать(Документ) Экспорт", "\tВ();", "КонецПроцедуры", "",
                "Функция тестПолучить()", "\tВозврат 1;", "КонецФункции");

        assertEquals(List.of("тестОбработать", "тестПолучить"), names(routines));
        assertEquals(5, routines.getRoutines().get(0).getBeginLine());
        assertEquals(7, routines.getRoutines().get(0).getEndLine());
        assertEquals(9, routines.getRoutines().get(1).getBeginLine());
        assertEquals(11, routines.getRoutines().get(1).getEndLine());
        assertFalse(routines.covers(2));
        assertFalse(routines.covers(4));
        assertTrue(routines.covers(5));
        assertTrue(routines.covers(7));
        assertFalse(routines.covers(8));
        assertTrue(routines.getWarnings().isEmpty());
    }

    @Test
    void commentBetweenKeywordAndNameDoesNotDefeatRoutinePrecheck() {
        PrefixRoutines routines = parse("Процедура // Описание", "// продолжение", "тестРассчитать()",
                "Рассчитать();", "КонецПроцедуры");

        assertEquals(List.of("тестРассчитать"), names(routines));
    }

    @Test
    void prefixIsCaseSensitiveAndComparedWithStartOfName() {
        PrefixRoutines routines = parse("Процедура ТестХ()", "КонецПроцедуры", "Процедура подмодульХ()", "КонецПроцедуры",
                "Процедура тест()", "КонецПроцедуры", "Процедура тестХ_Ещё()", "КонецПроцедуры");

        assertEquals(List.of("тест", "тестХ_Ещё"), names(routines));
    }

    @Test
    void severalPrefixesAreAttributedToTheirSources() {
        PrefixRoutines routines = parse("Процедура demoX()", "КонецПроцедуры", "Процедура тестY()", "КонецПроцедуры");

        assertEquals(List.of(DEMO_LATIN), routines.getRoutines().get(0).getPrefixes());
        assertEquals(List.of(DEMO), routines.getRoutines().get(1).getPrefixes());
        assertEquals(List.of(DEMO), routines.routineAt(3).getPrefixes());
        assertEquals(null, routines.routineAt(5));
    }

    @Test
    void directivesAnnotationsAsyncAndPreprocessorDoNotMoveBoundaries() {
        PrefixRoutines routines = parse("&НаКлиенте", "Асинх Процедура тестХ() Экспорт", "\tА();", "КонецПроцедуры",
                "#Если Сервер Тогда", "&НаСервере", "Функция тестY() Экспорт", "\tВозврат 1;", "КонецФункции",
                "#КонецЕсли");

        assertEquals(List.of(2, 7), routines.getRoutines().stream().map(PrefixRoutines.Routine::getBeginLine)
                .collect(Collectors.toList()));
        assertFalse(routines.covers(1));
        assertTrue(routines.covers(4));
        assertFalse(routines.covers(5));
        assertFalse(routines.covers(6));
        assertTrue(routines.covers(9));
        assertFalse(routines.covers(10));
    }

    @Test
    void englishKeywordsAreRecognized() {
        PrefixRoutines routines = parse("Procedure demoX()", "\tA();", "EndProcedure");

        assertEquals(List.of("demoX"), names(routines));
        assertEquals(3, routines.getRoutines().get(0).getEndLine());
    }

    @Test
    void methodAtEndOfModuleWithoutTrailingNewlineIsFound() {
        String text = "Процедура тестХ()\n\tА();\nКонецПроцедуры";

        PrefixRoutines routines = PrefixRoutines.parse(text, ast(text), PREFIXES, precheck(), "Module.bsl");

        assertEquals(3, routines.getRoutines().get(0).getEndLine());
    }

    @Test
    void bomAndCrlfKeepLineNumbers() {
        String text = "\uFEFFПроцедура Типовая()\r\nКонецПроцедуры\r\nПроцедура тестХ()\r\n\tА();\r\nКонецПроцедуры\r\n";

        PrefixRoutines routines = PrefixRoutines.parse(text, ast(text), PREFIXES, precheck(), "Module.bsl");

        assertEquals(3, routines.getRoutines().get(0).getBeginLine());
        assertEquals(5, routines.getRoutines().get(0).getEndLine());
    }

    @Test
    void namesInStringsCommentsAndCallsAreNotRoutines() {
        PrefixRoutines routines = parse("Процедура Типовая()", "\tТекст = \"Процедура тестХ()\";",
                "\t// Процедура тестY()", "\tтестZ();", "КонецПроцедуры");

        assertTrue(routines.isEmpty());
    }

    @Test
    void codeOutsideMethodsIsNotCovered() {
        PrefixRoutines routines = parse("Процедура тестХ()", "\tА();", "КонецПроцедуры", "Б();");

        assertTrue(routines.covers(3));
        assertFalse(routines.covers(4));
    }

    @Test
    void methodWithoutEndIsWarnedAndNotCounted() {
        PrefixRoutines routines = parse("Процедура тестХ()", "\tА();", "", "Процедура Другая()", "\tБ();",
                "КонецПроцедуры");

        assertFalse(routines.covers(2));
        assertFalse(routines.covers(5));
        assertEquals(List.of("Module.bsl:1 → a.json → includePrefix[0]: method тестХ has no end, not counted"),
                routines.getWarnings());
    }

    @Test
    void astIsNotRequestedWithoutPrefixedMethodKeyword() {
        String text = "Процедура Типовая()\n\tтестХ();\nКонецПроцедуры\n";

        PrefixRoutines routines = PrefixRoutines.parse(text, () -> {
            throw new AssertionError("AST requested");
        }, PREFIXES, precheck(), "Module.bsl");

        assertTrue(routines.isEmpty());
        assertSame(PrefixRoutines.NONE, routines);
    }

    private static PrefixRoutines parse(String... lines) {
        String text = String.join("\n", lines) + "\n";
        return PrefixRoutines.parse(text, ast(text), PREFIXES, precheck(), "Module.bsl");
    }

    private static Supplier<? extends ParseTree> ast(String text) {
        BSLTokenizer tokenizer = new BSLTokenizer(text);
        return tokenizer::getAst;
    }

    private static Pattern precheck() {
        return ExclusionRules.routinePrecheck(PREFIXES);
    }

    private static List<String> names(PrefixRoutines routines) {
        return routines.getRoutines().stream().map(PrefixRoutines.Routine::getName).collect(Collectors.toList());
    }
}
