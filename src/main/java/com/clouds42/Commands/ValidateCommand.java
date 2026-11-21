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
package com.clouds42.Commands;

import com.clouds42.BuildConfig;
import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = PipeMessages.VALIDATE_COMMAND, mixinStandardHelpOptions = true, version = BuildConfig.APP_VERSION,
        description = "Validate coverage configuration and markers without measuring",
        sortOptions = false)
public class ValidateCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final OutputStream reportOutput;

    public ValidateCommand() {
        this(null);
    }

    public ValidateCommand(OutputStream reportOutput) {
        this.reportOutput = reportOutput;
    }

    @Mixin
    private MetadataOptions metadataOptions;

    @Option(names = "--strict", description = "Fail on warnings. Default - ${DEFAULT-VALUE}", defaultValue = "false")
    private boolean strict;

    @Override
    public Integer call() throws Exception {
        List<String> warnings = Utils.validateMetadata(metadataOptions, reportOutput);
        if (strict && !warnings.isEmpty()) {
            logger.error("Validation failed: {} warning(s) in strict mode", warnings.size());
            return CommandLine.ExitCode.SOFTWARE;
        }
        logger.info("Validation passed, {} warning(s)", warnings.size());
        return CommandLine.ExitCode.OK;
    }
}
