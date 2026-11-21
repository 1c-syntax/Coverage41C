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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URI;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void rejectsNestedReportOutputsWithoutCreatingDirectories() throws Exception {
        Path coverage = tempDir.resolve("coverage.xml");
        Path report = tempDir.resolve("exclusions.json");
        assertOverlappingOutputsRejected(coverage, coverage.resolve("nested/deeper/report.json"));
        assertOverlappingOutputsRejected(report.resolve("nested/deeper/coverage.xml"), report);
        assertFalse(Files.exists(coverage));
        assertFalse(Files.exists(report));

        Path sibling = tempDir.resolve("coverage.xml-reports/nested/report.json");
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.OK, convert(stderr, "-o", coverage.toString(),
                "--exclusion-report", sibling.toString()), stderr.toString());
        assertTrue(Files.isRegularFile(coverage));
        assertTrue(Files.isRegularFile(sibling));
    }

    @Test
    void rejectsNestedReportOutputsThroughDirectoryAliases() throws Exception {
        Path actual = Files.createDirectory(tempDir.resolve("actual"));
        Path alias = tempDir.resolve("alias");
        Path coverage = actual.resolve("coverage.xml");
        Path report = actual.resolve("exclusions.json");
        Path dangling = tempDir.resolve("pending-coverage");
        try {
            Files.createSymbolicLink(alias, actual);
            Files.createSymbolicLink(dangling, coverage);
        } catch (UnsupportedOperationException | IOException error) {
            assumeTrue(false, () -> "Cannot create a symbolic link: " + error.getMessage());
        }

        assertOverlappingOutputsRejected(coverage, alias.resolve("coverage.xml/nested/report.json"));
        assertOverlappingOutputsRejected(alias.resolve("exclusions.json/nested/coverage.xml"), report);
        assertOverlappingOutputsRejected(coverage, dangling.resolve("nested/report.json"));
        assertFalse(Files.exists(coverage));
        assertFalse(Files.exists(report));
        assertTrue(Files.isSymbolicLink(dangling));
    }

    private static void assertOverlappingOutputsRejected(Path coverage, Path report) {
        for (int attempt = 0; attempt < 2; attempt++) {
            StringWriter stderr = new StringWriter();
            assertEquals(CommandLine.ExitCode.SOFTWARE, convert(stderr, "-o", coverage.toString(),
                    "--exclusion-report", report.toString()), stderr.toString());
            assertTrue(stderr.toString().contains("output paths overlap"), stderr.toString());
            assertFalse(Files.exists(coverage));
            assertFalse(Files.exists(report));
        }
    }

    @Test
    void rejectsTerminalDirectoryComponentsBeforeCreatingOutput() throws Exception {
        Path absent = tempDir.resolve("absent");
        Path coverage = tempDir.resolve("coverage.xml");
        Path companion = tempDir.resolve("companion/report.json");
        for (Path invalid : List.of(absent.resolve("."), absent.resolve(".."))) {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertFileDestinationRejected("-o", invalid.toString());
                assertFileDestinationRejected("-o", invalid.toString(), "--exclusion-report", companion.toString());
                assertFileDestinationRejected("-o", coverage.toString(), "--exclusion-report", invalid.toString());
                assertFalse(Files.exists(absent));
                assertFalse(Files.exists(coverage));
                assertFalse(Files.exists(companion.getParent()));
            }
        }
    }

    @Test
    void rejectsFileAliasesTargetingTerminalDirectoryComponents() throws Exception {
        Path parent = tempDir.resolve("pending");
        Path coverage = tempDir.resolve("coverage.xml");
        for (String component : List.of(".", "..")) {
            Path alias = tempDir.resolve(component.equals(".") ? "dot-output" : "parent-output");
            try {
                Files.createSymbolicLink(alias, parent.resolve(component));
            } catch (UnsupportedOperationException | IOException error) {
                assumeTrue(false, () -> "Cannot create a symbolic link: " + error.getMessage());
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                assertFileDestinationRejected("-o", alias.toString());
                assertFileDestinationRejected("-o", coverage.toString(), "--exclusion-report", alias.toString());
                assertFalse(Files.exists(parent));
                assertFalse(Files.exists(coverage));
                assertTrue(Files.isSymbolicLink(alias));
            }
        }

        Files.createDirectory(parent);
        Path valid = tempDir.resolve("ordinary-report-alias");
        Files.createSymbolicLink(valid, parent.resolve("./report.json"));
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.OK, convert(stderr, "-o", coverage.toString(),
                "--exclusion-report", valid.toString()), stderr.toString());
        assertTrue(Files.isRegularFile(parent.resolve("report.json")));
        assertTrue(Files.isSymbolicLink(valid));
    }

    @Test
    void permitsInteriorDotComponentsInFileOutputsOnRetry() throws Exception {
        Path parent = tempDir.resolve("new");
        assertFileDestinationRejected("-o", parent.resolve(".").toString());
        assertFalse(Files.exists(parent));

        Path coverage = parent.resolve("./coverage.xml");
        Path report = parent.resolve("./report.json");
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.OK, convert(stderr, "-o", coverage.toString(),
                "--exclusion-report", report.toString()), stderr.toString());
        assertTrue(Files.isRegularFile(parent.resolve("coverage.xml")));
        assertTrue(Files.isRegularFile(parent.resolve("report.json")));
        assertFileDestinationRejected("-o", parent.resolve(".").toString(), "--exclusion-report",
                tempDir.resolve("another/report.json").toString());
        assertFalse(Files.exists(tempDir.resolve("another")));
    }

    private static void assertFileDestinationRejected(String... options) {
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.SOFTWARE, convert(stderr, options), stderr.toString());
        assertTrue(stderr.toString().contains("must name a file"), stderr.toString());
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

    @Test
    void coberturaSerializesUncDriveAndUnixPaths() throws Exception {
        String drivePrefix = File.separatorChar == '\\' ? "" : "/";
        Path outsideModule = Path.of(drivePrefix + "E:/compat/src/Other # €.bsl");
        Map<URI, String> projects = Map.of(
                URI.create("file://nas/build/project%20root/%E2%82%AC/"), "//nas/build/project root/€/",
                URI.create("file:///C:/"), drivePrefix + "C:/",
                URI.create("file:///D:/build%20root/"), drivePrefix + "D:/build root/",
                URI.create("file:///var/tmp/project%20%23%20A%26B/"), "/var/tmp/project # A&B/");
        for (Map.Entry<URI, String> project : projects.entrySet()) {
            URI projectUri = project.getKey();
            URI module = projectUri.resolve("subdir/Module%20%23%20%E2%82%AC.bsl");
            URI anotherServer = URI.create("file://mirror/export/Other%20Module.bsl");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Utils.writeCobertura(Map.of(module, Map.of(BigDecimal.ONE, 1),
                    outsideModule.toUri(), Map.of(BigDecimal.ONE, 1),
                    anotherServer, Map.of(BigDecimal.ONE, 1)), projectUri, output);

            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(output.toByteArray()));
            var xpath = XPathFactory.newInstance().newXPath();
            assertEquals(project.getValue(), xpath.evaluate("/coverage/sources/source", document), projectUri.toString());
            assertEquals(1, count(document, "/coverage/packages/package/classes/class"
                    + "[@filename='subdir/Module # €.bsl']"));
            assertEquals(1, count(document, "/coverage/packages/package/classes/class"
                    + "[@filename='" + outsideModule.toString().replace(File.separatorChar, '/') + "']"));
            assertEquals(1, count(document, "/coverage/packages/package/classes/class"
                    + "[@filename='//mirror/export/Other Module.bsl']"));
        }
    }

    private void assertCoberturaSource(Path project) throws Exception {
        Path output = tempDir.resolve("cobertura.xml");
        MetadataOptions metadata = CommandLine.populateCommand(new MetadataOptions(), "-P", project.toString());
        OutputOptions options = CommandLine.populateCommand(new OutputOptions(), "-f", "COBERTURA", "-o", output.toString());
        Utils.dumpCoverageFile(Map.of(project.resolve("Module.bsl").toUri(), Map.of(BigDecimal.ONE, 1)), metadata, options);

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile());
        String source = XPathFactory.newInstance().newXPath().evaluate("/coverage/sources/source", document);
        String expected = project.toAbsolutePath().toString().replace('\\', '/');
        assertEquals(expected.endsWith("/") ? expected : expected + "/", source);
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

    @Test
    void borrowedCoverageSinkTracksCurrentWriteAndFlushForEveryFormat() throws IOException {
        var data = java.util.Map.of(Path.of("sample.bsl").toAbsolutePath().toUri(),
                java.util.Map.of(java.math.BigDecimal.valueOf(2), 1));
        var metadata = CommandLine.populateCommand(new MetadataOptions(), "-P", ".");
        for (OutputOptions.OutputFormat format : OutputOptions.OutputFormat.values()) {
            var options = CommandLine.populateCommand(new OutputOptions(), "-f", format.name());
            class Sink extends OutputStream {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                boolean failWrite;
                boolean failFlush;
                boolean closed;

                @Override
                public void write(int value) throws IOException {
                    if (failWrite) throw new IOException("test write failure");
                    bytes.write(value);
                }

                @Override
                public void flush() throws IOException {
                    if (failFlush) throw new IOException("test flush failure");
                }

                @Override
                public void close() {
                    closed = true;
                }
            }
            Sink sink = new Sink();
            sink.failWrite = true;
            assertThrows(IOException.class, () -> Utils.dumpCoverageFile(data, metadata, options, sink));
            sink.failWrite = false;
            Utils.dumpCoverageFile(data, metadata, options, sink);
            assertTrue(sink.bytes.size() > 0, format.name());
            sink.failFlush = true;
            assertThrows(IOException.class, () -> Utils.dumpCoverageFile(data, metadata, options, sink));
            sink.failFlush = false;
            sink.bytes.reset();
            Utils.dumpCoverageFile(data, metadata, options, sink);
            assertTrue(sink.bytes.toString(StandardCharsets.UTF_8).contains("sample.bsl"), format.name());
            assertFalse(sink.closed, format.name());
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
