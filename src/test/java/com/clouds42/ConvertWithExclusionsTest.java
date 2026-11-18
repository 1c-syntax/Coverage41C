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
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Сквозная проверка: исходники + конфиг исключений + сырые замеры → итоговый отчёт покрытия.
class ConvertWithExclusionsTest {

    private static final String MODULE = "configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";
    // Ключ модуля в сырых замерах: UUID общего модуля и UUID свойства «Модуль».
    private static final String MODULE_KEY = "/1be4af7e-334e-49fa-a9f9-d80c737ff954/d5963243-262e-4398-b4d7-fb16d06484f6";
    private static final String INTERNAL_XML = new File("src/test/resources/coverage/internal.xml").getAbsolutePath();
    // Строки, выполненные тестами: 2 — обычная, 4 — под Cover:off, 7 — под Cover:auto.
    private static final String[] MARKED_MODULE = {
            "Процедура А() Экспорт",
            "\tБ();",
            "\t// Cover:off",
            "\tВ();",
            "\t// Cover:on",
            "\t// Cover:auto",
            "\tГ();",
            "\tД();",
            "\t// Cover:on",
            "\tЕ();",
            "КонецПроцедуры"};
    private static final String[] PLAIN_MODULE = {
            "Процедура А() Экспорт",
            "\tБ();",
            "\tВ();",
            "КонецПроцедуры"};

    @TempDir
    Path tempDir;

    @Test
    void markersShapeGenericCoverage() throws Exception {
        Path output = convert(MARKED_MODULE, null, new int[]{2, 4, 7}, "GENERIC_COVERAGE");

        assertEquals(Map.of(2, true, 7, true, 10, false, 11, false), genericCoverage(output).get(MODULE));
    }

    @Test
    void markersShapeLcov() throws Exception {
        Path output = convert(MARKED_MODULE, null, new int[]{2, 4, 7}, "LCOV");

        // Порядок строк DA в LCOV не гарантирован.
        assertEquals(Set.of("TN:", "SF:" + MODULE, "DA:2,1", "DA:7,1", "DA:10,0", "DA:11,0", "LH:2", "LF:4",
                "end_of_record"), Set.copyOf(lcovRecord(output, MODULE)));
    }

    @Test
    void markersShapeCobertura() throws Exception {
        Path output = convert(MARKED_MODULE, null, new int[]{2, 4, 7}, "COBERTURA");

        assertEquals(Map.of(2, 1, 7, 1, 10, 0, 11, 0), coberturaHits(output, MODULE));
    }

    @Test
    void fileOffLeavesModuleOutOfEveryFormat() throws Exception {
        String[] module = {"// Cover:file-off", "Процедура А() Экспорт", "\tБ();", "КонецПроцедуры"};

        assertFalse(genericCoverage(convert(module, null, new int[]{3}, "GENERIC_COVERAGE")).containsKey(MODULE));
        assertFalse(Files.readAllLines(convert(module, null, new int[]{3}, "LCOV")).contains("SF:" + MODULE));
        assertEquals(Map.of(), coberturaHits(convert(module, null, new int[]{3}, "COBERTURA"), MODULE));
    }

    @Test
    void moduleExcludedByConfigIsLeftOut() throws Exception {
        Path output = convert(PLAIN_MODULE, "{'exclude':{'objects':['CommonModule.ПростойОбщийМодуль']}}",
                new int[]{2}, "GENERIC_COVERAGE");

        Map<String, Map<Integer, Boolean>> coverage = genericCoverage(output);
        assertFalse(coverage.containsKey(MODULE));
        assertFalse(coverage.isEmpty());
    }

