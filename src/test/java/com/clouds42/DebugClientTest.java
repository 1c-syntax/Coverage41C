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

import com._1c.g5.v8.dt.debug.core.runtime.client.RuntimeDebugClientException;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.ResponseFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugClientTest {

    private final DebugClient client = new DebugClient(new MyRuntimeDebugModelXmlSerializer());
    private HttpServer responseServer;

    @AfterEach
    void dispose() throws RuntimeDebugClientException {
        try {
            client.dispose();
        } finally {
            if (responseServer != null) {
                responseServer.stop(0);
            }
        }
    }

    @Test
    void httpClientInitializationFailurePreservesCause() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DebugClient(new MyRuntimeDebugModelXmlSerializer()) {
                    @Override
                    protected HttpClient createHttpClient() {
                        return new HttpClient() {
                            @Override
                            protected void doStart() throws Exception {
                                throw new IOException("cannot initialize transport");
                            }
                        };
                    }
                });
        assertTrue(error.getMessage().contains("Initialization error"));
        assertEquals("cannot initialize transport", error.getCause().getMessage());
    }

    @Test
    void httpClientCloseFailurePreservesCauseAndCanBeRetried() throws Exception {
        AtomicInteger stopCalls = new AtomicInteger();
        DebugClient failing = new DebugClient(new MyRuntimeDebugModelXmlSerializer()) {
            @Override
            protected HttpClient createHttpClient() {
                return new HttpClient() {
                    @Override
                    protected void doStart() { }
                    @Override
                    protected void doStop() throws Exception {
                        if (stopCalls.incrementAndGet() == 1) {
                            throw new IOException("cannot close transport");
                        }
                        super.doStop();
                    }
                };
            }
        };
        try {
            RuntimeDebugClientException error = assertThrows(RuntimeDebugClientException.class, failing::dispose);
            assertEquals("cannot close transport", error.getCause().getMessage());
        } finally {
            failing.dispose();
        }
        assertEquals(2, stopCalls.get());
    }

    @Test
    void rejectsMissingDebuggerUrlBeforeConnecting() {
        for (String url : new String[]{null, ""}) {
            assertThrows(IllegalArgumentException.class, () -> client.configure(url, UUID.randomUUID(), "DefAlias"));
        }
    }

    @Test
    void rejectsMissingInfobaseBeforeConnecting() {
        for (String alias : new String[]{null, ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.configure("http://127.0.0.1:1550", UUID.randomUUID(), alias));
        }
    }

    @Test
    void readsApiVersionWithBomAndNullPaddingWithoutTrailingSlashInUrl() throws Exception {
        var version = ResponseFactory.eINSTANCE.createMiscRDbgGetAPIVerResponse();
        version.setVersion("8.3.27");
        String xml = new MyRuntimeDebugModelXmlSerializer().convertToXml(version).trim()
                .replaceFirst("<response:MiscRDbgGetAPIVerResponse ",
                        "<response xmlns:debugRDBGRequestResponse=\"http://v8.1c.ru/8.3/debugger/debugRDBGRequestResponse\" ")
                .replace("</response:MiscRDbgGetAPIVerResponse>", "</response>");
        respond(200, "application/xml; charset=UTF-8", "\ufeff" + xml + "\0garbage");
        assertEquals("8.3.27", client.getApiVersion());
    }

    @Test
    void pingWithoutContentReturnsNoCommands() throws Exception {
        respond(204, "application/xml; charset=UTF-8", "");
        assertTrue(client.ping().isEmpty());
    }

    @Test
    void pingWithNonXmlContentDoesNotInventCommands() throws Exception {
        respond(200, "text/plain; charset=UTF-8", "unavailable");
        assertTrue(client.ping().isEmpty());
    }

    @Test
    void malformedSuccessXmlReportsResponseError() throws Exception {
        respond(200, "application/xml; charset=UTF-8", "<response");
        RuntimeDebugClientException error = assertThrows(RuntimeDebugClientException.class, client::getApiVersion);
        assertTrue(error.getMessage().contains("processing response"), error.getMessage());
        assertTrue(error.getCause() instanceof IOException);
    }

    @Test
    void malformedErrorBodyReportsResponseError() throws Exception {
        respond(500, "text/plain; charset=UTF-8", "not XML");
        RuntimeDebugClientException error = assertThrows(RuntimeDebugClientException.class, client::getApiVersion);
        assertTrue(error.getMessage().contains("processing response"), error.getMessage());
        assertTrue(error.getCause() instanceof IOException);
    }

    @Test
    void emptyErrorResponseIncludesHttpStatus() throws Exception {
        respond(500, "application/xml", "");
        RuntimeDebugClientException error = assertThrows(RuntimeDebugClientException.class, client::getApiVersion);
        assertTrue(error.getMessage().contains("status: 500"), error.getMessage());
    }

    @Test
    void suspendedInitialSettingsAreSentToDebugger() throws Exception {
        try (FakeDebugServer dbgs = new FakeDebugServer()) {
            client.configure(dbgs.url(), UUID.randomUUID(), "DefAlias");
            client.initSettings(false);
            client.initSettings(true);
            assertEquals(2, dbgs.requests("initSettings").size());
            assertEquals(0, settingsDataCount(dbgs.requests("initSettings").get(0).body),
                    dbgs.requests("initSettings").get(0).body);
            assertEquals(1, settingsDataCount(dbgs.requests("initSettings").get(1).body),
                    dbgs.requests("initSettings").get(1).body);
        }
    }

    private int settingsDataCount(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagNameNS("*", "data").getLength();
    }

    private void respond(int status, String contentType, String body) throws IOException {
        responseServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        responseServer.createContext("/e1crdbg/rdbg", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, content.length == 0 ? -1 : content.length);
            if (content.length > 0) {
                exchange.getResponseBody().write(content);
            }
            exchange.close();
        });
        responseServer.start();
        client.configure("http://127.0.0.1:" + responseServer.getAddress().getPort(), UUID.randomUUID(), "DefAlias");
    }

    // Имена сервисов docker-compose часто содержат подчёркивание, java.net.URI такой хост не разбирает.
    @Test
    void debuggerHostMayContainUnderscore() {
        Request request = client.buildRequest(HttpMethod.POST, "http://dbgs_host:1550/e1crdbg/rdbg");

        assertEquals("dbgs_host", request.getHost());
        assertEquals(1550, request.getPort());
        assertEquals("/e1crdbg/rdbg", request.getPath());
    }

    @Test
    void errorOfDebugServerShowsItsDescription() throws Exception {
        try (FakeDebugServer dbgs = new FakeDebugServer()) {
            dbgs.failWithException("attachDebugUI", "Нет доступа к информационной базе");
            client.configure(dbgs.url(), UUID.randomUUID(), "DefAlias");

            RuntimeDebugClientException error = assertThrows(RuntimeDebugClientException.class, () -> client.connect(""));

            assertTrue(error.getMessage().contains("Нет доступа к информационной базе"), error.getMessage());
        }
    }
}
