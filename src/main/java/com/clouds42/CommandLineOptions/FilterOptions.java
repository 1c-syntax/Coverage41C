package com.clouds42.CommandLineOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

import java.lang.invoke.MethodHandles;
import java.util.regex.Pattern;

public class FilterOptions {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private String extensionName;
    private String externalDataProcessorUrl;
    private Pattern extensionPattern;
    private Pattern externalDataProcessorUrlPattern;

    @Option(names = {"-e", "--extensionName"}, description = "Extension name", defaultValue = "")
    public void setExtensionName(String extensionName) {
        this.extensionName = extensionName;
        this.extensionPattern = Pattern.compile(extensionName);
    }

    @Option(names = {"-x", "--externalDataProcessor"}, description = "External data processor (or external report) url",
            defaultValue = "")
    public void setExternalDataProcessorUrl(String externalDataProcessorUrl) {
        this.externalDataProcessorUrl = externalDataProcessorUrl;
        this.externalDataProcessorUrlPattern = Pattern.compile(externalDataProcessorUrl);
    }

    public String getExternalDataProcessorUrl() {
        return externalDataProcessorUrl;
    }

    public String getExtensionName() {
        return extensionName;
    }

    public boolean extensionNameMatches(String extensionName) {
        return extensionPattern.matcher(extensionName).matches();
    }

    public boolean externalDataProcessorUrlMatches(String externalDataProcessorUrl) {
        return externalDataProcessorUrlPattern.matcher(externalDataProcessorUrl).matches();
    }
}
