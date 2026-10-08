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

import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.math.BigDecimal;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilsTest {

    @Test
    void normalizeXml() {
        String origit = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<response xmlns=\"http://v8.1c.ru/8.3/debugger/debugBaseData\" " +
                "xmlns:cfg=\"http://v8.1c.ru/8.1/data/enterprise/current-config\" " +
                "xmlns:debugRDBGRequestResponse=\"http://v8.1c.ru/8.3/debugger/debugRDBGRequestResponse\" " +
                "xmlns:v8=\"http://v8.1c.ru/8.1/data/core\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"/>" +
                "                                                         " +
                "                                                         " +
                "            nvState>" +
                "</request> nDebugger></dbgtgtRemoteRequestResponse:commandFromDbgServer></response> mmand";
        String template = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<response xmlns=\"http://v8.1c.ru/8.3/debugger/debugBaseData\" " +
                "xmlns:cfg=\"http://v8.1c.ru/8.1/data/enterprise/current-config\" " +
                "xmlns:debugRDBGRequestResponse=\"http://v8.1c.ru/8.3/debugger/debugRDBGRequestResponse\" " +
                "xmlns:v8=\"http://v8.1c.ru/8.1/data/core\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"/>";
        String result = Utils.normalizeXml(origit);
        assertEquals(template, result);
    }

    private static final String OBJECT_ID = "9b82e19b-3e2e-4eb2-9fac-70cde85488e7";
    private static final String PROPERTY_ID = "d5963243-262e-4398-b4d7-fb16d06484f6";

    @Test
    void uriKeyOfConfiguration() {
        String uriKey = Utils.getUriKey(OBJECT_ID, PROPERTY_ID);
        assertEquals(OBJECT_ID + "/" + PROPERTY_ID, uriKey);
        assertEquals(uriKey, Utils.getExtensionUriKey("", uriKey));
    }

    @Test
    void uriKeyOfExtension() {
        String uriKey = Utils.getUriKey(OBJECT_ID, PROPERTY_ID);
        assertEquals("Доработки/" + uriKey, Utils.getExtensionUriKey("Доработки", uriKey));
        assertNotEquals(Utils.getExtensionUriKey("", uriKey), Utils.getExtensionUriKey("Доработки", uriKey));
        assertNotEquals(Utils.getExtensionUriKey("Второе", uriKey), Utils.getExtensionUriKey("Доработки", uriKey));
    }

    @Test
    void rawUri() {
        String uriKey = Utils.getUriKey(OBJECT_ID, PROPERTY_ID);

        URI configurationUri = Utils.getRawUri("", uriKey);
        assertEquals(URI.create("file:///" + uriKey), configurationUri);
        assertEquals("", Utils.getRawExtensionName(configurationUri));

        URI extensionUri = Utils.getRawUri("Доработки", uriKey);
        assertEquals("/" + uriKey, extensionUri.getPath());
        assertEquals("Доработки", Utils.getRawExtensionName(extensionUri));
        assertNotEquals(configurationUri, extensionUri);
    }

    @Test
    void dumpRawCoverageWithExtensions() throws Exception {
        String uriKey = Utils.getUriKey(OBJECT_ID, PROPERTY_ID);
        Map<URI, Map<BigDecimal, Integer>> coverageData = new LinkedHashMap<>();
        coverageData.put(Utils.getRawUri("", uriKey), new HashMap<>(Map.of(new BigDecimal(2), 1)));
        coverageData.put(Utils.getRawUri("Доработки", uriKey), new HashMap<>(Map.of(new BigDecimal(6), 3)));

        MetadataOptions metadataOptions = new MetadataOptions();
        metadataOptions.setSrcDirName("");
        metadataOptions.setProjectDirName("");
        assertTrue(metadataOptions.isRawMode());

        File outputFile = new File("build/internalWithExtensions.xml");
        OutputOptions outputOptions = new OutputOptions();
        outputOptions.setOutputFile(outputFile);
        outputOptions.setOutputFormat(OutputOptions.OutputFormat.GENERIC_COVERAGE);

        Utils.dumpCoverageFile(coverageData, metadataOptions, outputOptions);

        NodeList files = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(outputFile)
                .getElementsByTagName("file");
        assertEquals(2, files.getLength());
        Map<String, String> lineByExtension = new HashMap<>();
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            assertEquals("/" + uriKey, file.getAttribute("path"));
            Element line = (Element) file.getElementsByTagName("lineToCover").item(0);
            lineByExtension.put(file.getAttribute("extension"), line.getAttribute("lineNumber"));
        }
        // configuration module - without attribute, as in previous versions
        assertEquals("2", lineByExtension.get(""));
        assertEquals("6", lineByExtension.get("Доработки"));
        assertFalse(lineByExtension.containsKey("Второе"));
    }

    @Test
    void printCoverageStats() {
        Map<URI, Map<BigDecimal, Integer>> coverageData = new LinkedHashMap<>();
        coverageData.put(new File("src/cf/CommonModules/Модуль/Ext/Module.bsl").getAbsoluteFile().toURI(),
                new HashMap<>(Map.of(new BigDecimal(2), 1, new BigDecimal(3), 0)));

        MetadataOptions metadataOptions = new MetadataOptions();
        metadataOptions.setSrcDirName("");
        metadataOptions.setProjectDirName(new File(".").getAbsolutePath());

        Utils.printCoverageStats(coverageData, metadataOptions);
    }
}
