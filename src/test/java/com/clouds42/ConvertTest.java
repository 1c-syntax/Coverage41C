package com.clouds42;

import org.junit.jupiter.api.Test;

import java.io.File;

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

}
