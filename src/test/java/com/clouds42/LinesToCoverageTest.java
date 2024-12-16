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
    void IfTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/if.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        var expected = new int[]{3, 8, 10, 16, 17, 19, 21, 22, 23};
        assertThat(linesToCover, equalTo(expected));
    }

    @Test
    void AssigmentTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/assigment.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // Реальный замер
        // 5, 11, 18, 22, 26, 32, 38, 44, 46, 52, 58, 63, 68, 70, 79, 84, 92, 102, 106, 110, 114, 117, 121, 125, 127, 127, 128, 128
        var expected = new int[]{5, 11, 18, 22, 26, 32, 38, 44, 46, 52, 58, 63, 68, 70, 79, 84, 92, 102, 106, 110, 114,
                117, 121, 125, 127, 128};
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

    @Test
    void otherTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/other.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // Реальный замер
        // 1, 4, 10, 15, 18, 19, 21, 34, 48, 50, 53, 55, 59, 61, 62, 64, 65, 70, 72, 76, 80, 84, 87, 91, 92, 94, 94,
        // 95, 95, 96, 96
        var expected = new int[]{4, 10, 15, 18, 19, 21, 34, 48, 50, 53, 55, 59, 61, 62, 64, 65,
                70, 72, 76, 80, 84, 87, 91, 92, 94, 95, 96};

        assertThat(linesToCover, equalTo(expected));
    }

    @Test
    void NotCoveredTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/notcovered.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // Реальный замер
        //6, 7, 9, 13, 14, 16, 19, 24, 26, 28, 30, 32, 34, 37, 42, 44, 47, 49, 49, 50, 50, 51, 51, 52, 52, 53, 53
        var expected = new int[]{6, 7,
                8, // Лишняя
                9, 13, 14, 16,
                17, // Лишняя
                19, 21, 22, 23};

        assertThat(linesToCover, equalTo(expected));
    }

    @Test
    void FilterTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/filtered.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // Реальный замер
        // 3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 23, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 62, 63, 65, 70, 73, 75, 77, 79, 81, 83, 85, 87, 89, 91, 93, 95, 97, 99, 101, 103, 105, 107, 109, 111, 113, 115, 117, 119, 122, 126, 128, 130, 132, 134, 135, 136, 137, 139, 143, 146, 149, 152, 155, 155, 156, 156, 157, 157, 158, 158
        var expected = new int[]{3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 23, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 62, 63, 65, 70, 73, 75, 77, 79, 81, 83, 85, 87, 89, 91, 93, 95, 97, 99, 101, 103, 105, 107, 109, 111, 113, 115, 117, 119, 122, 126, 128, 130, 132, 134, 135, 136, 137, 139, 143, 146, 149, 152, 155, 156, 157, 158};

        assertThat(linesToCover, equalTo(expected));
    }

    @Test
    void opcodesTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/opcode.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // Реальный замер
        // 10, 8, 3, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 111, 113, 115, 117, 119, 121, 123, 125, 127, 129, 131, 133, 135, 137, 139, 141, 143, 145, 147, 149, 152, 153, 155, 156, 159, 161
        var expected = new int[]{3, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42, 44, 46, 48, 50, 52, 54, 56, 58, 60, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84, 86, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 111, 113, 115, 117, 119, 121, 123, 125, 127, 129, 131, 133, 135, 137, 139, 141, 143, 145, 147, 149, 152, 153, 155, 156, 159, 161};

        assertThat(linesToCover, equalTo(expected));
    }

}
