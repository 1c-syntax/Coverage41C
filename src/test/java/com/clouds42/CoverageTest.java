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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = NONE, classes = Coverage41C.class)
@DirtiesContext
class CoverageTest {
    @Autowired
    CommandLine.IFactory factory;
    final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

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
    final String v8version = "8.3.20";
    final String buildDirName = "build";

    String vrunnerExecutable = "vrunner";
    String dbgsUrlString;
    URL dbgsUrl;

    CommandLine commandLine;

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
        commandLine = new CommandLine(Coverage41C.class, factory);
        assertEquals(0, commandLine.execute(mainAppHelpArguments));

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
    void testConfigurator() throws InterruptedException, ExecutionException, IOException {
        File configurationSourceDir = new File("src/test/resources/configuration");
        String expectedXmlFileName = "src/test/resources/coverage/configuration.xml";
        commandLine = new CommandLine(Coverage41C.class, factory);
        testCoverage(configurationSourceDir, expectedXmlFileName);
    }

    @Test
    void testEDT() throws InterruptedException, ExecutionException, IOException {
        File edtSourceDir = new File("src/test/resources/edt/pc");
        String expectedEdtXmlFileName = "src/test/resources/coverage/edt.xml";
        commandLine = new CommandLine(Coverage41C.class, factory);
        testCoverage(edtSourceDir, expectedEdtXmlFileName);
    }

    @Test
    void testInternal() throws InterruptedException, ExecutionException, IOException {
        String expectedIntXmlFileName = "src/test/resources/coverage/internal.xml";
        commandLine = new CommandLine(Coverage41C.class, factory);
        testCoverage(null, expectedIntXmlFileName);
    }

    void testCoverage(
            File sourceDir,
            String expectedXmlFileName) throws IOException, InterruptedException, ExecutionException {

        CompletableFuture<Integer> mainAppThread = CompletableFuture.supplyAsync(() -> {
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

            int mainAppReturnCode = commandLine.execute(fullArgsArray);
            return mainAppReturnCode;
        });

        Thread.sleep(1500); // wait for socket server

        String[] mainAppCheckArguments = {
                PipeMessages.CHECK_COMMAND,
                "-i", fileIbName,
                "-u", dbgsUrlString};
        int mainAppCheckResult = commandLine.execute(mainAppCheckArguments);
        assertEquals(0, mainAppCheckResult);

        String[] mainAppCleanArguments = {
                PipeMessages.CLEAN_COMMAND,
                "-i", fileIbName,
                "-u", dbgsUrlString};
        int mainAppCleanResult = commandLine.execute(mainAppCleanArguments);
        assertEquals(0, mainAppCleanResult);

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
        vrunnerVanessaProcess.waitFor();

        Thread.sleep(1500); // wait for measure results

        String[] mainAppDumpArguments = {
                PipeMessages.DUMP_COMMAND,
                "-i", fileIbName,
                "-u", dbgsUrlString};
        int mainAppDumpResult = commandLine.execute(mainAppDumpArguments);
        assertEquals(0, mainAppDumpResult);

        TestUtils.assertCoverageEqual(expectedXmlFileName, outputXmlFileName);

        String[] mainAppStopArguments = {
                PipeMessages.EXIT_COMMAND,
                "-i", fileIbName,
                "-u", dbgsUrlString};
        int mainAppStopResult = commandLine.execute(mainAppStopArguments);
        assertEquals(0, mainAppStopResult);

        assertEquals(0, mainAppThread.get());

        TestUtils.assertCoverageEqual(expectedXmlFileName, outputXmlFileName);

    }

}
