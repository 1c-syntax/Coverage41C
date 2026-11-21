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

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public class MetadataOptions {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Option(names = {"-s", "--srcDir"}, description = "Directory with sources exported to xml", defaultValue = "")
    private String srcDirName;

    @Option(names = {"-P", "--projectDir"}, description = "Directory with project", defaultValue = "")
    private String projectDirName;

    @Option(names = {"-r", "--removeSupport"}, description = "Remove support values: ${COMPLETION-CANDIDATES}. Default - ${DEFAULT-VALUE}", defaultValue = "NONE")
    private SupportVariant removeSupport;

    @Option(names = "--config", description = "Coverage configuration file or directory with *.json files")
    private File configFile;

    @Option(names = "--exclusion-report", description = "Write coverage exclusion report to JSON file")
    private File exclusionReportFile;

    @Option(names = "--source", paramLabel = "NAME=DIR",
            description = "Source directory relative to -P; repeat for each extension (empty name for main configuration)")
    private List<String> sourceDirectories = new ArrayList<>();

    @Option(names = "--extension", paramLabel = "<extensionName>=<path>",
            converter = ExtensionSource.Converter.class,
            description = "Extension source directory relative to -P. Can be repeated.")
    private List<ExtensionSource> extensionSources = new ArrayList<>();

    @Spec(Spec.Target.MIXEE)
    private CommandSpec spec;

    public boolean usesNamedSources() {
        return !sourceDirectories.isEmpty();
    }

    public boolean hasExtensionSources(String extensionName) {
        return extensionSources.stream().anyMatch(source -> source.getName().equals(extensionName));
    }

    public boolean hasMultipleSources() {
        return usesNamedSources() || !extensionSources.isEmpty();
    }

    public void validateSourceFilters(String extension, String url) {
        if (usesNamedSources() && (!extension.isEmpty() || !url.isEmpty())) {
            throw new IllegalArgumentException("--source cannot be combined with -e or -x");
        }
        if (!extensionSources.isEmpty()) {
            if (usesNamedSources()) {
                throw new IllegalArgumentException("--source cannot be combined with --extension");
            }
            if (projectDirName.isEmpty()) {
                throw new ParameterException(spec.commandLine(), "--extension requires --projectDir");
            }
            Set<String> names = new HashSet<>();
            names.add(extension);
            for (ExtensionSource source : extensionSources) {
                if (!names.add(source.getName())) {
                    throw new ParameterException(spec.commandLine(), "Sources of extension '" + source.getName()
                            + "' are specified more than once");
                }
            }
        }
    }

    public Map<String, Path> getSourceDirectories(String mainExtension) throws IOException {
        if (!usesNamedSources()) {
            Path project = Path.of(getProjectDirName());
            Map<String, Path> directories = new LinkedHashMap<>();
            directories.put(mainExtension, project.resolve(getSrcDirName()));
            for (ExtensionSource extension : extensionSources) {
                directories.put(extension.getName(), project.resolve(extension.getPath()));
            }
            if (!extensionSources.isEmpty()) {
                List<Path> physicalSources = new ArrayList<>();
                for (Map.Entry<String, Path> source : directories.entrySet()) {
                    Path path = source.getValue();
                    // -s may point to an external data processor descriptor.
                    boolean isMainSource = source.getKey().equals(mainExtension);
                    if (!Files.isDirectory(path) && (!isMainSource || !Files.isRegularFile(path))) {
                        throw new IllegalArgumentException("Multiple sources require a directory: " + path);
                    }
                    rejectDuplicateSourceDirectory(physicalSources, path);
                    physicalSources.add(path);
                }
            }
            return directories;
        }
        if (projectDirName.isEmpty()) {
            throw new IllegalArgumentException("--source requires --projectDir");
        }
        if (!srcDirName.isEmpty()) {
            throw new IllegalArgumentException("--source cannot be combined with -s");
        }
        Path project = Path.of(projectDirName).toRealPath();
        projectDirName = project.toString();
        Map<String, Path> result = new LinkedHashMap<>();
        for (String source : sourceDirectories) {
            int separator = source.indexOf('=');
            if (separator < 0 || separator == source.length() - 1) {
                throw new IllegalArgumentException("Expected --source NAME=DIR (empty name for main configuration): " + source);
            }
            String name = source.substring(0, separator);
            Path directory = project.resolve(source.substring(separator + 1)).toRealPath();
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("--source requires a configuration or extension directory: " + directory);
            }
            if (!directory.startsWith(project)) {
                throw new IllegalArgumentException("Source directory must be inside -P: " + directory);
            }
            if (result.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate source name: " + name);
            }
            rejectDuplicateSourceDirectory(result.values(), directory);
            result.put(name, directory);
        }
        return result;
    }

    private static void rejectDuplicateSourceDirectory(Iterable<Path> existingDirectories, Path directory)
            throws IOException {
        for (Path existingDirectory : existingDirectories) {
            if (Files.isSameFile(existingDirectory, directory)) {
                throw new IllegalArgumentException("Duplicate source directory: " + directory);
            }
        }
    }

    private void updatePaths() {
        if (hasMultipleSources()) {
            return;
        }
        if (projectDirName.isEmpty() && !srcDirName.isEmpty()) {
            Path source = Path.of(srcDirName);
            if (Files.isRegularFile(source)) {
                Path descriptor = source.toAbsolutePath();
                if (Files.isSymbolicLink(descriptor)) {
                    try {
                        projectDirName = descriptor.toRealPath().getParent().toString();
                    } catch (IOException error) {
                        throw new IllegalArgumentException("Cannot resolve source file: " + descriptor, error);
                    }
                    // Keep the supplied path as an input even though report paths use the physical directory.
                    srcDirName = descriptor.toString();
                } else {
                    projectDirName = descriptor.getParent().toString();
                    srcDirName = descriptor.getFileName().toString();
                }
            } else {
                // With only -s, a directory defines both project and source roots.
                projectDirName = srcDirName;
                srcDirName = "";
            }
        }
    }

    public String getSrcDirName() {
        updatePaths();
        return srcDirName;
    }

    public String getProjectDirName() {
        updatePaths();
        return projectDirName;
    }

    public SupportVariant getRemoveSupport() {
        return removeSupport;
    }

    public File getConfigFile() {
        return configFile;
    }

    public File getExclusionReportFile() {
        return exclusionReportFile;
    }

    public boolean isRawMode() {
        return !hasMultipleSources() && getSrcDirName().isEmpty()
                && getProjectDirName().isEmpty();
    }

    public void setSrcDirName(String srcDirName) {
        this.srcDirName = srcDirName;
    }

    public void setProjectDirName(String projectDirName) {
        this.projectDirName = projectDirName;
    }

    public void setRemoveSupport(SupportVariant removeSupport) {
        this.removeSupport = removeSupport;
    }

    public void setExtensionSources(List<ExtensionSource> extensionSources) {
        this.extensionSources = extensionSources;
    }
}
