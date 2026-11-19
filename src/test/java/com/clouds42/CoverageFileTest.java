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
import org.w3c.dom.Document;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Запись файла покрытия: форматы, ошибки записи, вывод в stdout.
class CoverageFileTest {

    private static final String INTERNAL_XML = new File("src/test/resources/coverage/internal.xml").getAbsolutePath();

    @TempDir
    Path tempDir;

    @Test
    void failsWhenCoverageFileCannotBeWritten() {
        Path output = tempDir.resolve("missing").resolve("coverage.out");
        for (OutputOptions.OutputFormat format : OutputOptions.OutputFormat.values()) {
            StringWriter stderr = new StringWriter();

            int exitCode = convert(stderr, "-f", format.name(), "-o", output.toString());

            assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode, format.name());
            assertTrue(stderr.toString().contains(output.toString()), format + ": " + stderr);
        }
    }

    @Test
    void coberturaKeepsLinesInsideLinesElement() throws Exception {
        Path output = tempDir.resolve("cobertura.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(stderr, "-f", "COBERTURA", "-o", output.toString()),
                stderr.toString());

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile());
        assertTrue(count(document, "/coverage/packages/package/classes/class/lines/line") > 0);
        assertEquals(0, count(document, "/coverage/packages/package/classes/class/line"));
    }

    @Test
    void rejectsConversionWithoutSourcesBeforeCreatingOutput() {
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();
        CommandLine command = Coverage41C.getCommandLine();
        command.setErr(new PrintWriter(stderr, true));

        assertEquals(CommandLine.ExitCode.SOFTWARE, command.execute(
                PipeMessages.CONVERT_COMMAND, "-c", INTERNAL_XML, "-o", output.toString()), stderr.toString());
        assertTrue(stderr.toString().contains("Conversion requires source files"), stderr.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsConfigurationDescriptorPassedAsSingleSource() {
        Path descriptor = Path.of("src/test/resources/configuration/Configuration.xml").toAbsolutePath();
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();
        CommandLine command = Coverage41C.getCommandLine();
        command.setErr(new PrintWriter(stderr, true));

        assertEquals(CommandLine.ExitCode.SOFTWARE, command.execute(PipeMessages.CONVERT_COMMAND,
                "-c", INTERNAL_XML, "-s", descriptor.toString(), "-o", output.toString()), stderr.toString());
        assertTrue(stderr.toString().contains("pass the configuration directory to -s"), stderr.toString());
        assertFalse(Files.exists(output));
    }

    @Test
    void coberturaPlacesRootProjectPathInSourceElement() throws Exception {
        assertCoberturaSource(tempDir.toAbsolutePath().getRoot());
    }

    @Test
    void coberturaKeepsNestedProjectPath() throws Exception {
        assertCoberturaSource(Files.createDirectories(tempDir.resolve("project/nested")));
    }

    private void assertCoberturaSource(Path project) throws Exception {
        Path output = tempDir.resolve("cobertura.xml");
        MetadataOptions metadata = CommandLine.populateCommand(new MetadataOptions(), "-P", project.toString());
        OutputOptions options = CommandLine.populateCommand(new OutputOptions(), "-f", "COBERTURA", "-o", output.toString());
        Utils.dumpCoverageFile(Map.of(project.resolve("Module.bsl").toUri(), Map.of(BigDecimal.ONE, 1)), metadata, options);

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile());
        String source = XPathFactory.newInstance().newXPath().evaluate("/coverage/sources/source", document);
        assertEquals(project.toUri().getPath(), source);
    }

    @Test
    void lcovUsesAsciiDigitsWithArabicLocale() throws Exception {
        Path output = tempDir.resolve("coverage.lcov");
        StringWriter stderr = new StringWriter();
        Locale original = Locale.getDefault();
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertEquals(CommandLine.ExitCode.OK, convert(stderr, "-f", "LCOV", "-o", output.toString()),
                    stderr.toString());
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
        }

        List<String> records = Files.readAllLines(output);
        assertTrue(records.stream().anyMatch(line -> line.matches("DA:[0-9]+,1")), records.toString());
        assertTrue(records.stream().anyMatch(line -> line.startsWith("LH:")));
        assertTrue(records.stream().anyMatch(line -> line.startsWith("LF:")));
        assertTrue(records.stream().filter(line -> line.startsWith("DA:") || line.startsWith("LH:") || line.startsWith("LF:"))
                .allMatch(line -> line.matches("DA:[0-9]+,[0-9]+|L[HF]:[0-9]+")), records.toString());
    }

    @Test
    void writesToStandardOutputAndKeepsItOpen() {
        for (OutputOptions.OutputFormat format : OutputOptions.OutputFormat.values()) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            PrintStream capture = new PrintStream(stdout, true, StandardCharsets.UTF_8);
            int exitCode;
            try {
                System.setOut(capture);
                exitCode = convert(new StringWriter(), "-f", format.name());
                System.out.print("still open");
            } finally {
                System.setOut(originalOut);
            }

            assertEquals(CommandLine.ExitCode.OK, exitCode, format.name());
            assertFalse(capture.checkError(), format.name());
            String output = stdout.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("ПростойОбщийМодуль"), format.name());
            assertTrue(output.endsWith("still open"), format.name());
        }
    }

    @Test
    void failsWhenStandardOutputCannotBeWritten() {
        for (OutputOptions.OutputFormat format : OutputOptions.OutputFormat.values()) {
            PrintStream originalOut = System.out;
            StringWriter stderr = new StringWriter();
            int exitCode;
            try (PrintStream failingOutput = new PrintStream(new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    throw new IOException("simulated write failure");
                }
            }, false, StandardCharsets.UTF_8)) {
                System.setOut(failingOutput);
                exitCode = convert(stderr, "-f", format.name());
            } finally {
                System.setOut(originalOut);
            }

            assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode, format.name());
            assertTrue(stderr.toString().contains("Cannot write coverage to standard output"), format + ": " + stderr);
        }
    }

    private static double count(Document document, String path) throws Exception {
        return (Double) XPathFactory.newInstance().newXPath()
                .evaluate("count(" + path + ")", document, XPathConstants.NUMBER);
    }

    private static int convert(StringWriter stderr, String... options) {
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.CONVERT_COMMAND,
                "-P", new File(".").getAbsolutePath(),
                "-s", "src/test/resources/configuration",
                "-c", INTERNAL_XML));
        arguments.addAll(Arrays.asList(options));
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments.toArray(new String[0]));
    }
}
