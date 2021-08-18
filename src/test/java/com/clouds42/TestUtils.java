/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2021
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

import org.xmlunit.builder.Input;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.ElementSelector;
import org.xmlunit.diff.ElementSelectors;
import org.xmlunit.matchers.CompareMatcher;

import javax.xml.transform.Source;

import static org.hamcrest.MatcherAssert.assertThat;

public class TestUtils {

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
