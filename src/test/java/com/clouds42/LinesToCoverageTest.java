package com.clouds42;

import com.github._1c_syntax.bsl.parser.BSLTokenizer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LinesToCoverageTest {

    @Test
    void ToCoverTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/tocover.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        // Реальный Замер
        // 13, 17, 19, 22, 23, 24, 27, 29, 30, 31, 33, 34, 35, 38, 39, 41, 42, 45, 46, 50, 51, 52, 54,
        // 56, 58, 60, 66, 67, 69, 70, 71, 73, 74, 75, 78, 79, 80, 81, 82, 84, 89, 93, 94, 98, 98
        var expected = new int[]{13, 15, 19, 22, 23, 24, 27 ,29, 30, 33, 34, 38, 39, 41, 45, 50, 51,
                52, 54, 58, 66, 67, 69, 70, 73, 74, 75, 78, 79, 80, 81, 93, 98};
        assertArrayEquals(expected, linesToCover);
    }

    @Test
    void ParseErrorTest() throws IOException {

        var file = new File("src/test/resources/linestocoverage/error.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCoverage.getLines(tokenizer.getAst());

        var expected = new int[]{4};
        assertArrayEquals(expected, linesToCover);
    }

}
