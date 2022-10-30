/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2022
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

import com.clouds42.Commands.ConvertCommand;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import picocli.CommandLine;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = NONE, classes = Coverage41C.class)
public class ConvertTest {

    @Autowired
    CommandLine.IFactory factory;

    @Autowired
    ConvertCommand command;

    @Test
    void testConfigurator() {
        File configurationSourceDir = new File("src/test/resources/configuration");

        String expectedIntXmlFileName = "src/test/resources/coverage/internal.xml";
        String expectedConfXmlFileName = "src/test/resources/coverage/configuration.xml";
        String outputXmlFileName = "build/genericCoverageCnvConf.xml";

        String[] mainAppConvertArguments = {
                "-P", new File(".").getAbsolutePath(),
                "-s", configurationSourceDir.getPath(),
                "-c", expectedIntXmlFileName,
                "-o", outputXmlFileName};

        int mainAppConvertResult = new CommandLine(command, factory).execute(mainAppConvertArguments);
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
                "-P", new File(".").getAbsolutePath(),
                "-s", edtSourceDir.getPath(),
                "-c", expectedIntXmlFileName,
                "-o", outputXmlFileName};
        int mainAppConvertEdtResult = new CommandLine(command, factory).execute(mainAppConvertEdtArguments);
        assertEquals(0, mainAppConvertEdtResult);

        TestUtils.assertCoverageEqual(expectedEdtXmlFileName, outputXmlFileName);

    }

}
