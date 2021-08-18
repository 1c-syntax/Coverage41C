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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}