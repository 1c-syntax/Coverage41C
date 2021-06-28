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

import com.github._1c_syntax.bsl.parser.BSLTokenizer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

class LinesToCoverageTest {

    @Test
    void ToCoverTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/tocover.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // Реальный Замер
        // 13, 17, 19, 22, 23, 24, 27, 29, 30, 31, 33, 34, 35, 38, 39, 41, 42, 45, 46, 50, 51, 52, 54,
        // 56, 58, 60, 66, 67, 69, 70, 71, 73, 74, 75, 78, 79, 80, 81, 82, 84, 92, 93, 94, 98, 103, 105,
        // 112, 112
        var expected = new int[]{13, 17, 19, 22, 23, 24, 27, 29, 30, 31, 33, 34, 35, 38, 39, 41, 42, 45, 46, 50, 51, 52, 54,
                56, 58, 60, 66, 67, 69, 70, 71, 73, 74, 75, 76, 78, 79, 80, 81, 82, 84, 92, 93, 94, 98, 103, 105,
                112};
        assertThat(linesToCover, equalTo(expected));
    }

    @Test
    void ParseErrorTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/error.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        var expected = new int[]{4, 6, 8};
        assertThat(linesToCover, equalTo(expected));
    }

    @Test
    void SimpleTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/simple.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // 3 , 8 , 15, 18, 19, 20, 21, 23, 24, 29, 30, 31, 32, 33, 34, 35, 36, 37, 41, 42, 48,
        // 51, 53, 57, 63, 66, 67, 68, 69, 70, 72, 72
        var expected = new int[]{3, 8, 15, 18, 19, 20, 21, 23, 24, 29, 30, 31, 32, 33, 34, 35, 36, 37, 41, 42, 48,
                50, 53, 57, 60, 63, 66, 67, 68, 69, 70, 72};
        assertThat(linesToCover, equalTo(expected));
    }

    @Test
    void AssigmentTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/assigment.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // 4	, 10, 17, 21, 25, 31, 37, 43, 45, 51, 57, 62, 66, 70, 74, 78, 80, 80, 81, 81
        var expected = new int[]{4, 10, 17, 21, 25, 31, 37, 43, 45, 51, 57, 62, 66, 70, 74, 78, 80, 81};
        assertThat(linesToCover, equalTo(expected));
    }

    @Test
    void DoTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/do.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // Реальный замер
        //  4, 5, 6, 7, 8, 12, 16, 22, 23, 25, 30, 31, 32, 33, 34, 35, 36, 37, 39, 43, 44, 53, 54,
        //  56, 61, 64, 65, 66, 67, 68, 70, 75, 76, 77, 78, 79, 80, 82, 85, 86, 89, 95, 96, 97, 99, 102, 103,
        //  106, 107, 109, 109, 110, 110, 111, 111, 112, 112
        var expected = new int[]{4, 5, 6, 7, 8, 12, 16, 22, 23, 25, 30, 31, 32, 33, 34, 35, 36, 37, 39, 43, 44, 53, 54,
                56, 61, 64, 65, 66, 67, 68, 70, 75, 76, 77, 78, 79, 80, 82, 85, 86, 89, 95, 96, 97, 99, 102, 103, 106,
                107, 109, 110, 111, 112};

        assertThat(linesToCover, equalTo(expected));
    }
}
