package com.clouds42.Commands;

import com.clouds42.PipeMessages;
import picocli.CommandLine.Command;

@Command(name = PipeMessages.CLEAN_COMMAND,
        description = "Clear coverage data in main application",
        sortOptions = false)
public class SendCleanMessageCommand extends SendMessageCommand {
}
