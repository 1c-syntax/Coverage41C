package com.clouds42;

import com.github._1c_syntax.bsl.parser.BSLTokenizer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LinesToCoverTest {

    @Test
    void LineToCoverTest() throws IOException {

        File file = new File("src/test/resources/linestocover/test.bsl");
        BSLTokenizer tokenizer = new BSLTokenizer(Files.readString(file.toPath()));

        int[] linesToCover = LinesToCover.getLines(tokenizer.getAst());

        int[] expected = new int[]{11, 13, 15, 19, 26, 28, 37, 38, 39, 40, 43};
        assertArrayEquals(expected, linesToCover);
    }

}
