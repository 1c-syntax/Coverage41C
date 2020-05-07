package com.clouds42.Commands;

import com.clouds42.PipeMessages;
import picocli.CommandLine.Command;

@Command(name = PipeMessages.DUMP_COMMAND,
        description = "Save coverage data to file",
        sortOptions = false)
public class SendDumpMessageCommand extends SendMessageCommand {
}
