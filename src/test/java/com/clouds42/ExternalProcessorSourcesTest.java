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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExternalProcessorSourcesTest {
    private static final String FORM_ID = "a715d335-6c96-4c86-b540-00c3aaa18375";
    private static final String SOURCE = "Процедура Пример()\nШаг();\nКонецПроцедуры\n";
    @TempDir Path directory;

    @Test
    void reportsCannotCreateAnExpectedButOmittedModule() throws Exception {
        for (boolean edt : List.of(false, true)) {
            for (boolean formModule : List.of(false, true)) {
                Processor processor = processor(edt, "collision-" + formModule);
                Path reserved = processor.objectModule();
                if (formModule) {
                    processor.form("Main", FORM_ID, "Main");
                    reserved = processor.module("Main");
                    Files.createDirectories(reserved.getParent());
                } else {
                    Files.delete(reserved);
                }
                assertFalse(Files.exists(reserved));
                assertEquals(1, execute("validate", "-s", processor.descriptor().toString(),
                        "--exclusion-report", reserved.toString()));
                assertFalse(Files.exists(reserved), "validation must not create source text");
                Path raw = Files.writeString(processor.base().resolve("raw.xml"), "<coverage/>");
                Path report = processor.base().resolve("report.json");
                assertEquals(1, execute("convert", "-s", processor.descriptor().toString(), "-c", raw.toString(),
                        "-o", reserved.toString(), "--exclusion-report", report.toString()));
                assertFalse(Files.exists(reserved));
                assertFalse(Files.exists(report));
            }
        }
    }

    @Test
    void missingModuleCollisionIsDetectedThroughASymbolicParent() throws Exception {
        Processor processor = processor(true, "linked-collision");
        Files.delete(processor.objectModule());
        Path link = directory.resolve("linked-source");
        symlink(link, processor.base());
        Path reserved = link.resolve("ObjectModule.bsl");
        assertEquals(1, execute("validate", "-s", processor.descriptor().toString(),
                "--exclusion-report", reserved.toString()));
        assertFalse(Files.exists(processor.objectModule()));
    }

    @Test
    void omittedObjectAndFormModulesDoNotProduceReadErrorsInEitherMode() throws Exception {
        for (boolean edt : List.of(false, true)) {
            Processor processor = processor(edt, "omitted");
            Files.delete(processor.objectModule());
            processor.form("Main", FORM_ID, "Main");
            Path raw = Files.writeString(processor.base().resolve("raw.xml"), "<coverage/>");
            var original = System.err;
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            try (PrintStream err = new PrintStream(log, true, StandardCharsets.UTF_8)) {
                System.setErr(err);
                assertEquals(0, execute("convert", "-s", processor.descriptor().toString(), "-c", raw.toString(),
                        "-o", processor.base().resolve("coverage.xml").toString()));
            } finally {
                System.setErr(original);
            }
            assertFalse(log.toString(StandardCharsets.UTF_8).contains("cannot read module"), log.toString());
            Path report = processor.base().resolve("report.json");
            assertEquals(0, execute("validate", "-s", processor.descriptor().toString(), "--strict",
                    "--exclusion-report", report.toString()));
            assertTrue(new ObjectMapper().readTree(report.toFile()).path("warnings").isEmpty());
        }
    }

    @Test
    void existingBrokenModulesStillProduceReadErrors() throws Exception {
        for (boolean edt : List.of(false, true)) {
            Processor processor = processor(edt, "broken");
            Files.write(processor.objectModule(), new byte[]{(byte) 0xc3, 0x28});
            Path raw = Files.writeString(processor.base().resolve("raw.xml"), "<coverage/>");
            var original = System.err;
            ByteArrayOutputStream log = new ByteArrayOutputStream();
            try (PrintStream err = new PrintStream(log, true, StandardCharsets.UTF_8)) {
                System.setErr(err);
                assertEquals(0, execute("convert", "-s", processor.descriptor().toString(), "-c", raw.toString(),
                        "-o", processor.base().resolve("coverage.xml").toString()));
            } finally {
                System.setErr(original);
            }
            assertTrue(log.toString(StandardCharsets.UTF_8).contains("cannot read module"), log.toString());
            assertEquals(1, execute("validate", "-s", processor.descriptor().toString(), "--strict"));
        }
    }

    @Test
    void formNamesMustBeSinglePortablePathComponents() throws Exception {
        int variant = 0;
        for (boolean edt : List.of(false, true)) {
            for (String name : List.of(".", "..", "Nested/Name", "Nested\\Name", "C:Outside", "/outside")) {
                Processor processor = processor(edt, "name-" + variant++);
                processor.form("Main", FORM_ID, name);
                List<String> problems = new ArrayList<>();
                var modules = Utils.discoverExternalDataProcessorSources(processor.descriptor(), ignored -> {}, problems::add);
                assertFalse(modules.containsKey(FORM_ID + "/32e087ab-1491-49b6-aba7-43571b41ac2b"), name);
                assertTrue(problems.stream().anyMatch(problem -> problem.contains(name)), problems.toString());
                assertEquals(1, execute("validate", "-s", processor.descriptor().toString(), "--strict"));
            }
        }
    }

    @Test
    void formNameCannotReadAModuleOutsideForms() throws Exception {
        for (boolean edt : List.of(false, true)) {
            Processor processor = processor(edt, "escape");
            Path outside = processor.module("../Outside");
            Files.createDirectories(outside.getParent());
            Files.writeString(outside, "// Cover:on\n" + SOURCE);
            processor.form("Main", FORM_ID, "../Outside");
            List<String> problems = new ArrayList<>();
            var modules = Utils.discoverExternalDataProcessorSources(processor.descriptor(), ignored -> {}, problems::add);
            assertFalse(modules.values().contains(outside.toUri()));
            assertTrue(problems.stream().anyMatch(problem -> problem.contains("../Outside")), problems.toString());
        }
    }

    @Test
    void aSymbolicFormDirectoryCannotEscapeForms() throws Exception {
        for (boolean edt : List.of(false, true)) {
            Processor processor = processor(edt, "symbolic-escape");
            Path outside = Files.createDirectory(processor.base().resolve("Outside"));
            Path escapedModule = outside.resolve(edt ? "Module.bsl" : "Ext/Form/Module.bsl");
            Files.createDirectories(escapedModule.getParent());
            Files.writeString(escapedModule, SOURCE);
            symlink(processor.forms().resolve("Main"), outside);
            processor.form("Main", FORM_ID, "Main");
            List<String> problems = new ArrayList<>();
            var modules = Utils.discoverExternalDataProcessorSources(processor.descriptor(), ignored -> {}, problems::add);
            assertFalse(modules.containsKey(FORM_ID + "/32e087ab-1491-49b6-aba7-43571b41ac2b"));
            assertTrue(problems.stream().anyMatch(problem -> problem.contains("Main")), problems.toString());
        }
    }

    @Test
    void symbolicPathsThatStayWithinFormsRemainUsable() throws Exception {
        for (boolean edt : List.of(false, true)) {
            Processor processor = processor(edt, "symbolic-inside");
            Path realModule = processor.module("Real");
            Files.createDirectories(realModule.getParent());
            Files.writeString(realModule, SOURCE);
            symlink(processor.forms().resolve("Main"), Path.of("Real"));
            processor.form("Main", FORM_ID, "Main");
            List<String> problems = new ArrayList<>();
            var modules = Utils.discoverExternalDataProcessorSources(processor.descriptor(), ignored -> {}, problems::add);
            assertEquals(processor.module("Main").toUri(), modules.get(FORM_ID + "/32e087ab-1491-49b6-aba7-43571b41ac2b"));
            assertTrue(problems.isEmpty(), problems.toString());
            assertEquals(0, execute("validate", "-s", processor.descriptor().toString(), "--strict"));
        }
    }

    @Test
    void duplicateFormIdsAreReportedInsteadOfSilentlyReplacingAModule() throws Exception {
        for (boolean edt : List.of(false, true)) {
            Processor processor = processor(edt, "duplicate");
            for (String name : List.of("First", "Second")) {
                Path module = processor.module(name);
                Files.createDirectories(module.getParent());
                Files.writeString(module, SOURCE);
            }
            processor.form("First", FORM_ID, "First");
            processor.form("Second", FORM_ID.toUpperCase(java.util.Locale.ROOT), "Second");
            List<String> problems = new ArrayList<>();
            var modules = Utils.discoverExternalDataProcessorSources(processor.descriptor(), ignored -> {}, problems::add);
            assertTrue(problems.stream().anyMatch(problem -> problem.contains("First") && problem.contains("Second")),
                    problems.toString());
            assertEquals(2, modules.size());
            if (edt) {
                assertEquals(processor.module("First").toUri(),
                        modules.get(FORM_ID + "/32e087ab-1491-49b6-aba7-43571b41ac2b"));
            }
            Path report = processor.base().resolve("report.json");
            assertEquals(1, execute("validate", "-s", processor.descriptor().toString(), "--strict",
                    "--exclusion-report", report.toString()));
            assertTrue(new ObjectMapper().readTree(report.toFile()).path("warnings").toString().contains("First"));
        }
    }

    @Test
    void distinctFormIdsKeepBothModules() throws Exception {
        for (boolean edt : List.of(false, true)) {
            Processor processor = processor(edt, "distinct");
            processor.form("First", FORM_ID, "First");
            processor.form("Second", "019b1538-8b9a-477b-a934-69a717c3e580", "Second");
            List<String> problems = new ArrayList<>();
            var modules = Utils.discoverExternalDataProcessorSources(processor.descriptor(), ignored -> {}, problems::add);
            assertEquals(3, modules.size());
            assertTrue(problems.isEmpty(), problems.toString());
        }
    }

    private Processor processor(boolean edt, String suffix) throws Exception {
        Path base = Files.createDirectory(directory.resolve((edt ? "edt-" : "xml-") + suffix));
        Path forms = Files.createDirectories(base.resolve(edt ? "Forms" : "Sample/Forms"));
        Path object = base.resolve(edt ? "ObjectModule.bsl" : "Sample/Ext/ObjectModule.bsl");
        Files.createDirectories(object.getParent());
        Files.writeString(object, SOURCE);
        Path descriptor = Files.writeString(base.resolve(edt ? "Sample.mdo" : "Sample.xml"), edt
                ? "<mdclass:ExternalDataProcessor xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\" uuid=\"object-id\"></mdclass:ExternalDataProcessor>"
                : "<MetaDataObject><ExternalDataProcessor uuid=\"object-id\"/></MetaDataObject>");
        return new Processor(base, descriptor, object, forms, edt);
    }

    private record Processor(Path base, Path descriptor, Path objectModule, Path forms, boolean edt) {
        Path module(String name) { return forms.resolve(name).resolve(edt ? "Module.bsl" : "Ext/Form/Module.bsl"); }
        void form(String file, String uuid, String name) throws Exception {
            String escaped = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            if (edt) {
                String end = "</mdclass:ExternalDataProcessor>";
                String element = "<forms uuid=\"" + uuid + "\"><name>" + escaped + "</name></forms>";
                Files.writeString(descriptor, Files.readString(descriptor).replace(end, element + end));
            } else {
                Files.writeString(forms.resolve(file + ".xml"), "<MetaDataObject><Form uuid=\"" + uuid
                        + "\"><Properties><Name>" + escaped + "</Name></Properties></Form></MetaDataObject>");
            }
        }
    }

    private static int execute(String... arguments) { return Coverage41C.getCommandLine().execute(arguments); }
    private static void symlink(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.io.IOException e) { assumeTrue(false, "Symbolic links unavailable: " + e); }
    }
}
