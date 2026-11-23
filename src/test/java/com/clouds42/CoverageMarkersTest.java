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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoverageMarkersTest {

    @Test
    void offAndOnMakeRange() {
        CoverageMarkers markers = parse(
                "Процедура А()", "\tБ();", "\t// Cover:off", "\tВ();", "\t// Cover:on", "\tГ();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 3..5"), ranges(markers));
        assertEquals(List.of(), markers.getWarnings());
    }

    @Test
    void autoMakesAutoRange() {
        CoverageMarkers markers = parse(
                "Процедура А()", "\tБ();", "\t// Cover:auto", "\tВ();", "\t// Cover:on", "\tГ();", "КонецПроцедуры");
        assertEquals(List.of("AUTO Cover:auto 3..5"), ranges(markers));
    }

    @Test
    void acceptsRussianSynonymsInAnyCase() {
        CoverageMarkers markers = parse(
                "Процедура А()", "\t// COVER:Выкл", "\tБ();", "\t// cover:ВКЛ",
                "\t// Cover:авто", "\tВ();", "\t// Cover:вкл", "КонецПроцедуры");
        assertEquals(List.of("OFF COVER:Выкл 2..4", "AUTO Cover:авто 5..7"), ranges(markers));
    }

    @Test
    void ignoresLongerWordsAndStringLiterals() {
        CoverageMarkers markers = parse(
                "Процедура А()", "\t// Cover:offline", "\tСообщить(\"// Cover:off\");", "КонецПроцедуры");
        assertEquals(List.of(), ranges(markers));
        assertEquals(List.of(), markers.getWarnings());
    }

    @Test
    void markersInsideDeletedExtensionTextAreIgnored() {
        CoverageMarkers markers = parse(
                "#Удаление", "// Cover:file-off", "#КонецУдаления",
                "Процедура Обработать()", "// Cover:auto", "# Delete", "// Cover:off", "// Cover:on",
                "# EndDelete", "Обновить();", "// Cover:on", "КонецПроцедуры");

        assertEquals(List.of("AUTO Cover:auto 5..11"), ranges(markers));
        assertEquals(List.of(), markers.getWarnings());
    }

    @Test
    void markerFollowedByHyphenAndReasonIsRecognized() {
        CoverageMarkers markers = parse(
                "Процедура А()", "\t// Cover:off-временный код", "\tБ();", "\t// Cover:on", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 2..4"), ranges(markers));
        assertEquals("временный код", markers.getMarkers().get(0).getReason());
    }

    @Test
    void onClosesOnlyLastOpenRange() {
        CoverageMarkers markers = parse(
                "Процедура А()", "\t// Cover:off", "\tБ();", "\t// Cover:off", "\tВ();", "\t// Cover:on",
                "\tГ();", "\t// Cover:on", "\tД();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 2..8", "OFF Cover:off 4..6"), ranges(markers));
        assertEquals(List.of(), markers.getWarnings());
    }

    @Test
    void unclosedRangeRunsToEndOfModuleWithWarning() {
        CoverageMarkers markers = parse(
                "Процедура А()", "\tБ();", "\t// Cover:off", "\tВ();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 3..6"), ranges(markers));
        assertEquals(List.of("3: Cover:off is not closed, excluded up to end of module"), markers.getWarnings());
    }

    @Test
    void onWithoutOpenRangeIsWarned() {
        CoverageMarkers markers = parse("Процедура А()", "\t// Cover:on", "\tБ();", "КонецПроцедуры");
        assertEquals(List.of(), ranges(markers));
        assertEquals(List.of("2: Cover:on without open Cover:off or Cover:auto"), markers.getWarnings());
    }

    @Test
    void textAfterMarkerIsReason() {
        CoverageMarkers markers = parse(
                "Процедура А()", "\t// Cover:off: временный код", "\tБ();", "\t// Cover:on",
                "\t// Cover:auto — ждёт теста", "\tВ();", "\t// Cover:on", "КонецПроцедуры");
        assertEquals(List.of("временный код", "ждёт теста"),
                markers.getMarkers().stream().map(CoverageMarker::getReason).collect(Collectors.toList()));
    }

    @Test
    void inlineMarkerExcludesItsLine() {
        CoverageMarkers markers = parse("Процедура А()", "\tБ(); // Cover:off", "\tВ();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 2..2"), ranges(markers));
        assertEquals(List.of(), markers.getWarnings());
    }

    @Test
    void inlineMarkerOnProcedureExcludesProcedure() {
        CoverageMarkers markers = parse("&НаКлиенте", "Процедура А() // Cover:off — обработчик платформы",
                "\tБ();", "КонецПроцедуры", "", "Процедура В()", "\tГ();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 2..4"), ranges(markers));
        assertEquals("обработчик платформы", markers.getMarkers().get(0).getReason());
    }

    @Test
    void inlineAutoOnFunctionExcludesFunction() {
        CoverageMarkers markers = parse("Функция Ф() // Cover:auto", "\tВозврат 1;", "КонецФункции",
                "", "Процедура В()", "\tГ();", "КонецПроцедуры");
        assertEquals(List.of("AUTO Cover:auto 1..3"), ranges(markers));
    }

    @Test
    void inlineMarkerOnIfExcludesWholeIf() {
        CoverageMarkers markers = parse("Процедура П()", "\tЕсли А Тогда // Cover:off", "\t\tБ();",
                "\tИначеЕсли В Тогда", "\t\tГ();", "\tКонецЕсли;", "\tД();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 2..6"), ranges(markers));
    }

    @Test
    void inlineMarkerAfterBodyStatementOnHeaderLineOnlyCoversThatLine() {
        for (String header : List.of("Если Готово Тогда", "Пока Готово Цикл",
                "Для Сч = 1 По 2 Цикл", "Для Каждого Эл Из Массив Цикл")) {
            String end = header.startsWith("Если") ? "КонецЕсли;" : "КонецЦикла;";
            assertEquals(List.of("OFF Cover:off 2..2"), ranges(parse("Процедура П()",
                    header + " Первый(); // Cover:off", "Второй();", end, "КонецПроцедуры")), header);
        }
    }

    @Test
    void inlineMarkerAfterBodyStatementOnTryOrMethodHeaderOnlyCoversThatLine() {
        assertAll(
                () -> assertEquals(List.of("OFF Cover:off 2..2"), ranges(parse("Процедура П()",
                        "Попытка Первый(); // Cover:off", "Второй();", "Исключение", "Третий();",
                        "КонецПопытки;", "КонецПроцедуры")), "Try"),
                () -> assertEquals(List.of("AUTO Cover:auto 1..1"), ranges(parse(
                        "Процедура П() Первый(); // Cover:auto", "Второй();", "КонецПроцедуры")), "Procedure"));
    }

    @Test
    void inlineMarkerOnEndOfMultilineMethodHeaderCoversMethod() {
        assertEquals(List.of("OFF Cover:off 1..4"), ranges(parse("Процедура П(Первый,",
                "Второй) Экспорт // Cover:off", "ВыполнитьШаг();", "КонецПроцедуры")));
    }

    @Test
    void inlineMarkerBeforeExportOnFollowingLineCoversMethod() {
        for (String[] method : new String[][] {
                {"Процедура Пример(", "Экспорт", "КонецПроцедуры"},
                {"Функция Пример(", "Экспорт", "КонецФункции"},
                {"Procedure Sample(", "Export", "EndProcedure"},
                {"Function Sample(", "Export", "EndFunction"}}) {
            for (String marker : List.of("off", "auto")) {
                CoverageMarkers markers = parse(method[0], "Параметр) // Cover:" + marker,
                        method[1], "Шаг();", method[2]);

                assertEquals(List.of(marker.toUpperCase() + " Cover:" + marker + " 1..5"), ranges(markers));
                assertEquals(List.of(), markers.getWarnings());
            }
        }
    }

    @Test
    void inlineMarkerAfterBodyOnExportLineRemainsSingleLine() {
        CoverageMarkers markers = parse("Процедура Пример(", "Параметр)",
                "Экспорт Первый(); // Cover:off", "Второй();", "КонецПроцедуры");

        assertEquals(List.of("OFF Cover:off 3..3"), ranges(markers));
        assertEquals(List.of(), markers.getWarnings());
    }

    @Test
    void incompleteIfDoesNotExcludeRecoveredBodyWithoutWarning() {
        CoverageMarkers markers = parse("Процедура Пример()", "Если Готово Тогда // Cover:off",
                "Первый();", "Второй();", "КонецПроцедуры");

        assertEquals(List.of("OFF Cover:off 2..2"), ranges(markers));
        assertTrue(markers.getWarnings().stream().anyMatch(warning -> warning.contains("incomplete")),
                markers.getWarnings().toString());
    }

    @Test
    void incompleteLoopAndTryBlocksKeepInlineMarkersLocal() {
        for (String header : List.of("Пока Готово Цикл", "Для Сч = 1 По 2 Цикл",
                "Для Каждого Элемент Из Массив Цикл", "Попытка")) {
            String body = header.equals("Попытка") ? "Шаг();\nИсключение\nВосстановить();" : "Шаг();";
            CoverageMarkers markers = parse("Процедура Пример()", header + " // Cover:auto",
                    body, "КонецПроцедуры");

            assertEquals(List.of("AUTO Cover:auto 2..2"), ranges(markers), header);
            assertTrue(markers.getWarnings().stream().anyMatch(warning -> warning.contains("incomplete")),
                    header + ": " + markers.getWarnings());
        }
    }

    @Test
    void inlineMarkerOnThenCoversMultilineIfButNotWhenItFollowsABodyStatement() {
        CoverageMarkers markers = parse("Процедура А()",
                "Если Готово", "И Проверено Тогда // Cover:off", "Б();", "КонецЕсли;",
                "Если Готово", "Тогда В(); // Cover:off", "Г();", "КонецЕсли;", "КонецПроцедуры");

        assertEquals(List.of("OFF Cover:off 2..5", "OFF Cover:off 7..7"), ranges(markers));
    }

    @Test
    void warnsThatUnclosedAutoRemainsOptional() {
        CoverageMarkers markers = parse("Процедура А()", "// Cover:auto", "Б();", "КонецПроцедуры");
        assertEquals(List.of("2: Cover:auto is not closed, lines are optional up to end of module"),
                markers.getWarnings());
    }

    @Test
    void inlineMarkerOnTryExcludesWholeTry() {
        CoverageMarkers markers = parse("Процедура П()", "\tПопытка // Cover:off", "\t\tБ();",
                "\tИсключение", "\t\tВ();", "\tКонецПопытки;", "\tД();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 2..6"), ranges(markers));
    }

    @Test
    void inlineMarkerOnLoopsExcludesWholeLoop() {
        assertEquals(List.of("OFF Cover:off 2..4"), ranges(parse("Процедура П()",
                "\tПока А Цикл // Cover:off", "\t\tБ();", "\tКонецЦикла;", "\tД();", "КонецПроцедуры")));
        assertEquals(List.of("OFF Cover:off 2..4"), ranges(parse("Процедура П()",
                "\tДля Сч = 1 По 2 Цикл // Cover:off", "\t\tБ();", "\tКонецЦикла;", "\tД();", "КонецПроцедуры")));
        assertEquals(List.of("OFF Cover:off 2..4"), ranges(parse("Процедура П()",
                "\tДля Каждого Эл Из Массив Цикл // Cover:off", "\t\tБ();", "\tКонецЦикла;", "\tД();",
                "КонецПроцедуры")));
    }

    @Test
    void inlineMarkerOnLoopKeywordCoversMultilineLoop() {
        for (String header : List.of("Пока Готово", "Для Сч = 1 По 2", "Для Каждого Эл Из Массив")) {
            assertEquals(List.of("OFF Cover:off 2..5"), ranges(parse("Процедура П()",
                    header, "Цикл // Cover:off", "Б();", "КонецЦикла;", "КонецПроцедуры")), header);
        }
        assertEquals(List.of("AUTO Cover:auto 2..5"), ranges(parse("Процедура П()",
                "Пока Готово", "Цикл // Cover:auto", "Б();", "КонецЦикла;", "КонецПроцедуры")));
    }

    @Test
    void inlineAutoAfterLoopBodyStatementRemainsSingleLine() {
        assertEquals(List.of("AUTO Cover:auto 3..3"), ranges(parse("Процедура П()",
                "Пока Готово", "Цикл Б(); // Cover:auto", "В();", "КонецЦикла;", "КонецПроцедуры")));
    }

    @Test
    void inlineMarkerOnRegionExcludesRegionWithNestedRegions() {
        CoverageMarkers markers = parse("#Область Внешняя // Cover:off", "#Область Внутренняя", "Процедура А()",
                "\tБ();", "КонецПроцедуры", "#КонецОбласти", "#КонецОбласти", "", "Процедура В()", "\tГ();",
                "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 1..7"), ranges(markers));
    }

    @Test
    void inlineMarkerTakesLongestBlockStartingOnLine() {
        CoverageMarkers markers = parse("Процедура П()", "\tЕсли А Тогда Если Б Тогда // Cover:off",
                "\t\tВ();", "\tКонецЕсли;", "\tКонецЕсли;", "\tД();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 2..5"), ranges(markers));
    }

    @Test
    void inlineOnClosesOpenRange() {
        CoverageMarkers markers = parse("Процедура А()", "\t// Cover:off", "\tБ(); // Cover:on", "\tВ();",
                "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:off 2..3"), ranges(markers));
        assertEquals(List.of(), markers.getWarnings());
    }

    @Test
    void brokenModuleDoesNotBreakInlineMarker() {
        CoverageMarkers markers = assertDoesNotThrow(() -> parse(
                "Процедура А(", "\tБ(); // Cover:off", "\tЕсли Тогда", "КонецПроцедуры"));
        assertEquals(List.of("OFF Cover:off 2..2"), ranges(markers));
    }

    @Test
    void fileOffInHeaderExcludesWholeModule() {
        CoverageMarkers markers = parse("// Cover:file-off — генерированный код", "Процедура А()", "\tБ();",
                "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:file-off 1..5"), ranges(markers));
        assertEquals("генерированный код", markers.getMarkers().get(0).getReason());
        assertEquals(List.of(), markers.getWarnings());
    }

    @Test
    void fileOffWorksAfterByteOrderMark() {
        CoverageMarkers markers = CoverageMarkers.parse(new BSLTokenizer(
                "\uFEFF// Cover:file-off\nПроцедура А()\n\tБ();\nКонецПроцедуры\n"));
        assertEquals(List.of("OFF Cover:file-off 1..5"), ranges(markers));
    }

    @Test
    void fileOffAfterCodeIsIgnoredWithWarning() {
        CoverageMarkers markers = parse("#Если Сервер Тогда", "// Cover:file-off", "Процедура А()", "\tБ();",
                "КонецПроцедуры", "#КонецЕсли");
        assertEquals(List.of(), ranges(markers));
        assertEquals(List.of("2: Cover:file-off ignored, it must precede module code"), markers.getWarnings());
    }

    @Test
    void fileOffHasRussianSynonym() {
        CoverageMarkers markers = parse("// Cover:файл-выкл", "Процедура А()", "\tБ();", "КонецПроцедуры");
        assertEquals(List.of("OFF Cover:файл-выкл 1..5"), ranges(markers));
    }

    @Test
    void markerMustNotBePartOfAnotherWord() {
        for (String prefix : List.of("Legacy", "_", "9", "Метка", "е\u0301")) {
            assertEquals(List.of(), ranges(parse("// " + prefix + "Cover:off", "Б();")), prefix);
        }
        assertEquals(List.of("OFF Cover:off 1..1"), ranges(parse("Б(); // (Cover:off)")));
    }

    @Test
    void unicodeCaseVariantsKeepTheirMatchedKind() {
        CoverageMarkers markers = parse("// Cover:FıLE-OFF", "Б();");
        assertEquals(List.of("OFF Cover:FıLE-OFF 1..3"), ranges(markers));
        assertEquals(true, markers.getMarkers().getFirst().isFileOff());
        assertEquals(List.of(), markers.getWarnings());
        assertEquals(List.of(), ranges(parse("// Cover:ВКЛ", "Б();")));
    }

    @Test
    void inlineMarkerOnAsyncKeywordCoversTheMethod() {
        for (String[] method : new String[][] {
                {"Асинх", "Процедура Пример()", "КонецПроцедуры"},
                {"Асинх", "Функция Пример()", "КонецФункции"},
                {"Async", "Procedure Sample()", "EndProcedure"},
                {"Async", "Function Sample()", "EndFunction"}}) {
            assertEquals(List.of("OFF Cover:off 2..5"), ranges(parse("&НаКлиенте",
                    method[0] + " // Cover:off", method[1], "Шаг();", method[2])));
        }
    }

    static CoverageMarkers parse(String... lines) {
        return CoverageMarkers.parse(new BSLTokenizer(String.join("\n", lines) + "\n"));
    }

    static List<String> ranges(CoverageMarkers markers) {
        return markers.getMarkers().stream()
                .map(marker -> marker.getKind() + " " + marker.getLabel() + " "
                        + marker.getStartLine() + ".." + marker.getEndLine())
                .collect(Collectors.toList());
    }
}
