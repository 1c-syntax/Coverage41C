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

import org.w3c.dom.Element;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.ElementSelector;
import org.xmlunit.diff.ElementSelectors;
import org.xmlunit.matchers.CompareMatcher;

import javax.xml.transform.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestUtils {

    public static final Path CONFIGURATION = Path.of("src/test/resources/configuration");

    // Копирует тестовую конфигурацию в <directory>/project/configuration, чтобы тест мог менять модули.
    public static Path copyConfiguration(Path directory) throws IOException {
        Path project = directory.resolve("project");
        copyDirectory(CONFIGURATION, project.resolve("configuration"));
        return project;
    }

    public static void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path copy = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(file, copy);
                }
            }
        }
    }

    static boolean parseCovered(Element line) {
        String covered = line.getAttribute("covered");
        Element file = (Element) line.getParentNode();
        assertTrue("true".equals(covered) || "false".equals(covered),
                () -> "Invalid covered attribute at " + file.getAttribute("path") + ":" + line.getAttribute("lineNumber"));
        return "true".equals(covered);
    }

    public static void assertCoverageEqual(String expectedXmlFile, String controlXmlFile) {
        Source expectedXml = Input.fromFile(expectedXmlFile).build();
        Source controlXml = Input.fromFile(controlXmlFile).build();

        ElementSelector elementSelector = ElementSelectors.conditionalBuilder()
                .whenElementIsNamed("file").thenUse(ElementSelectors.byNameAndAttributes("path"))
                .whenElementIsNamed("lineToCover").thenUse(
                        ElementSelectors.and(
                                ElementSelectors.byXPath("./parent::file", ElementSelectors.byNameAndAttributes("path")),
                                ElementSelectors.byNameAndAttributes("lineNumber", "covered")
                        )
                )
                .whenElementIsNamed("coverage").thenUse(ElementSelectors.byNameAndAllAttributes)
                .build();

        assertThat(
                expectedXml,
                CompareMatcher.isSimilarTo(controlXml)
                        .withNodeMatcher(new DefaultNodeMatcher(
                                elementSelector
                        )));
    }

}
