package com.clouds42.Commands;

import com.clouds42.PipeMessages;
import picocli.CommandLine.Command;

@Command(name = PipeMessages.EXIT_COMMAND,
        description = "Stop main application and save coverage to file",
        sortOptions = false)
public class SendStopMessageCommand extends SendMessageCommand {
}
