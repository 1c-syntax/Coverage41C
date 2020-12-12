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