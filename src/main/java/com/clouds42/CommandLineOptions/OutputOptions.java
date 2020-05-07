package com.clouds42.CommandLineOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

import java.io.File;
import java.lang.invoke.MethodHandles;

public class OutputOptions {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = {"-o", "--out"}, description = "Output file name")
    private File outputFile;

    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

}
