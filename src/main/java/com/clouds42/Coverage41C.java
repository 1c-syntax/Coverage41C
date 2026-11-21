/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2026
 * Kosolapov Stanislav aka proDOOMman <prodoomman@gmail.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * Coverage41C is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * Coverage41C is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Coverage41C.
 */
package com.clouds42;

import com.clouds42.Commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Objects;
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
                ConvertCommand.class,
                ValidateCommand.class}
                )
public class Coverage41C implements Callable<Integer> {

    public static void main(String[] args) {
        int exitCode = getCommandLine(StandardOutputFile.PROCESS_OUTPUT).execute(args);
        System.exit(exitCode);
    }

    public static CommandLine getCommandLine() {
        return new CommandLine(new Coverage41C());
    }

    /** Uses a caller-owned stream for start coverage and for convert and validate reports directed to stdout. */
    public static CommandLine getCommandLine(OutputStream reportOutput) {
        Objects.requireNonNull(reportOutput, "reportOutput");
        if (reportOutput instanceof PrintStream) {
            throw new IllegalArgumentException("Pass the underlying OutputStream so write errors propagate");
        }
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        return new CommandLine(new Coverage41C(), new CommandLine.IFactory() {
            @Override
            public <T> T create(Class<T> type) throws Exception {
                if (type == CoverageCommand.class) {
                    return type.cast(new CoverageCommand() {
                        @Override
                        protected OutputStream coverageOutput() {
                            return reportOutput;
                        }
                    });
                }
                if (type == ConvertCommand.class) {
                    return type.cast(new ConvertCommand(reportOutput));
                }
                if (type == ValidateCommand.class) {
                    return type.cast(new ValidateCommand(reportOutput));
                }
                return defaults.create(type);
            }
        });
    }

    @Override
    public Integer call() throws Exception {
        CommandLine.usage(this, System.out);
        return 0;
    }
}
