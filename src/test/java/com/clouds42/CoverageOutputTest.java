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

import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoverageOutputTest {
    @TempDir Path directory;

    private boolean dump(OutputOptions.OutputFormat format) {
        MetadataOptions metadata = new MetadataOptions();
        new picocli.CommandLine(metadata).parseArgs("-P", directory.toString());
        OutputOptions output = new OutputOptions();
        output.setOutputFormat(format);
        return Utils.tryDumpCoverageFile(Map.of(directory.resolve("Module.bsl").toUri(),
                Map.of(BigDecimal.ONE, 1)), metadata, output);
    }

    @Test
    void stdoutReportsWriteFailuresInEveryFormat() {
        PrintStream original = System.out;
        try {
            for (var format : OutputOptions.OutputFormat.values()) {
                System.setOut(new PrintStream(new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        throw new IOException("output unavailable");
                    }
                }));
                assertFalse(dump(format), format.toString());
            }
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void everyFormatLeavesSharedStdoutOpenForSubsequentReports() {
        PrintStream original = System.out;
        try {
            for (var format : OutputOptions.OutputFormat.values()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                System.setOut(new PrintStream(bytes, false, StandardCharsets.UTF_8));
                assertTrue(dump(format));
                int firstSize = bytes.size();
                assertTrue(firstSize > 0);
                assertTrue(dump(format));
                assertTrue(bytes.size() > firstSize, format.toString());
                assertFalse(System.out.checkError());
            }
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void alreadyFailedStdoutIsRejectedBeforeWritingAndReplacementCanRecover() {
        PrintStream original = System.out;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            System.setOut(new PrintStream(bytes) {{ setError(); }});
            assertFalse(dump(OutputOptions.OutputFormat.GENERIC_COVERAGE));
            assertEquals(0, bytes.size());
            System.setOut(new PrintStream(bytes));
            assertTrue(dump(OutputOptions.OutputFormat.GENERIC_COVERAGE));
            assertTrue(bytes.size() > 0);
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void coberturaNestsLinesUnderTheClassLinesElement() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            assertTrue(dump(OutputOptions.OutputFormat.COBERTURA));
        } finally {
            System.setOut(original);
        }
        var document = Utils.newInputXmlBuilder().parse(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        assertEquals("1", xpath.evaluate("count(/coverage/packages/package/classes/class/lines/line)", document));
        assertEquals("0", xpath.evaluate("count(/coverage/packages/package/classes/class/line)", document));
    }
}
