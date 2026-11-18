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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclusionConfigTest {

    private static final File RAW_COVERAGE = new File("src/test/resources/coverage/internal.xml");
    private static final File CONFIGURATION = new File("src/test/resources/configuration");

    @TempDir
    Path tempDir;

    private int outputCounter;

    @Test
    void readsEveryJsonFileOfConfigDirectory() throws Exception {
        Path configDir = Files.createDirectory(tempDir.resolve("config"));
        write(configDir.resolve("a.json"), "{'includeSubsystems':['ПодчиненнаяПодсистема']}");
        write(configDir.resolve("b.json"), "{'includeSubsystems':['ПочиненнаяСистема2']}");
        write(configDir.resolve("readme.md"), "# not a config");
        Files.createDirectory(configDir.resolve("nested"));
        write(configDir.resolve("nested/broken.json"), "{'includeSubsystems':['НесуществующаяПодсистема']}");

        assertEquals(coveredFiles(new File("src/test/resources/config/coverage-child-subsystems.json")),
                coveredFiles(configDir.toFile()));
    }

    @Test
    void reportsMissingConfigPath() {
        StringWriter stderr = new StringWriter();

        int exitCode = convert(tempDir.resolve("missing").toFile(), tempDir.resolve("out.xml"), stderr);

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Coverage configuration path does not exist"), stderr.toString());
    }

    @Test
    void reportsConfigDirectoryWithoutJsonFiles() throws Exception {
        Path configDir = Files.createDirectory(tempDir.resolve("empty"));
        StringWriter stderr = new StringWriter();

        int exitCode = convert(configDir.toFile(), tempDir.resolve("out.xml"), stderr);

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("contains no *.json files"), stderr.toString());
    }

    @Test
    void excludesObjectWithAllItsModules() throws Exception {
        Set<String> baseline = coveredFiles(null);

        Set<String> excluded = coveredFiles(config("team.json", "{'exclude':{'objects':['Catalog.Справочник1']}}"));

        assertTrue(baseline.stream().anyMatch(path -> path.contains("/Catalogs/Справочник1/")));
        assertEquals(without(baseline, path -> path.contains("/Catalogs/Справочник1/")), excluded);
    }

    @Test
    void excludesFormsByRussianMask() throws Exception {
        Set<String> baseline = coveredFiles(null);

        Set<String> excluded = coveredFiles(
                config("team.json", "{'exclude':{'objects':['Справочник.Справочник1.Форма.*']}}"));

        assertTrue(baseline.stream().anyMatch(path -> path.contains("/Catalogs/Справочник1/Forms/")));
        assertEquals(without(baseline, path -> path.contains("/Catalogs/Справочник1/Forms/")), excluded);
    }

    @Test
    void excludesModulesByPathGlob() throws Exception {
        Set<String> baseline = coveredFiles(null);

        Set<String> excluded = coveredFiles(config("team.json", "{'exclude':{'paths':['**/Forms/**/Module.bsl']}}"));

        Predicate<String> form = path -> path.contains("/Forms/") && path.endsWith("/Module.bsl");
        assertTrue(baseline.stream().anyMatch(form));
        assertEquals(without(baseline, form), excluded);
    }

    @Test
    void excludedSubsystemWinsOverIncludedOne() throws Exception {
        Set<String> included = coveredFiles(config("include.json", "{'includeSubsystems':['ПерваяПодсистема']}"));

        Set<String> excluded = coveredFiles(config("team.json",
                "{'includeSubsystems':['ПерваяПодсистема'],'exclude':{'subsystems':['ПодчиненнаяПодсистема']}}"));

        Predicate<String> child = path -> path.contains("/Справочник1/") || path.contains("/Обработка1/");
        assertTrue(included.stream().anyMatch(child));
        assertTrue(included.stream().anyMatch(child.negate()));
        assertEquals(without(included, child), excluded);
    }

    @Test
    void excludesLinesByRegularExpression() throws Exception {
        String module = "src/test/resources/configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";
        Set<Integer> baseline = coveredLines(null, module);

        Set<Integer> excluded = coveredLines(
                config("team.json", "{'exclude':{'lines':['^\\\\s*НичегоНеДелать\\\\(\\\\);']}}"), module);

        assertTrue(baseline.containsAll(Set.of(3, 7, 12)), baseline.toString());
        assertEquals(without(baseline, Set.of(3, 7, 12)::contains), excluded);
    }

    @Test
    void readingOrderOfConfigFilesDoesNotMatter() throws Exception {
        String byObject = "{'exclude':{'objects':['Catalog.Справочник1']}}";
        String byPath = "{'exclude':{'paths':['**/Catalogs/**']}}";
        Path first = Files.createDirectory(tempDir.resolve("first"));
        write(first.resolve("a.json"), byObject);
        write(first.resolve("b.json"), byPath);
        Path second = Files.createDirectory(tempDir.resolve("second"));
        write(second.resolve("a.json"), byPath);
        write(second.resolve("b.json"), byObject);
        Set<String> baseline = coveredFiles(null);

        Set<String> expected = without(baseline, path -> path.contains("/Catalogs/"));
        assertEquals(expected, coveredFiles(first.toFile()));
        assertEquals(expected, coveredFiles(second.toFile()));
    }

    @Test
    void writesExclusionReport() throws Exception {
        File config = config("team.json",
                "{'team':'Команда А','exclude':{'objects':['Catalog.Справочник1','Catalog.Нет']}}");
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = convert(config, tempDir.resolve("out.xml"), stderr, "--exclusion-report", report.toString());

        assertEquals(0, exitCode, stderr.toString());
        JsonNode root = new ObjectMapper().readTree(report.toFile());
        assertEquals(1, root.get("entries").size());
        JsonNode entry = root.get("entries").get(0);
        assertEquals("team.json → exclude.objects[0]", entry.get("source").asText());
        assertEquals("Команда А", entry.get("team").asText());
        assertEquals("off", entry.get("kind").asText());
        assertEquals(6, entry.get("modules").size());
        assertTrue(entry.get("modules").get(0).asText().contains("/Catalogs/Справочник1/"));
        assertTrue(entry.get("excludedLines").asInt() > 0);
        assertEquals(entry.get("excludedLines").asInt(), root.get("excludedLines").asInt());
        assertTrue(root.get("totalLines").asInt() > root.get("excludedLines").asInt());
        assertEquals("team.json → exclude.objects[1]: rule does not match anything",
                root.get("warnings").get(0).asText());
    }

    @Test
    void reportShowsCoverageScopeFromIncludeSubsystems() throws Exception {
        File config = config("team.json", "{'includeSubsystems':['ПерваяПодсистема']}");
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = convert(config, tempDir.resolve("out.xml"), stderr, "--exclusion-report", report.toString());

        assertEquals(0, exitCode, stderr.toString());
        JsonNode scope = new ObjectMapper().readTree(report.toFile()).get("scope");
        assertEquals("team.json → includeSubsystems[0]", scope.get("sources").get(0).asText());
        assertTrue(scope.get("modulesOutOfScope").asInt() > 0, scope.toString());
    }

    @Test
    void lcovCountsOnlyLinesToCover() throws Exception {
        File config = config("team.json", "{'auto':{'objects':['Catalog.Справочник1']}}");
        Path output = tempDir.resolve("coverage.lcov");
        StringWriter stderr = new StringWriter();

        assertEquals(0, convert(config, output, stderr, "-f", "LCOV"), stderr.toString());

        String lcov = Files.readString(output);
        assertTrue(lcov.contains("/Catalogs/Справочник1/"), lcov);
        for (String record : lcov.split("end_of_record")) {
            if (!record.contains("SF:")) {
                continue;
            }
            long lines = record.lines().filter(line -> line.startsWith("DA:")).count();
            String found = record.lines().filter(line -> line.startsWith("LF:")).findFirst().orElse("LF:");
            assertEquals("LF:" + lines, found, record);
        }
    }

    @Test
    void exclusionReportRequiresSources() {
        StringWriter stderr = new StringWriter();
        String[] arguments = {
                PipeMessages.CONVERT_COMMAND,
                "-c", RAW_COVERAGE.getPath(),
                "-o", tempDir.resolve("raw.xml").toString(),
                "--exclusion-report", tempDir.resolve("exclusions.json").toString()
        };
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));

        assertEquals(CommandLine.ExitCode.SOFTWARE, commandLine.execute(arguments));
        assertTrue(stderr.toString().contains("Exclusion report requires source files"), stderr.toString());
    }

    @Test
    void excludesConfigurationModulesByMask() throws Exception {
        String root = "src/test/resources/configuration/Ext/";
        Set<String> withoutSession = coveredFiles(config("session.json",
                "{'exclude':{'objects':['Configuration.*.SessionModule']}}"));
        Set<String> withoutAll = coveredFiles(config("all.json", "{'exclude':{'objects':['Конфигурация.*']}}"));

        assertFalse(withoutSession.contains(root + "SessionModule.bsl"));
        assertTrue(withoutSession.contains(root + "ManagedApplicationModule.bsl"));
        assertTrue(withoutAll.stream().noneMatch(path -> path.startsWith(root)), withoutAll.toString());
    }

    @Test
    void pathGlobIsRelativeToProjectDirectory() throws Exception {
        Set<String> all = coveredFiles(null);
        Set<String> fromProject = coveredFiles(config("project.json",
                "{'exclude':{'paths':['src/test/resources/configuration/Catalogs/**']}}"));
        Set<String> fromSources = coveredFiles(config("sources.json", "{'exclude':{'paths':['Catalogs/**']}}"));

        assertEquals(without(all, path -> path.startsWith("src/test/resources/configuration/Catalogs/")), fromProject);
        assertEquals(all, fromSources);
    }

    @Test
    void includeSubsystemsNarrowsRulesOfOtherFiles() throws Exception {
        Path directory = tempDir.resolve("configs");
        Files.createDirectories(directory);
        write(directory.resolve("a.json"), "{'includeSubsystems':['ПодчиненнаяПодсистема']}");
        write(directory.resolve("b.json"), "{'exclude':{'objects':['Document.Документ1']}}");
        Path report = tempDir.resolve("report.json");
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(0, convert(directory.toFile(), output, stderr, "--exclusion-report", report.toString()),
                stderr.toString());

        NodeList files = read(output).getElementsByTagName("file");
        for (int index = 0; index < files.getLength(); index++) {
            assertFalse(((Element) files.item(index)).getAttribute("path").contains("Документ1"));
        }
        // Правило попало только в модули вне охвата: это не опечатка, предупреждения нет.
        assertEquals(0, new ObjectMapper().readTree(report.toFile()).get("warnings").size());
    }

    @Test
    void summaryInLogShowsTotalsTopRulesAndWarnings() throws Exception {
        String[] objects = {"Catalog.Справочник1", "Document.Документ1", "DataProcessor.Обработка1", "Report.Отчет1",
                "InformationRegister.РегистрСведений1", "BusinessProcess.БизнесПроцесс1", "ChartOfAccounts.ПланСчетов1",
                "AccumulationRegister.РегистрНакопления1", "AccountingRegister.РегистрБухгалтерии1",
                "ExchangePlan.ПланОбмена1", "Task.Задача1", "Catalog.Нет"};
        File config = config("many.json", "{'exclude':{'objects':['" + String.join("','", objects) + "']}}");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            assertEquals(0, convert(config, tempDir.resolve("coverage.xml"), new StringWriter()));
        } finally {
            System.setErr(originalErr);
        }

        List<String> summary = Arrays.stream(log.toString(StandardCharsets.UTF_8).split("\n"))
                .filter(line -> line.contains("ExclusionReport - "))
                .map(line -> line.substring(line.indexOf("ExclusionReport - ") + "ExclusionReport - ".length()))
                .filter(line -> !line.contains("does not match"))
                .collect(Collectors.toList());
        assertTrue(summary.get(0).matches("Coverage exclusions: \\d+ of \\d+ lines excluded, 0 marked auto \\(\\d+\\.\\d\\d%\\)"),
                summary.toString());
        assertEquals(10, summary.stream().filter(line -> line.matches("  \\d+ lines: many\\.json → .*")).count(),
                summary.toString());
        assertEquals("Coverage exclusion warnings: 1", summary.get(summary.size() - 1));
    }

    @Test
    void exclusionReportCreatesMissingDirectories() throws Exception {
        Path report = tempDir.resolve("reports").resolve("coverage").resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = convert(config("a.json", "{'exclude':{'objects':['Catalog.Справочник1']}}"),
                tempDir.resolve("coverage.xml"), stderr, "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        assertEquals(1, new ObjectMapper().readTree(report.toFile()).get("entries").size());
    }

    @Test
    void exclusionReportIsUtf8AndAddsUpWithCoverageFile() throws Exception {
        Path report = tempDir.resolve("report.json");
        Path output = tempDir.resolve("coverage.xml");
        File config = config("a.json", "{'exclude':{'objects':['Catalog.Справочник1']},"
                + "'auto':{'objects':['Document.Документ1']}}");
        StringWriter stderr = new StringWriter();

        assertEquals(0, convert(config, output, stderr, "--exclusion-report", report.toString()), stderr.toString());

        String json = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(json.contains("Справочник1"), json);
        JsonNode root = new ObjectMapper().readTree(json);
        // Исполнившиеся auto-строки попадают в отчёт покрытия как покрытые, остальные auto-строки в нём отсутствуют.
        NodeList lines = read(output).getElementsByTagName("lineToCover");
        long executedAutoLines = 0;
        for (int index = 0; index < lines.getLength(); index++) {
            Element line = (Element) lines.item(index);
            if (((Element) line.getParentNode()).getAttribute("path").contains("/Documents/Документ1/")) {
                assertEquals("true", line.getAttribute("covered"));
                executedAutoLines++;
            }
        }
        assertTrue(executedAutoLines > 0);
        assertEquals(root.get("totalLines").asLong() - root.get("excludedLines").asLong()
                - root.get("autoLines").asLong() + executedAutoLines, lines.getLength());
        assertTrue(root.get("excludedLines").asLong() > 0);
        assertTrue(root.get("autoLines").asLong() > 0);
    }

    private Set<String> coveredFiles(File config) throws Exception {
        Path output = tempDir.resolve("coverage-" + (++outputCounter) + ".xml");
        StringWriter stderr = new StringWriter();
        assertEquals(0, convert(config, output, stderr), stderr.toString());
        NodeList files = read(output).getElementsByTagName("file");
        return IntStream.range(0, files.getLength())
                .mapToObj(index -> ((Element) files.item(index)).getAttribute("path"))
                .collect(Collectors.toSet());
    }

    private Set<Integer> coveredLines(File config, String modulePath) throws Exception {
        Path output = tempDir.resolve("coverage-" + (++outputCounter) + ".xml");
        StringWriter stderr = new StringWriter();
        assertEquals(0, convert(config, output, stderr), stderr.toString());
        NodeList files = read(output).getElementsByTagName("file");
        Set<Integer> lines = new HashSet<>();
        for (int index = 0; index < files.getLength(); index++) {
            Element file = (Element) files.item(index);
            if (!file.getAttribute("path").equals(modulePath)) {
                continue;
            }
            NodeList lineNodes = file.getElementsByTagName("lineToCover");
            for (int lineIndex = 0; lineIndex < lineNodes.getLength(); lineIndex++) {
                lines.add(Integer.parseInt(((Element) lineNodes.item(lineIndex)).getAttribute("lineNumber")));
            }
        }
        return lines;
    }

    private File config(String name, String json) throws IOException {
        Path file = tempDir.resolve(name);
        write(file, json);
        return file.toFile();
    }

    private static void write(Path file, String json) throws IOException {
        Files.writeString(file, json.replace('\'', '"'));
    }

    private static Document read(Path coverageFile) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(coverageFile.toFile());
    }

    private static <T> Set<T> without(Set<T> values, Predicate<T> removed) {
        return values.stream().filter(removed.negate()).collect(Collectors.toSet());
    }

    private static int convert(File config, Path output, StringWriter stderr, String... extraArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.CONVERT_COMMAND,
                "-P", new File(".").getAbsolutePath(),
                "-s", CONFIGURATION.getPath(),
                "-c", RAW_COVERAGE.getPath(),
                "-o", output.toString()));
        if (config != null) {
            arguments.add("--config");
            arguments.add(config.getPath());
        }
        arguments.addAll(Arrays.asList(extraArguments));
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments.toArray(new String[0]));
    }
}
