package com.clouds42.Commands;

import com.clouds42.PipeMessages;
import picocli.CommandLine.Command;

@Command(name = PipeMessages.STATS_COMMAND,
        description = "Print coverage statistic report",
        sortOptions = false)
public class SendStatsMessageCommand extends SendMessageCommand {
}
