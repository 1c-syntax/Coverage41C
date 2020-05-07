package com.clouds42.CommandLineOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

import java.io.File;
import java.lang.invoke.MethodHandles;

public class ConvertOptions {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = {"-c", "--convertFile"}, description = "Input file name with RAW xml coverage data", required = true)
    private File inputRawXmlFile;

    public File getInputRawXmlFile() {
        return inputRawXmlFile;
    }

    public void setInputRawXmlFile(File inputRawXmlFile) {
        this.inputRawXmlFile = inputRawXmlFile;
    }
}
