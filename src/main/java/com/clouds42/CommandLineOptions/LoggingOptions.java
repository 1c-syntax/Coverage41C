package com.clouds42.CommandLineOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

import java.lang.invoke.MethodHandles;

public class LoggingOptions {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = "--verbose", description = "If you need more logs. Default - ${DEFAULT-VALUE}", defaultValue = "false")
    private Boolean verbose;

    public Boolean isVerbose() {
        return verbose.booleanValue();
    }

    public void setVerbose(Boolean verbose) {
        this.verbose = verbose;
    }
}
