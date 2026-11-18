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
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalDataProcessorTest {

    private static final Path EXTERNAL_PROCESSOR = Path.of("src/test/resources/externalprocessor");
    private static final String OBJECT_MODULE = "Процедура ПроцедураМодуляОбработки() Экспорт\n"
            + "\tНичегоНеДелать(); // Cover:off\n"
            + "КонецПроцедуры\n";
    private static final String FORM_MODULE = "&НаКлиенте\n"
            + "Процедура Команда1(Команда)\n"
            + "\tКоманда1НаСервере(); // Cover:off\n"
            + "КонецПроцедуры\n";

    @TempDir
    Path tempDir;

    @Test
    void markersWorkInConfiguratorFormat() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("configurator"), project);
        Files.writeString(project.resolve("ВнешняяОбработка/Ext/ObjectModule.bsl"), OBJECT_MODULE);
        Files.writeString(project.resolve("ВнешняяОбработка/Forms/Форма/Ext/Form/Module.bsl"), FORM_MODULE);

        assertEquals(Set.of("ВнешняяОбработка/Ext/ObjectModule.bsl:2 → Cover:off",
                        "ВнешняяОбработка/Forms/Форма/Ext/Form/Module.bsl:3 → Cover:off"),
                exclusionSources(project, "ВнешняяОбработка.xml"));
    }

    @Test
    void markersWorkInEdtFormat() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("edt/pc"), project);
        Path processor = project.resolve("src/ExternalDataProcessors/ВнешняяОбработка");
        Files.writeString(processor.resolve("ObjectModule.bsl"), OBJECT_MODULE);
        Files.writeString(processor.resolve("Forms/Форма/Module.bsl"), FORM_MODULE);

        assertEquals(Set.of("src/ExternalDataProcessors/ВнешняяОбработка/ObjectModule.bsl:2 → Cover:off",
                        "src/ExternalDataProcessors/ВнешняяОбработка/Forms/Форма/Module.bsl:3 → Cover:off"),
                exclusionSources(project, "src/ExternalDataProcessors/ВнешняяОбработка/ВнешняяОбработка.mdo"));
    }

    @Test
    void readsConfiguratorProcessorWithoutForms() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("configurator"), project);
        Files.writeString(project.resolve("ВнешняяОбработка/Ext/ObjectModule.bsl"), OBJECT_MODULE);
        deleteDirectory(project.resolve("ВнешняяОбработка/Forms"));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        Set<String> sources;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            sources = exclusionSources(project, "ВнешняяОбработка.xml");
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(Set.of("ВнешняяОбработка/Ext/ObjectModule.bsl:2 → Cover:off"), sources);
        String output = log.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("Exception"), output);
    }

    @Test
    void skipsBrokenFormDescriptionOfConfiguratorProcessor() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("configurator"), project);
        Files.writeString(project.resolve("ВнешняяОбработка/Ext/ObjectModule.bsl"), OBJECT_MODULE);
        Files.writeString(project.resolve("ВнешняяОбработка/Forms/Форма.xml"), "<MetaDataObject>");

        assertEquals(Set.of("ВнешняяОбработка/Ext/ObjectModule.bsl:2 → Cover:off"),
                exclusionSources(project, "ВнешняяОбработка.xml"));
    }

    @Test
    void skipsEdtFormWithoutName() throws Exception {
        Path project = tempDir.resolve("project");
        TestUtils.copyDirectory(EXTERNAL_PROCESSOR.resolve("edt/pc"), project);
        Path processor = project.resolve("src/ExternalDataProcessors/ВнешняяОбработка");
        Files.writeString(processor.resolve("ObjectModule.bsl"), OBJECT_MODULE);
        Path descriptor = processor.resolve("ВнешняяОбработка.mdo");
        Files.writeString(descriptor, Files.readString(descriptor).replace("<name>Форма</name>", ""));

        assertEquals(Set.of("src/ExternalDataProcessors/ВнешняяОбработка/ObjectModule.bsl:2 → Cover:off"),
                exclusionSources(project, "src/ExternalDataProcessors/ВнешняяОбработка/ВнешняяОбработка.mdo"));
    }

    @Test
    void rejectsUnknownSourceFormat() throws Exception {
        Files.writeString(tempDir.resolve("Обработка.xml"), "<Something/>");
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-P", tempDir.toAbsolutePath().toString(), "-s", "Обработка.xml");

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Unknown source format"), stderr.toString());
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(file);
            }
        }
    }

    @Test
    void rejectsCoverageConfiguration() throws Exception {
        Path config = tempDir.resolve("team.json");
        Files.writeString(config, "{\"exclude\":{\"lines\":[\"А\"]}}");
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-P", EXTERNAL_PROCESSOR.resolve("configurator").toAbsolutePath().toString(),
                "-s", "ВнешняяОбработка.xml",
                "--config", config.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Coverage configuration is not supported for external data processors"),
                stderr.toString());
    }

    private Set<String> exclusionSources(Path project, String sources) throws Exception {
        Path report = tempDir.resolve("exclusions.json");
        StringWriter stderr = new StringWriter();

        int exitCode = execute(stderr, PipeMessages.VALIDATE_COMMAND,
                "-P", project.toAbsolutePath().toString(),
                "-s", sources,
                "--strict",
                "--exclusion-report", report.toString());

        assertEquals(CommandLine.ExitCode.OK, exitCode, stderr.toString());
        Set<String> result = new TreeSet<>();
        for (JsonNode entry : new ObjectMapper().readTree(report.toFile()).get("entries")) {
            result.add(entry.get("source").asText());
        }
        return result;
    }

    private static int execute(StringWriter stderr, String... arguments) {
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments);
    }
}