    @Test
    void moduleUnderAutoRuleKeepsOnlyExecutedLines() throws Exception {
        Path output = convert(PLAIN_MODULE, "{'auto':{'objects':['CommonModule.ПростойОбщийМодуль']}}",
                new int[]{2}, "GENERIC_COVERAGE");

        assertEquals(Map.of(2, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void lineRuleLeavesOutMatchingLines() throws Exception {
        Path output = convert(PLAIN_MODULE, "{'exclude':{'lines':['^\\\\s*В\\\\(\\\\);']}}",
                new int[]{2, 3}, "GENERIC_COVERAGE");

        assertEquals(Map.of(2, true, 4, false), genericCoverage(output).get(MODULE));
    }

    @Test
    void removesModulesBySupportLevel() throws Exception {
        String locked = "configuration/CommonModules/ПростойОбщийМодуль/Ext/Module.bsl";
        String editable = "configuration/CommonModules/ГлобальныйОбщийМодуль/Ext/Module.bsl";
        String notSupported = "configuration/CommonModules/ОбщийМодульПовтИспСеанс/Ext/Module.bsl";
        String own = "configuration/CommonModules/ОбщийМодульПовтИспВызов/Ext/Module.bsl";
        Path project = TestUtils.copyConfiguration(tempDir);
        // Поставка: первый модуль не редактируется, второй редактируется с сохранением поддержки, третий снят с поддержки.
        Files.writeString(project.resolve("configuration/Ext/ParentConfigurations.bin"),
                "{6,0,1,46c7c1d0-b04d-4295-9b04-ae3207c18d29,0,11111111-1111-1111-1111-111111111111,"
                        + "\"1.0\",\"Вендор\",\"Поставка\",3,"
                        + "0,0,1be4af7e-334e-49fa-a9f9-d80c737ff954,1be4af7e-334e-49fa-a9f9-d80c737ff954,"
                        + "1,0,9e9c021c-bdbd-4804-a53a-9442ba9eb18c,9e9c021c-bdbd-4804-a53a-9442ba9eb18c,"
                        + "2,0,a09f73b1-0058-4f3b-a22b-799ef3c2a33d,a09f73b1-0058-4f3b-a22b-799ef3c2a33d,0}");

        assertEquals(List.of(true, true, true, true), modulesPresent(project, "NONE", locked, editable, notSupported, own));
        assertEquals(List.of(false, true, true, true),
                modulesPresent(project, "NOT_EDITABLE", locked, editable, notSupported, own));
        assertEquals(List.of(false, false, true, true),
                modulesPresent(project, "EDITABLE_SUPPORT_ENABLED", locked, editable, notSupported, own));
        assertEquals(List.of(false, false, false, true),
                modulesPresent(project, "NOT_SUPPORTED", locked, editable, notSupported, own));
    }

    private List<Boolean> modulesPresent(Path project, String removeSupport, String... modules) throws Exception {
        Path output = tempDir.resolve("coverage-" + removeSupport + ".xml");
        StringWriter stderr = new StringWriter();
        assertEquals(CommandLine.ExitCode.OK, convert(project, output, stderr, "-r", removeSupport), stderr.toString());
        Map<String, Map<Integer, Boolean>> coverage = genericCoverage(output);
        List<Boolean> present = new ArrayList<>();
        for (String module : modules) {
            present.add(coverage.containsKey(module));
        }
        return present;
    }

    @Test
    void verboseLogShowsCoveredLineThatIsNotToCover() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve(MODULE), String.join("\n", PLAIN_MODULE) + "\n");
        Path rawCoverage = tempDir.resolve("internal.xml");
        Files.writeString(rawCoverage, "<coverage version=\"1\"><file path=\"" + MODULE_KEY + "\">"
                + "<lineToCover covered=\"true\" lineNumber=\"1\"/>"
                + "<lineToCover covered=\"false\" lineNumber=\"2\"/></file>"
                + "<file path=\"/00000000-0000-0000-0000-000000000000/00000000-0000-0000-0000-000000000000\"/>"
                + "</coverage>");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            exitCode = convert(project, rawCoverage, tempDir.resolve("coverage.xml"), new StringWriter(), "--verbose");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(CommandLine.ExitCode.OK, exitCode);
        String output = log.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Can't find line to cover 1 in module"), output);
        assertTrue(output.contains(">>> Процедура А() Экспорт"), output);
        assertTrue(output.contains("Can't find file key: 00000000-0000-0000-0000-000000000000/"), output);
        assertEquals(Map.of(2, false, 3, false, 4, false), genericCoverage(tempDir.resolve("coverage.xml")).get(MODULE));
    }

    @Test
    void rejectsConfigurationWithoutSources() throws Exception {
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"exclude\":{\"lines\":[\"А\"]}}");
        StringWriter stderr = new StringWriter();
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));

        int exitCode = commandLine.execute(PipeMessages.CONVERT_COMMAND,
                "-c", INTERNAL_XML,
                "-o", tempDir.resolve("coverage.xml").toString(),
                "--config", config.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Coverage configuration requires source files"), stderr.toString());
    }

    // includeUsers делает конфиг допустимым без исходников только для сбора; convert без исходников — по-прежнему ошибка.
    @Test
    void rejectsConfigurationWithIncludeUsersWithoutSources() throws Exception {
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"includeUsers\":[\"unit\"],\"exclude\":{\"lines\":[\"А\"]}}");
        StringWriter stderr = new StringWriter();
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));

        int exitCode = commandLine.execute(PipeMessages.CONVERT_COMMAND,
                "-c", INTERNAL_XML,
                "-o", tempDir.resolve("coverage.xml").toString(),
                "--config", config.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Coverage configuration requires source files"), stderr.toString());
    }

    @Test
    void moduleWithoutSourceTextIsLeftOutOfCoverage() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.write(project.resolve(MODULE), new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0x00, 0x02});
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(project, output, stderr), stderr.toString());
        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    @Test
    void moduleThatCannotBeReadIsLeftOutOfCoverage() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.write(project.resolve(MODULE),
                "Процедура А()\n\tБ();\nКонецПроцедуры\n".getBytes(Charset.forName("windows-1251")));
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(project, output, stderr), stderr.toString());
        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    // Исполняемые строки: 2, 4, 6; блок доработки — строка 4.
    private static final String[] CHANGED_MODULE = {
            "Процедура А() Экспорт",
            "\tТиповой();",
            "\t// ++ уч",
            "\tНаш();",
            "\t// -- уч",
            "КонецПроцедуры"};

    @Test
    void changeBlockOfModuleOutOfScopeIsCovered() throws Exception {
        Path output = convert(CHANGED_MODULE, "{'includeSubsystems':['ПодчиненнаяПодсистема'],'changeMarkers':{}}",
                new int[]{2, 4}, "GENERIC_COVERAGE");

        assertEquals(Map.of(4, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void changeBlockClosedByInlineEndMarkerCoversItsLine() throws Exception {
        Path output = convert(new String[]{"Процедура А()", "\tТиповой();", "\t// ++ уч", "\tНаш();",
                        "КонецПроцедуры // -- уч"},
                "{'includeSubsystems':['ПодчиненнаяПодсистема'],'changeMarkers':{}}",
                new int[]{2, 4, 5}, "GENERIC_COVERAGE");

        assertEquals(Map.of(4, true, 5, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void moduleOutOfScopeWithoutChangeMarkersStaysOut() throws Exception {
        Path output = convert(CHANGED_MODULE, "{'includeSubsystems':['ПодчиненнаяПодсистема']}",
                new int[]{2, 4}, "GENERIC_COVERAGE");

        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    @Test
    void changeBlockOfModuleExcludedByRuleIsCovered() throws Exception {
        Path output = convert(CHANGED_MODULE,
                "{'exclude':{'objects':['CommonModule.ПростойОбщийМодуль']},'changeMarkers':{}}",
                new int[]{2, 4}, "GENERIC_COVERAGE");

        assertEquals(Map.of(4, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void changeBlockOfModuleUnderRemoveSupportIsCovered() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve("configuration/Ext/ParentConfigurations.bin"),
                "{6,0,1,46c7c1d0-b04d-4295-9b04-ae3207c18d29,0,11111111-1111-1111-1111-111111111111,"
                        + "\"1.0\",\"Вендор\",\"Поставка\",3,"
                        + "0,0,1be4af7e-334e-49fa-a9f9-d80c737ff954,1be4af7e-334e-49fa-a9f9-d80c737ff954,"
                        + "1,0,9e9c021c-bdbd-4804-a53a-9442ba9eb18c,9e9c021c-bdbd-4804-a53a-9442ba9eb18c,"
                        + "2,0,a09f73b1-0058-4f3b-a22b-799ef3c2a33d,a09f73b1-0058-4f3b-a22b-799ef3c2a33d,0}");

        Path output = convert(CHANGED_MODULE, "{'changeMarkers':{}}", new int[]{2, 4}, "GENERIC_COVERAGE",
                "-r", "NOT_EDITABLE");

        assertEquals(Map.of(4, true), genericCoverage(output).get(MODULE));
    }

    @Test
    void exclusionReportAddsUpWithChangeBlocks() throws Exception {
        Path report = tempDir.resolve("exclusions.json");

        Path output = convert(CHANGED_MODULE, "{'includeSubsystems':['ПодчиненнаяПодсистема'],"
                        + "'exclude':{'objects':['Catalog.Справочник1']},'changeMarkers':{}}", new int[]{2, 4},
                "GENERIC_COVERAGE", "--exclusion-report", report.toString());

        JsonNode root = new ObjectMapper().readTree(report.toFile());
        int linesInCoverage = genericCoverage(output).values().stream().mapToInt(Map::size).sum();
        assertEquals(root.get("totalLines").asInt() - root.get("excludedLines").asInt()
                - root.get("autoLines").asInt(), linesInCoverage);
        assertEquals(1, root.get("includedLines").asInt());
        JsonNode blocks = root.get("changeBlocks").get(0);
        assertEquals("team.json → changeMarkers[0]", blocks.get("source").asText());
        assertEquals(MODULE, blocks.get("modules").get(0).asText());
    }

    @Test
    void moduleWithoutSourceTextOutOfScopeIsLeftOutWithChangeMarkers() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.write(project.resolve(MODULE), new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F, 0x00, 0x02});
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"includeSubsystems\":[\"ПодчиненнаяПодсистема\"],\"changeMarkers\":{}}");
        Path output = tempDir.resolve("coverage.xml");
        StringWriter stderr = new StringWriter();

        assertEquals(CommandLine.ExitCode.OK, convert(project, output, stderr, "--config", config.toString()),
                stderr.toString());
        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    // Исполняемые строки: 3 и 15 вне методов с префиксом; 7, 8, 9 и 12, 13 — в тестОбработать и тестПолучить.
    private static final String[] ROUTINE_MODULE = {
            "Перем Счётчик;",
            "Процедура Типовая() Экспорт",
            "\tБ();",
            "КонецПроцедуры",
            "",
            "Процедура тестОбработать(Документ) Экспорт",
            "\tВ();",
            "\tГ();",
            "КонецПроцедуры",
            "",
            "Функция тестПолучить()",
            "\tВозврат 1;",
            "КонецФункции",
            "",
            "Д();"};
    private static final String ROUTINES = "{'includePrefix':['тест'],'prefixScope':{'routines':true}}";

    @Test
    void prefixedMethodsOfModuleOutOfScopeAreCovered() throws Exception {
        Path output = convert(ROUTINE_MODULE, ROUTINES, new int[]{3, 7, 12}, "GENERIC_COVERAGE");

        Map<String, Map<Integer, Boolean>> coverage = genericCoverage(output);
        assertEquals(Map.of(7, true, 8, false, 9, false, 12, true, 13, false), coverage.get(MODULE));
        assertEquals(Set.of(MODULE), coverage.keySet());
    }

    @Test
    void prefixedMethodsShapeLcovAndCobertura() throws Exception {
        Path lcov = convert(ROUTINE_MODULE, ROUTINES, new int[]{3, 7, 12}, "LCOV");
        assertEquals(Set.of("TN:", "SF:" + MODULE, "DA:7,1", "DA:8,0", "DA:9,0", "DA:12,1", "DA:13,0", "LH:2", "LF:5",
                "end_of_record"), Set.copyOf(lcovRecord(lcov, MODULE)));

        Path cobertura = convert(ROUTINE_MODULE, ROUTINES, new int[]{3, 7, 12}, "COBERTURA");
        assertEquals(Map.of(7, 1, 8, 0, 9, 0, 12, 1, 13, 0), coberturaHits(cobertura, MODULE));
    }

    @Test
    void subsystemContentsAreIncludedOnlyWithFlag() throws Exception {
        String catalog = "configuration/Catalogs/Справочник1/Ext/ObjectModule.bsl";
        String accounts = "configuration/ChartsOfAccounts/ПланСчетов1/Ext/ObjectModule.bsl";

        Map<String, Map<Integer, Boolean>> full = genericCoverage(convert(PLAIN_MODULE, "{'includePrefix':['Перв']}",
                new int[]{2}, "GENERIC_COVERAGE"));
        assertTrue(full.containsKey(catalog), full.keySet().toString());
        assertTrue(full.containsKey(accounts), full.keySet().toString());
        assertFalse(full.containsKey(MODULE));

        Map<String, Map<Integer, Boolean>> none = genericCoverage(convert(PLAIN_MODULE,
                "{'includePrefix':['Перв'],'prefixScope':{'includeSubsystemContents':false}}", new int[]{2},
                "GENERIC_COVERAGE"));
        assertTrue(none.isEmpty(), none.keySet().toString());
    }

    @Test
    void prefixedMethodsUnderRemoveSupportStayOut() throws Exception {
        Path project = TestUtils.copyConfiguration(tempDir);
        Files.writeString(project.resolve("configuration/Ext/ParentConfigurations.bin"),
                "{6,0,1,46c7c1d0-b04d-4295-9b04-ae3207c18d29,0,11111111-1111-1111-1111-111111111111,"
                        + "\"1.0\",\"Вендор\",\"Поставка\",1,"
                        + "0,0,1be4af7e-334e-49fa-a9f9-d80c737ff954,1be4af7e-334e-49fa-a9f9-d80c737ff954,0}");

        Path output = convert(ROUTINE_MODULE, ROUTINES, new int[]{7}, "GENERIC_COVERAGE", "-r", "NOT_EDITABLE");

        assertFalse(genericCoverage(output).containsKey(MODULE));
    }

    private Path convert(String[] moduleLines, String configJson, int[] executedLines, String format,
                         String... extraOptions) throws Exception {
        Path project = tempDir.resolve("project");
        if (!Files.exists(project)) {
            TestUtils.copyConfiguration(tempDir);
        }
        Files.writeString(project.resolve(MODULE), String.join("\n", moduleLines) + "\n");
        Path rawCoverage = tempDir.resolve("internal.xml");
        StringBuilder raw = new StringBuilder("<coverage version=\"1\"><file path=\"" + MODULE_KEY + "\">");
        for (int line : executedLines) {
            raw.append("<lineToCover covered=\"true\" lineNumber=\"").append(line).append("\"/>");
        }
        Files.writeString(rawCoverage, raw.append("</file></coverage>").toString());
        List<String> options = new ArrayList<>(List.of("-f", format));
        if (configJson != null) {
            Path config = tempDir.resolve("team.json");
            Files.writeString(config, configJson.replace('\'', '"'));
            options.addAll(List.of("--config", config.toString()));
        }
        options.addAll(Arrays.asList(extraOptions));
        Path output = tempDir.resolve("coverage.out");
        StringWriter stderr = new StringWriter();

        int exitCode = convert(project, rawCoverage, output, stderr, options.toArray(new String[0]));

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        return output;
    }

    private static int convert(Path project, Path output, StringWriter stderr, String... options) {
        return convert(project, Path.of(INTERNAL_XML), output, stderr, options);
    }

    private static int convert(Path project, Path rawCoverage, Path output, StringWriter stderr, String... options) {
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.CONVERT_COMMAND,
                "-P", project.toAbsolutePath().toString(),
                "-s", "configuration",
                "-c", rawCoverage.toString(),
                "-o", output.toString()));
        arguments.addAll(Arrays.asList(options));
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments.toArray(new String[0]));
    }

    private static List<String> lcovRecord(Path output, String path) throws Exception {
        List<String> lines = Files.readAllLines(output);
        int start = lines.indexOf("SF:" + path) - 1;
        int end = start;
        while (!lines.get(end).equals("end_of_record")) {
            end++;
        }
        return lines.subList(start, end + 1);
    }

    // номер строки → число попаданий
    private static Map<Integer, Integer> coberturaHits(Path output, String path) throws Exception {
        NodeList classes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
                .getElementsByTagName("class");
        for (int i = 0; i < classes.getLength(); i++) {
            Element coberturaClass = (Element) classes.item(i);
            if (!coberturaClass.getAttribute("filename").equals(path)) {
                continue;
            }
            Map<Integer, Integer> hits = new TreeMap<>();
            NodeList lines = coberturaClass.getElementsByTagName("line");
            for (int j = 0; j < lines.getLength(); j++) {
                Element line = (Element) lines.item(j);
                hits.put(Integer.parseInt(line.getAttribute("number")), Integer.parseInt(line.getAttribute("hits")));
            }
            return hits;
        }
        return Map.of();
    }

    // path → (номер строки → покрыта ли)
    private static Map<String, Map<Integer, Boolean>> genericCoverage(Path output) throws Exception {
        Map<String, Map<Integer, Boolean>> result = new TreeMap<>();
        NodeList files = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
                .getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            Map<Integer, Boolean> lines = new TreeMap<>();
            NodeList lineNodes = file.getElementsByTagName("lineToCover");
            for (int j = 0; j < lineNodes.getLength(); j++) {
                Element line = (Element) lineNodes.item(j);
                lines.put(Integer.parseInt(line.getAttribute("lineNumber")),
                        Boolean.parseBoolean(line.getAttribute("covered")));
            }
            result.put(file.getAttribute("path"), lines);
        }
        return result;
    }
}
