/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2024
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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConvertTest {

    @Test
    void testConfigurator() {
        File configurationSourceDir = new File("src/test/resources/configuration");

        String expectedIntXmlFileName = "src/test/resources/coverage/internal.xml";
        String expectedConfXmlFileName = "src/test/resources/coverage/configuration.xml";
        String outputXmlFileName = "build/genericCoverageCnvConf.xml";

        String[] mainAppConvertArguments = {
                PipeMessages.CONVERT_COMMAND,
                "-P", new File(".").getAbsolutePath(),
                "-s", configurationSourceDir.getPath(),
                "-c", expectedIntXmlFileName,
                "-o", outputXmlFileName};
        int mainAppConvertResult = Coverage41C.getCommandLine().execute(mainAppConvertArguments);
        assertEquals(0, mainAppConvertResult);

        TestUtils.assertCoverageEqual(expectedConfXmlFileName, outputXmlFileName);

    }

    @Test
    void testEdt() {
        File edtSourceDir = new File("src/test/resources/edt/pc");

        String expectedIntXmlFileName = "src/test/resources/coverage/internal.xml";
        String expectedEdtXmlFileName = "src/test/resources/coverage/edt.xml";
        String outputXmlFileName = "build/genericCoverageCnvEdt.xml";

        String[] mainAppConvertEdtArguments = {
                PipeMessages.CONVERT_COMMAND,
                "-P", new File(".").getAbsolutePath(),
                "-s", edtSourceDir.getPath(),
                "-c", expectedIntXmlFileName,
                "-o", outputXmlFileName};
        int mainAppConvertEdtResult = Coverage41C.getCommandLine().execute(mainAppConvertEdtArguments);
        assertEquals(0, mainAppConvertEdtResult);

        TestUtils.assertCoverageEqual(expectedEdtXmlFileName, outputXmlFileName);

    }

    // Выгрузка конфигурации и двух расширений со стенда 8.3.27, сырые замеры сняты на нём же
    private static final File EXTENSIONS_PROJECT_DIR = new File("src/test/resources/extensions");
    private static final String EXTENSIONS_COVERAGE_DIR = "src/test/resources/extensions/coverage/";

    private static int convertExtensions(String rawXmlFileName, String outputXmlFileName, String... sourcesArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                PipeMessages.CONVERT_COMMAND,
                "-P", EXTENSIONS_PROJECT_DIR.getAbsolutePath(),
                "-c", EXTENSIONS_COVERAGE_DIR + rawXmlFileName,
                "-o", outputXmlFileName));
        arguments.addAll(List.of(sourcesArguments));
        return Coverage41C.getCommandLine().execute(arguments.toArray(new String[0]));
    }

    @Test
    void testConfigurationWithExtensions() {
        String outputXmlFileName = "build/genericCoverageCnvExtensions.xml";

        int result = convertExtensions("internal.xml", outputXmlFileName,
                "-s", "src/cf",
                "--extension", "Доработки=src/cfe/Доработки",
                "--extension", "Второе=src/cfe/Второе");
        assertEquals(0, result);

        // совпадает с отдельными замерами 2.7.3: -s src/cf, -e Доработки -s src/cfe/Доработки, -e Второе -s src/cfe/Второе
        TestUtils.assertCoverageEqual(EXTENSIONS_COVERAGE_DIR + "all.xml", outputXmlFileName);
    }

    @Test
    void testExtensionSourcesAsSrcDir() {
        // как раньше: без --extension атрибут extension не учитывается, -s может указывать на расширение
        String outputXmlFileName = "build/genericCoverageCnvExtensionSrcDir.xml";

        int result = convertExtensions("internal.xml", outputXmlFileName,
                "-s", "src/cfe/Доработки");
        assertEquals(0, result);

        TestUtils.assertCoverageEqual(EXTENSIONS_COVERAGE_DIR + "Доработки.xml", outputXmlFileName);
    }

    @Test
    void testRawFileOfPreviousVersion() {
        // сырой файл 2.7.3 без атрибута extension: все модули считаются модулями конфигурации
        String outputXmlFileName = "build/genericCoverageCnvPreviousVersion.xml";

        int result = convertExtensions("internal-2.7.3.xml", outputXmlFileName,
                "-s", "src/cf");
        assertEquals(0, result);
        TestUtils.assertCoverageEqual(EXTENSIONS_COVERAGE_DIR + "configuration.xml", outputXmlFileName);

        result = convertExtensions("internal-2.7.3.xml", outputXmlFileName,
                "-s", "src/cf",
                "--extension", "Доработки=src/cfe/Доработки",
                "--extension", "Второе=src/cfe/Второе");
        assertEquals(0, result);
        TestUtils.assertCoverageEqual(EXTENSIONS_COVERAGE_DIR + "all-from-2.7.3.xml", outputXmlFileName);
    }

    @Test
    void testSameObjectIdInExtensions() {
        // один и тот же идентификатор объекта в расширениях Доработки, Второе и в конфигурации:
        // покрытие попадает только в модуль своего расширения
        String outputXmlFileName = "build/genericCoverageCnvSameObjectId.xml";

        int result = convertExtensions("internal-same-id.xml", outputXmlFileName,
                "-s", "src/cf",
                "--extension", "Доработки=src/cfe/Доработки",
                "--extension", "Второе=src/cfe/Второе");
        assertEquals(0, result);

        TestUtils.assertCoverageEqual(EXTENSIONS_COVERAGE_DIR + "same-id.xml", outputXmlFileName);
    }

}
