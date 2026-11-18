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

import com.github._1c_syntax.bsl.mdclasses.CF;
import com.github._1c_syntax.bsl.mdclasses.MDClasses;
import com.github._1c_syntax.bsl.mdo.Subsystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import picocli.CommandLine;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubsystemFilterTest {

    private static final File RAW_COVERAGE = new File("src/test/resources/coverage/internal.xml");
    private static final File PARENT_SUBSYSTEM_CONFIG =
            new File("src/test/resources/config/coverage-parent-subsystem.json");
    private static final File CHILD_SUBSYSTEMS_CONFIG =
            new File("src/test/resources/config/coverage-child-subsystems.json");
    private static final File UNKNOWN_SUBSYSTEM_CONFIG =
            new File("src/test/resources/config/coverage-unknown-subsystem.json");
    private static final File INCLUDE_PREFIX_CONFIG =
            new File("src/test/resources/config/coverage-include-prefix.json");
    private static final File SUBSYSTEM_MASK_CONFIG =
            new File("src/test/resources/config/coverage-subsystem-mask.json");

    @TempDir
    Path tempDir;

    @Test
    void ambiguousLeafNameRequiresQualifiedSubsystemPath() throws Exception {
        CF configuration = configurationWithDuplicateSubsystemNames();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SubsystemFilter.resolve(configuration, "ПодчиненнаяПодсистема"));
        assertTrue(error.getMessage().contains("Ambiguous subsystem name"), error.getMessage());
        // allowMissing разрешает отсутствие в другом источнике, но не неоднозначность.
        assertThrows(IllegalArgumentException.class,
                () -> SubsystemFilter.resolve(configuration, "ПодчиненнаяПодсистема", true));
    }

    @Test
    void qualifiedPathsKeepSameNamedSubsystemsSeparate() throws Exception {
        CF configuration = configurationWithDuplicateSubsystemNames();

        Set<String> first = SubsystemFilter.resolve(configuration,
                "ПерваяПодсистема.ПодчиненнаяПодсистема");
        Set<String> second = SubsystemFilter.resolve(configuration,
                "ВтораяПодсистема.ПодчиненнаяПодсистема");
        assertEquals(Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"), first);
        assertEquals(Set.of("Subsystem.ВтораяПодсистема.Subsystem.ПодчиненнаяПодсистема"), second);
    }

    @Test
    void fullRootPathTakesPrecedenceOverSameNestedName() throws Exception {
        Path root = TestUtils.copyConfiguration(tempDir).resolve("configuration");
        Path subsystems = root.resolve("Subsystems");
        String child = Files.readString(subsystems.resolve("ПерваяПодсистема/Subsystems/ПодчиненнаяПодсистема.xml"));
        Files.writeString(subsystems.resolve("ПодчиненнаяПодсистема.xml"),
                child.replace("0fbc4f99-ef89-4d98-9b8f-d26c216c8e31", "0fbc4f99-ef89-4d98-9b8f-d26c216c8e33"));
        Path config = root.resolve("Configuration.xml");
        Files.writeString(config, Files.readString(config).replace("<Subsystem>ПерваяПодсистема</Subsystem>",
                "<Subsystem>ПерваяПодсистема</Subsystem><Subsystem>ПодчиненнаяПодсистема</Subsystem>"));
        CF configuration = (CF) MDClasses.createConfiguration(root);

        assertEquals(Set.of("Subsystem.ПодчиненнаяПодсистема"),
                SubsystemFilter.resolve(configuration, "ПодчиненнаяПодсистема"));
        assertEquals(Set.of("Subsystem.ПерваяПодсистема.Subsystem.ПодчиненнаяПодсистема"),
                SubsystemFilter.resolve(configuration, "ПерваяПодсистема.ПодчиненнаяПодсистема"));
    }

    private CF configurationWithDuplicateSubsystemNames() throws Exception {
        Path root = TestUtils.copyConfiguration(tempDir).resolve("configuration");
        Path subsystems = root.resolve("Subsystems");
        String parent = Files.readString(subsystems.resolve("ПерваяПодсистема.xml"))
                .replace("ПерваяПодсистема", "ВтораяПодсистема")
                .replace("<Subsystem>ПочиненнаяСистема2</Subsystem>", "")
                .replace("3d00f7d6-e3b0-49cf-8093-e2e4f6ea2293", "3d00f7d6-e3b0-49cf-8093-e2e4f6ea2294");
        Files.writeString(subsystems.resolve("ВтораяПодсистема.xml"), parent);
        Path child = subsystems.resolve("ВтораяПодсистема/Subsystems/ПодчиненнаяПодсистема.xml");
        Files.createDirectories(child.getParent());
        Files.writeString(child, Files.readString(subsystems.resolve("ПерваяПодсистема/Subsystems/ПодчиненнаяПодсистема.xml"))
                .replace("0fbc4f99-ef89-4d98-9b8f-d26c216c8e31", "0fbc4f99-ef89-4d98-9b8f-d26c216c8e32"));
        Path config = root.resolve("Configuration.xml");
        Files.writeString(config, Files.readString(config).replace("<Subsystem>ПерваяПодсистема</Subsystem>",
                "<Subsystem>ПерваяПодсистема</Subsystem><Subsystem>ВтораяПодсистема</Subsystem>"));
        return (CF) MDClasses.createConfiguration(root);
    }

    @Test
    void includesParentSubsystemAndItsNestedSubsystemsForEdt() throws Exception {
        Path output = tempDir.resolve("edt-coverage.xml");

        int exitCode = convert(new File("src/test/resources/edt/pc"), PARENT_SUBSYSTEM_CONFIG, output);

        assertEquals(0, exitCode);
        assertEquals(Set.of(
                "src/test/resources/edt/pc/src/BusinessProcesses/БизнесПроцесс1/Forms/ФормаБизнесПроцесса/Module.bsl",
                "src/test/resources/edt/pc/src/BusinessProcesses/БизнесПроцесс1/ManagerModule.bsl",
                "src/test/resources/edt/pc/src/BusinessProcesses/БизнесПроцесс1/ObjectModule.bsl",
                "src/test/resources/edt/pc/src/Catalogs/Справочник1/Commands/Команда1/CommandModule.bsl",
                "src/test/resources/edt/pc/src/Catalogs/Справочник1/Forms/ФормаВыбора/Module.bsl",
                "src/test/resources/edt/pc/src/Catalogs/Справочник1/Forms/ФормаСписка/Module.bsl",
                "src/test/resources/edt/pc/src/Catalogs/Справочник1/Forms/ФормаЭлемента/Module.bsl",
                "src/test/resources/edt/pc/src/Catalogs/Справочник1/ManagerModule.bsl",
                "src/test/resources/edt/pc/src/Catalogs/Справочник1/ObjectModule.bsl",
                "src/test/resources/edt/pc/src/ChartsOfAccounts/ПланСчетов1/Forms/ФормаСчета/Module.bsl",
                "src/test/resources/edt/pc/src/ChartsOfAccounts/ПланСчетов1/ManagerModule.bsl",
                "src/test/resources/edt/pc/src/ChartsOfAccounts/ПланСчетов1/ObjectModule.bsl",
                "src/test/resources/edt/pc/src/CommonCommands/ОбщаяКоманда1/CommandModule.bsl",
                "src/test/resources/edt/pc/src/DataProcessors/Обработка1/Forms/Форма/Module.bsl",
                "src/test/resources/edt/pc/src/DataProcessors/Обработка1/ManagerModule.bsl",
                "src/test/resources/edt/pc/src/DataProcessors/Обработка1/ObjectModule.bsl"
        ), readCoveredFiles(output));
    }

    @Test
    void includesSeveralSubsystemsForConfigurator() throws Exception {
        Path output = tempDir.resolve("configurator-coverage.xml");

        int exitCode = convert(new File("src/test/resources/configuration"), CHILD_SUBSYSTEMS_CONFIG, output);

        assertEquals(0, exitCode);
        assertEquals(Set.of(
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Forms/ФормаБизнесПроцесса/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Commands/Команда1/Ext/CommandModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаВыбора/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаСписка/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаЭлемента/Ext/Form/Module.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Forms/ФормаСчета/Ext/Form/Module.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Forms/Форма/Ext/Form/Module.bsl"
        ), readCoveredFiles(output));
    }

    @Test
    void includesSubsystemsMatchingMaskForConfigurator() throws Exception {
        Path output = tempDir.resolve("mask-coverage.xml");

        int exitCode = convert(new File("src/test/resources/configuration"), SUBSYSTEM_MASK_CONFIG, output);

        assertEquals(0, exitCode);
        assertEquals(Set.of(
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Forms/ФормаБизнесПроцесса/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Commands/Команда1/Ext/CommandModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаВыбора/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаСписка/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаЭлемента/Ext/Form/Module.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Forms/ФормаСчета/Ext/Form/Module.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Forms/Форма/Ext/Form/Module.bsl"
        ), readCoveredFiles(output));
    }

    @Test
    void includePrefixAddsObjectsFormsAndSubsystemsToIncludedSubsystems() throws Exception {
        Path output = tempDir.resolve("prefix-coverage.xml");

        int exitCode = convert(new File("src/test/resources/configuration"), INCLUDE_PREFIX_CONFIG, output);

        assertEquals(0, exitCode);
        assertEquals(Set.of(
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/BusinessProcesses/БизнесПроцесс1/Forms/ФормаБизнесПроцесса/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Commands/Команда1/Ext/CommandModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаВыбора/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаСписка/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Catalogs/Справочник1/Forms/ФормаЭлемента/Ext/Form/Module.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/ChartsOfAccounts/ПланСчетов1/Forms/ФормаСчета/Ext/Form/Module.bsl",
                "src/test/resources/configuration/ChartsOfCharacteristicTypes/ПланВидовХарактеристик1/Forms/ФормаЭлемента/Ext/Form/Module.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/DataProcessors/Обработка1/Forms/Форма/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Documents/Документ1/Ext/ManagerModule.bsl",
                "src/test/resources/configuration/Documents/Документ1/Ext/ObjectModule.bsl",
                "src/test/resources/configuration/Documents/Документ1/Forms/ФормаДокумента/Ext/Form/Module.bsl",
                "src/test/resources/configuration/Documents/Документ1/Forms/ФормаСписка/Ext/Form/Module.bsl"
        ), readCoveredFiles(output));
    }

    @Test
    void rejectsUnknownSubsystem() {
        Path output = tempDir.resolve("unknown-subsystem.xml");

        int exitCode = convert(new File("src/test/resources/configuration"), UNKNOWN_SUBSYSTEM_CONFIG, output);

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
    }

    @Test
    void reportsMissingSourcesPath() {
        Path output = tempDir.resolve("missing-sources.xml");
        File missingSources = new File("src/test/resources/missing/src/cf");
        StringWriter stderr = new StringWriter();

        int exitCode = convert(missingSources, PARENT_SUBSYSTEM_CONFIG, output, stderr);

        assertEquals(CommandLine.ExitCode.SOFTWARE, exitCode);
        assertTrue(stderr.toString().contains("Sources path does not exist"), stderr.toString());
        assertFalse(stderr.toString().contains("external data processors"), stderr.toString());
    }

    @Test
    void selectsTopLevelSubsystemWhenNestedSubsystemHasTheSameName() throws Exception {
        CF configuration = configurationWithRepeatedSubsystemName();
        Subsystem parent = topLevelSubsystem(configuration);
        Subsystem nested = nestedSubsystem(parent);
        Set<String> selected = SubsystemFilter.resolve(configuration, "ПерваяПодсистема");

        assertEquals(3, selected.size());
        assertTrue(selected.contains(parent.getMdoReference().getMdoRef()));
        assertTrue(selected.contains(nested.getMdoReference().getMdoRef()));
    }

    @Test
    void selectsNestedSubsystemByDottedPathWhenNamesRepeat() throws Exception {
        CF configuration = configurationWithRepeatedSubsystemName();
        Subsystem nested = nestedSubsystem(topLevelSubsystem(configuration));
        Set<String> selected = SubsystemFilter.resolve(configuration, "ПерваяПодсистема.ПерваяПодсистема");

        assertEquals(Set.of(nested.getMdoReference().getMdoRef()), selected);
    }

    private CF configurationWithRepeatedSubsystemName() throws Exception {
        Path root = tempDir.resolve("repeat");
        TestUtils.copyDirectory(Path.of("src/test/resources/edt/pc"), root);
        try (var paths = Files.walk(root)) {
            Path nestedFile = paths.filter(path -> path.getFileName().toString().equals("ПодчиненнаяПодсистема.mdo"))
                    .findFirst().orElseThrow();
            Files.writeString(nestedFile, Files.readString(nestedFile)
                    .replace("<name>ПодчиненнаяПодсистема</name>", "<name>ПерваяПодсистема</name>"));
        }
        return (CF) MDClasses.createConfiguration(root);
    }

    private Subsystem topLevelSubsystem(CF configuration) {
        return configuration.getSubsystems().stream()
                .filter(child -> child.getName().equals("ПерваяПодсистема"))
                .findFirst().orElseThrow();
    }

    private Subsystem nestedSubsystem(Subsystem parent) {
        return parent.getSubsystems().stream()
                .filter(child -> child.getUuid().equals("0fbc4f99-ef89-4d98-9b8f-d26c216c8e31"))
                .findFirst().orElseThrow();
    }

    private int convert(File sourceDir, File configFile, Path output) {
        return convert(sourceDir, configFile, output, new StringWriter());
    }

    private int convert(File sourceDir, File configFile, Path output, StringWriter stderr) {
        String[] arguments = {
                PipeMessages.CONVERT_COMMAND,
                "-P", new File(".").getAbsolutePath(),
                "-s", sourceDir.getPath(),
                "-c", RAW_COVERAGE.getPath(),
                "--config", configFile.getPath(),
                "-o", output.toString()
        };
        CommandLine commandLine = Coverage41C.getCommandLine();
        commandLine.setErr(new PrintWriter(stderr, true));
        return commandLine.execute(arguments);
    }

    private Set<String> readCoveredFiles(Path coverageFile) throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(coverageFile.toFile());
        NodeList files = document.getElementsByTagName("file");
        return IntStream.range(0, files.getLength())
                .mapToObj(files::item)
                .map(node -> node.getAttributes().getNamedItem("path").getTextContent())
                .collect(Collectors.toSet());
    }
}
