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
package com.clouds42.CommandLineOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

import java.io.File;
import java.lang.invoke.MethodHandles;

public class OutputOptions {

    public enum OutputFormat {
        GENERIC_COVERAGE,
        LCOV,
        COBERTURA
    }

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = {"-o", "--out"}, description = "Output file name")
    private File outputFile;

    @Option(names = {"-f", "--format"}, description = "Output file format: ${COMPLETION-CANDIDATES}. Default - ${DEFAULT-VALUE}", defaultValue = "GENERIC_COVERAGE")
    private OutputFormat outputFormat;

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(OutputFormat outputFormat) {
        this.outputFormat = outputFormat;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }


}
