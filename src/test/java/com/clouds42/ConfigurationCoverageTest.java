package com.clouds42;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.ElementSelectors;
import org.xmlunit.matchers.CompareMatcher;
import picocli.CommandLine;

import javax.xml.transform.Source;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigurationCoverageTest {

    @Test
    void testCoverage() throws IOException, InterruptedException, ExecutionException {

        boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

        File configurationSourceDir = new File("src/test/resources/configuration");
        File dtPath = new File("src/test/resources/dt/1Cv8.dt");
        File bddRootDir = new File("src/test/resources/bdd");
        File vbParamsFile = new File("src/test/resources/bdd/VBParams.json");
        File featuresDir = new File("src/test/resources/bdd/features");

        String ibUser = "Администратор";
        String ibPassword = "\\\"\\\"";
        String v8version = "8.3.16";
        String ibConnection = "/Fbuild/ib";
        String fileIbName = "DefAlias";
        String outputXmlFileName = "build/configuration.xml";
        String expectedXmlFileName = "src/test/resources/coverage/configuration.xml";
        String additionalTestManagerArguments = "\\\"/AllowExecuteScheduledJobs -Off\\\"";
        String buildDirName = "build";
        String vrunnerExecutable = "vrunner";
        if (isWindows) {
            vrunnerExecutable += ".bat";
        } else {
            vrunnerExecutable += ".sh";
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Map<String, String> map = mapper.readValue(vbParamsFile, Map.class);
        String dbgsUrlString = map.get("АдресОтладчика");
        URL dbgsUrl = new URL(dbgsUrlString);

        String[] mainAppHelpArguments = {"--help"};
        assertEquals(0, new CommandLine(new Coverage41C()).execute(mainAppHelpArguments));

        Files.walk(Path.of(buildDirName))
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);

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
                "--src", configurationSourceDir.getAbsolutePath(),
                "--dt", dtPath.getAbsolutePath(),
                "--v8version", v8version);
        Process vrunnerInitDevProcess = vrunnerInitDevProcessBuilder.inheritIO().start();
        assertEquals(0, vrunnerInitDevProcess.waitFor());

        CompletableFuture<Integer> mainAppThread = CompletableFuture.supplyAsync(() -> {
            String[] mainAppArguments = {
                    "-i", fileIbName,
                    "-u", dbgsUrlString,
                    "-o", outputXmlFileName,
                    "-P", new File(".").getAbsolutePath(),
                    "-s", configurationSourceDir.getPath()};
            int mainAppReturnCode = new CommandLine(new Coverage41C()).execute(mainAppArguments);
            return mainAppReturnCode;
        });

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

        String[] mainAppDumpArguments = {
                "-i", fileIbName,
                "-u", dbgsUrlString,
                "-a", "dump"};
        int mainAppDumpResult = new CommandLine(new Coverage41C()).execute(mainAppDumpArguments);
        assertEquals(0, mainAppDumpResult);

        String[] mainAppStopArguments = {
                "-i", fileIbName,
                "-u", dbgsUrlString,
                "-a", "stop"};
        int mainAppStopResult = new CommandLine(new Coverage41C()).execute(mainAppStopArguments);
        assertEquals(0, mainAppStopResult);

        assertEquals(0, mainAppThread.get());

        Source expectedXml = Input.fromFile(expectedXmlFileName).build();
        Source controlXml = Input.fromFile(outputXmlFileName).build();
        assertThat(
                expectedXml,
                CompareMatcher.isSimilarTo(controlXml)
                .withNodeMatcher(new DefaultNodeMatcher(ElementSelectors.byNameAndAllAttributes)));

    }

}