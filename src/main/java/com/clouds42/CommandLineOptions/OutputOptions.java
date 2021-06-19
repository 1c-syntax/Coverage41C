package com.clouds42.CommandLineOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

import java.io.File;
import java.lang.invoke.MethodHandles;

public class OutputOptions {

    public enum OutputFormat {
        GENERIC_COVERAGE,
        LCOV,
        COBERTURA
    }

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = {"-o", "--out"}, description = "Output file name")
    private File outputFile;

    @Option(names = {"-f", "--format"}, description = "Output file format: ${COMPLETION-CANDIDATES}. Default - ${DEFAULT-VALUE}", defaultValue = "GENERIC_COVERAGE")
    private OutputFormat outputFormat;

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(OutputFormat outputFormat) {
        this.outputFormat = outputFormat;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }


}
