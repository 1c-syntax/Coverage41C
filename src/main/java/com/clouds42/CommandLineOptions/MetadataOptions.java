package com.clouds42.CommandLineOptions;

import com.github._1c_syntax.mdclasses.metadata.additional.SupportVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

import java.lang.invoke.MethodHandles;

public class MetadataOptions {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = {"-s", "--srcDir"}, description = "Directory with sources exported to xml", defaultValue = "")
    private String[] srcDirNames;

    @Option(names = {"-P", "--projectDir"}, description = "Directory with project", defaultValue = "")
    private String projectDirName;

    @Option(names = {"-r", "--removeSupport"}, description = "Remove support values: ${COMPLETION-CANDIDATES}. Default - ${DEFAULT-VALUE}", defaultValue = "NONE")
    private SupportVariant removeSupport;

    private void updatePaths() {
        if (projectDirName.isEmpty() && srcDirNames.length > 0) {
            // for backward compatibility
            projectDirName = srcDirNames[0];
            srcDirNames = new String[]{""};
        }
    }

    public String[] getSrcDirNames() {
        updatePaths();
        return srcDirNames;
    }

    public void setSrcDirNames(String[] srcDirNames) { this.srcDirNames = srcDirNames; }

    public String getProjectDirName() {
        updatePaths();
        return projectDirName;
    }

    public void setProjectDirName(String projectDirName) {
        this.projectDirName = projectDirName;
    }

    public SupportVariant getRemoveSupport() {
        return removeSupport;
    }

    public void setRemoveSupport(SupportVariant removeSupport) {
        this.removeSupport = removeSupport;
    }

    public boolean isRawMode() {
        return getSrcDirNames().length == 0
                && getProjectDirName().isEmpty();
    }
}
