/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2024
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
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;

@Command
public class ConnectionOptions {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = {"-i", "--infobase"}, description = "InfoBase name. File infobase uses 'DefAlias' name." +
            "  Default - ${DEFAULT-VALUE}", defaultValue = "DefAlias")
    private String infobaseAlias;

    @Option(names = {"-u", "--debugger"}, description = "Debugger url. Default - ${DEFAULT-VALUE}", defaultValue = "http://127.0.0.1:1550/")
    private String debugServerUrl;

    @Option(names = {"-u:file", "--debugger:file"}, description = "Debugger url file name", defaultValue = "")
    private String debugServerUrlFileName;

    public String getInfobaseAlias() {
        return infobaseAlias;
    }

    public void setInfobaseAlias(String infobaseAlias) {
        this.infobaseAlias = infobaseAlias;
    }

    public String getDebugServerUrl() {

        if (!debugServerUrlFileName.isEmpty()) {
            try {
                debugServerUrl =
                        "http://" + Files.lines(Path.of(debugServerUrlFileName)).findFirst().get().trim();
                debugServerUrlFileName = "";
            } catch (IOException e) {
                logger.info(e.getLocalizedMessage());
            }
        }

        return debugServerUrl;
    }

    public void setDebugServerUrl(String debugServerUrl) {
        this.debugServerUrl = debugServerUrl;
    }

}
