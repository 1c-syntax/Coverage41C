package com.clouds42;

import com.clouds42.Commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "Coverage41C",
        mixinStandardHelpOptions = true,
        version = com.clouds42.BuildConfig.APP_VERSION,
        description = "Make measures from 1C:Enterprise and save them to genericCoverage.xml file",
        sortOptions = false,
        subcommands = {
                CoverageCommand.class,
                SendStopMessageCommand.class,
                SendCheckMessageCommand.class,
                SendCleanMessageCommand.class,
                SendDumpMessageCommand.class,
                SendStatsMessageCommand.class,
                ConvertCommand.class}
                )
public class Coverage41C implements Callable<Integer> {

    public static void main(String[] args) {
        int exitCode = getCommandLine().execute(args);
        System.exit(exitCode);
    }

    public static CommandLine getCommandLine() {
        return new CommandLine(new Coverage41C());
    }

    @Override
    public Integer call() throws Exception {
        CommandLine.usage(this, System.out);
        return 0;
    }
}
