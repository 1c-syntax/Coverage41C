/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2022
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

import com.clouds42.Commands.ConvertCommand;
import com.clouds42.Commands.CoverageCommand;
import com.clouds42.Commands.SendCheckMessageCommand;
import com.clouds42.Commands.SendCleanMessageCommand;
import com.clouds42.Commands.SendDumpMessageCommand;
import com.clouds42.Commands.SendStatsMessageCommand;
import com.clouds42.Commands.SendStopMessageCommand;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IFactory;

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
                ConvertCommand.class
        }
)
@SpringBootApplication
public class Coverage41C implements CommandLineRunner, ExitCodeGenerator {

    private IFactory factory;
    private int exitCode;

    Coverage41C(CommandLine.IFactory factory) {
        this.factory = factory;
    }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(Coverage41C.class, args)));
    }

    @Override
    public void run(String... args) throws Exception {
        exitCode = new CommandLine(this, factory).execute(args);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
