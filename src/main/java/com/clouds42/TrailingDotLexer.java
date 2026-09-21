/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2024
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
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.WritableToken;

/**
 * Лексер, в котором точка в конце строки ({@code Объект.<перевод строки>Свойство}) - обычное обращение
 * к свойству или методу, как и для платформы.
 * <p>
 * bsl-parser с версии 0.34 выделяет такую точку в DOT_TRAILING (незавершённое обращение для редактора),
 * из-за чего следующий идентификатор становится отдельным оператором, а остаток модуля часто разбирается с ошибками.
 * Покрытие снимается только с компилирующегося кода, поэтому незавершённых обращений в нём не бывает
 */
public class TrailingDotLexer extends BSLLexer {

    private boolean afterTrailingDot;

    public TrailingDotLexer() {
        super(CharStreams.fromString(""));
    }

    @Override
    public Token nextToken() {
        Token token = super.nextToken();
        if (token.getType() == DOT_TRAILING) {
            ((WritableToken) token).setType(DOT);
            afterTrailingDot = true;
        } else if (afterTrailingDot && token.getChannel() == Token.DEFAULT_CHANNEL) {
            afterTrailingDot = false;
            // как в DOT_MODE лексера: после точки ключевое слово - имя свойства или метода
            if (isIdentifierLike(token.getText())) {
                ((WritableToken) token).setType(IDENTIFIER);
            }
        }
        return token;
    }

    @Override
    public void reset() {
        super.reset();
        afterTrailingDot = false;
    }

    private static boolean isIdentifierLike(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int first = text.codePointAt(0);
        return (Character.isLetter(first) || first == '_')
                && text.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || c == '_');
    }
}
