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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoverageTest {

    final boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

    final File dtPath = new File("src/test/resources/dt/1Cv8.dt");
    final File vbParamsFile = new File("src/test/resources/bdd/VBParams.json");
    final File sourceDir = new File("src/test/resources/configuration");

    final File bddRootDir = new File("src/test/resources/bdd");
    final File featuresDir = new File("src/test/resources/bdd/features");

    final String ibConnection = "/Fbuild/ib";
    final String fileIbName = "DefAlias";
    final String outputXmlFileName = "build/genericCoverage.xml";
    final String additionalTestManagerArguments = "\\\"/AllowExecuteScheduledJobs -Off\\\"";

    final String ibUser = "Администратор";
    final String ibPassword = "\\\"\\\"";
    final String v8version = "8.3.18";
    final String buildDirName = "build";

    String vrunnerExecutable = "vrunner";
    String dbgsUrlString;
    URL dbgsUrl;

    @BeforeAll
    public void prepareIb() throws IOException, InterruptedException {

        if (isWindows) {
            vrunnerExecutable += ".bat";
        } else {
            vrunnerExecutable += ".sh";
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Map<String, String> map = mapper.readValue(vbParamsFile, Map.class);
        dbgsUrlString = map.get("АдресОтладчика");
        dbgsUrl = new URL(dbgsUrlString);

        String[] mainAppHelpArguments = {"--help"};
        assertEquals(0, Coverage41C.getCommandLine().execute(mainAppHelpArguments));

        Path ibPath = Path.of(buildDirName, "ib");
        if (Files.exists(ibPath)) {
            Files.walk(ibPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }

        long pid = ProcessHandle.current().pid();

        ProcessBuilder startDbgsProcessBuilder = new ProcessBuilder();
        startDbgsProcessBuilder.command(
                "oscript", "src/test/resources/scripts/StartDbgs.os",
                "--opid", String.valueOf(pid),
                "--port", String.valueOf(dbgsUrl.getPort()),
                "--host", dbgsUrl.getHost(),
                "--v8version", v8version);
        Process startDbgsProcess = startDbgsProcessBuilder.inheritIO().start();
        assertEquals(0, startDbgsProcess.waitFor());

        ProcessBuilder vrunnerInitDevProcessBuilder = new ProcessBuilder();
        vrunnerInitDevProcessBuilder.command(vrunnerExecutable, "init-dev",
                "--db-user", ibUser,
                "--db-pwd", ibPassword,
                "--src", sourceDir.getAbsolutePath(),
                "--dt", dtPath.getAbsolutePath(),
                "--v8version", v8version);
        Process vrunnerInitDevProcess = vrunnerInitDevProcessBuilder.inheritIO().start();
        assertEquals(0, vrunnerInitDevProcess.waitFor());
    }

    @Test
    void testConfigurator() throws Exception {
        File configurationSourceDir = new File("src/test/resources/configuration");
        String expectedXmlFileName = "src/test/resources/coverage/configuration.xml";
        testCoverage(configurationSourceDir, expectedXmlFileName);
    }

    @Test
    void testEDT() throws Exception {
        File edtSourceDir = new File("src/test/resources/edt/pc");
        String expectedEdtXmlFileName = "src/test/resources/coverage/edt.xml";
        testCoverage(edtSourceDir, expectedEdtXmlFileName);
    }

    @Test
    void testInternal() throws Exception {
        String expectedRawXmlFileName = "src/test/resources/coverage/start-raw.xml";
        testCoverage(null, expectedRawXmlFileName);
    }

    void testCoverage(File sourceDir, String expectedXmlFileName) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var collector = executor.submit(() -> {
            String[] argsArray = {
                    PipeMessages.START_COMMAND,
                    "-i", fileIbName,
                    "-u", dbgsUrlString,
                    "-o", outputXmlFileName};
            List<String> mainAppArguments = new LinkedList<>();
            mainAppArguments.addAll(Arrays.asList(argsArray));
            if (sourceDir != null) {
                String[] additionalArgsArray = {
                        "-P", new File(".").getAbsolutePath(),
                        "-s", sourceDir.getPath()};
                mainAppArguments.addAll(Arrays.asList(additionalArgsArray));
            }
            String[] fullArgsArray = mainAppArguments.toArray(new String[mainAppArguments.size()]);
            int mainAppReturnCode = Coverage41C.getCommandLine().execute(fullArgsArray);
            return mainAppReturnCode;
        });

        int stopResult;
        int collectorResult;
        try {
            Thread.sleep(1500); // wait for socket server
            assertEquals(0, send(PipeMessages.CHECK_COMMAND));
            assertEquals(0, send(PipeMessages.CLEAN_COMMAND));

            ProcessBuilder vrunnerVanessaProcessBuilder = new ProcessBuilder();
            vrunnerVanessaProcessBuilder.command(vrunnerExecutable, "vanessa",
                    "--root", bddRootDir.getAbsolutePath(),
                    "--path", featuresDir.getAbsolutePath(),
                    "--ibconnection", ibConnection,
                    "--db-user", ibUser,
                    "--db-pwd", ibPassword,
                    "--v8version", v8version,
                    "--additional", additionalTestManagerArguments,
                    "--vanessasettings", vbParamsFile.getAbsolutePath());
            Process vrunnerVanessaProcess = vrunnerVanessaProcessBuilder.inheritIO().start();
            assertEquals(0, vrunnerVanessaProcess.waitFor(), "Vanessa scenario failed");

            Thread.sleep(1500); // wait for measure results
            assertEquals(0, send(PipeMessages.DUMP_COMMAND));
            var coverage = Utils.newInputXmlBuilder().parse(new File(outputXmlFileName)).getDocumentElement();
            assertEquals("coverage", coverage.getTagName());
            assertEquals("1", coverage.getAttribute("version"));
        } finally {
            try {
                stopResult = send(PipeMessages.EXIT_COMMAND);
                collectorResult = collector.get(30, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }

        assertEquals(0, stopResult);
        assertEquals(0, collectorResult);
        TestUtils.assertCoverageEqual(expectedXmlFileName, outputXmlFileName);
    }

    private int send(String command) {
        return Coverage41C.getCommandLine().execute(command, "-i", fileIbName, "-u", dbgsUrlString);
    }
}
