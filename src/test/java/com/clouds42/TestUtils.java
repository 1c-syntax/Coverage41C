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
