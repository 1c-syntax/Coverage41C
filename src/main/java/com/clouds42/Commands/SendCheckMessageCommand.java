package com.clouds42.Commands;

import com.clouds42.PipeMessages;
import picocli.CommandLine.Command;

@Command(name = PipeMessages.CHECK_COMMAND,
        description = "Check is main application ready",
        sortOptions = false)
public class SendCheckMessageCommand extends SendMessageCommand {
}
