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

import com.github._1c_syntax.bsl.parser.BSLParser;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.Locale;
import java.util.Set;

public class GlobalCallsFilter {

    private static final Set<String> filterMethodsName = Set.of(
            "eval",
            "вычислить",
            "boolean",
            "булево",
            "number",
            "число",
            "string",
            "строка",
            "date",
            "дата",
            "type",
            "тип",
            "typeof",
            "типзнч",
            "strlen",
            "стрдлина",
            "triml",
            "сокрл",
            "trimr",
            "сокрп",
            "trimall",
            "сокрлп",
            "left",
            "лев",
            "right",
            "прав",
            "mid",
            "сред",
            "strpos",
            "upper",
            "врег",
            "lower",
            "нрег",
            "title",
            "трег",
            "char",
            "символ",
            "charcode",
            "кодсимвола",
            "isblankstring",
            "пустаястрока",
            "strreplace",
            "стрзаменить",
            "strgetline",
            "стрполучитьстроку",
            "strlinecount",
            "стрчислострок",
            "stroccurrencecount",
            "стрчисловхождений",
            "year",
            "год",
            "month",
            "месяц",
            "day",
            "день",
            "hour",
            "час",
            "minute",
            "минута",
            "second",
            "секунда",
            "begofyear",
            "началогода",
            "begofmonth",
            "началомесяца",
            "begofweek",
            "началонедели",
            "begofday",
            "началодня",
            "begofhour",
            "началочаса",
            "begofminute",
            "началоминуты",
            "begofquarter",
            "началоквартала",
            "endofyear",
            "конецгода",
            "endofmonth",
            "конецмесяца",
            "endofweek",
            "конецнедели",
            "endofday",
            "конецдня",
            "endofhour",
            "конецчаса",
            "endofminute",
            "конецминуты",
            "endofquarter",
            "конецквартала",
            "weekofyear",
            "неделягода",
            "dayofyear",
            "деньгода",
            "weekday",
            "деньнедели",
            "addmonth",
            "добавитьмесяц",
            "currentdate",
            "текущаядата",
            "int",
            "цел",
            "round",
            "окр",
            "log",
            "log10",
            "sin",
            "cos",
            "tan",
            "asin",
            "acos",
            "atan",
            "exp",
            "pow",
            "sqrt",
            "min",
            "мин",
            "max",
            "макс",
            "format",
            "формат",
            "errorinfo",
            "информацияобошибке",
            "errordescription",
            "описаниеошибки"

    );

    public static boolean filterByName(ParseTree parseTree) {
        if (!(parseTree instanceof BSLParser.GlobalMethodCallContext)) {
            return true;
        }

        return !filterMethodsName.contains(
                ((BSLParser.GlobalMethodCallContext) parseTree).methodName().getText().toLowerCase(Locale.ROOT)
        );

    }
}
