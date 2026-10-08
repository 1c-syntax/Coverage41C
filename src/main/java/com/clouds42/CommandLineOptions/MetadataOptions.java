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
package com.clouds42.CommandLineOptions;

import com.github._1c_syntax.bsl.support.SupportVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MetadataOptions {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = {"-s", "--srcDir"}, description = "Directory with sources exported to xml", defaultValue = "")
    private String srcDirName;

    @Option(names = {"-P", "--projectDir"}, description = "Directory with project", defaultValue = "")
    private String projectDirName;

    @Option(names = {"-r", "--removeSupport"}, description = "Remove support values: ${COMPLETION-CANDIDATES}. Default - ${DEFAULT-VALUE}", defaultValue = "NONE")
    private SupportVariant removeSupport;

    @Option(names = {"--extension"}, paramLabel = "<extensionName>=<path>",
            converter = ExtensionSource.Converter.class,
            description = "Extension name and directory with its sources exported to xml (relative to project directory)." +
                    " Can be repeated. Requires --projectDir")
    private List<ExtensionSource> extensionSources;

    @Spec(Spec.Target.MIXEE)
    private CommandSpec spec;

    // for backward compatibility: -s without -P is the project directory
    private boolean isSrcDirProjectDir() {
        return projectDirName.isEmpty() && !srcDirName.isEmpty();
    }

    public String getSrcDirName() {
        return isSrcDirProjectDir() ? "" : srcDirName;
    }

    public void setSrcDirName(String srcDirName) {
        this.srcDirName = srcDirName;
    }

    public String getProjectDirName() {
        return isSrcDirProjectDir() ? srcDirName : projectDirName;
    }

    public void setProjectDirName(String projectDirName) {
        this.projectDirName = projectDirName;
    }

    public SupportVariant getRemoveSupport() {
        return removeSupport;
    }

    public void setRemoveSupport(SupportVariant removeSupport) {
        this.removeSupport = removeSupport;
    }

    public List<ExtensionSource> getExtensionSources() {
        if (extensionSources == null) {
            return List.of();
        } else {
            return extensionSources;
        }
    }

    public void setExtensionSources(List<ExtensionSource> extensionSources) {
        this.extensionSources = extensionSources;
    }

    public boolean hasExtensionSources(String extensionName) {
        return getExtensionSources().stream()
                .anyMatch(extensionSource -> extensionSource.getName().equals(extensionName));
    }

    /**
     * Проверяет, что деревья расширений можно сочетать с основным деревом исходников
     *
     * @param srcDirExtensionName - расширение, которому принадлежит основное дерево (-e), пустое - конфигурация
     */
    public void validate(String srcDirExtensionName) {
        List<ExtensionSource> sources = getExtensionSources();
        if (sources.isEmpty()) {
            return;
        }
        if (projectDirName.isEmpty()) {
            throw new ParameterException(spec.commandLine(),
                    "Option --extension requires --projectDir: extension paths are relative to it");
        }
        Set<String> names = new HashSet<>();
        if (!srcDirExtensionName.isEmpty()) {
            names.add(srcDirExtensionName);
        }
        for (ExtensionSource source : sources) {
            if (!names.add(source.getName())) {
                throw new ParameterException(spec.commandLine(),
                        String.format("Sources of extension '%s' are specified more than once", source.getName()));
            }
        }
    }

    public boolean isRawMode() {
        return getSrcDirName().isEmpty()
                && getProjectDirName().isEmpty()
                && getExtensionSources().isEmpty();
    }
}
