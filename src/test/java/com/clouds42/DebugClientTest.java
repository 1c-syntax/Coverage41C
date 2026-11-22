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

import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.core.runtime.client.RuntimeDebugClientException;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.CommandsFactory;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.MiscRDbgGetAPIVerResponse;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGAttachDetachDebugTargetsRequest;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGSetMeasureModeRequest;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGPingDebugUIResponse;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.ResponseFactory;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.ResponsePackage;
import com._1c.g5.v8.dt.debug.model.virtual.VirtualFactory;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            stopResponseServer();
        }
    }

    private void stopResponseServer() {
        if (responseServer != null) {
            responseServer.stop(0);
            responseServer = null;
        }
    }

    @Test
    void httpsValidatesCertificatesAndHostnames() {
        SslContextFactory ssl = client.createHttpClient().getSslContextFactory();
        assertFalse(ssl.isTrustAll());
        assertEquals("HTTPS", ssl.getEndpointIdentificationAlgorithm());
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
                    protected void doStart() {
                        // Keep the transport idle so this test exercises disposal and its retry.
                    }
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
        String xml = apiVersionXml();
        respond(200, "application/xml; charset=UTF-8", "\ufeff" + xml + "\0garbage");
        assertEquals("8.3.27", client.getApiVersion());
        respond(200, "application/xml; charset=UTF-8", zlibDeflate("\ufeff" + xml + "\0garbage"), "deflate");
        assertEquals("8.3.27", client.getApiVersion());
    }

    @Test
    void legacyApiVersionPreservesJettyGzipDecoding() throws Exception {
        byte[] frame = gzipBytes(("\ufeff" + apiVersionXml() + "\0padding").getBytes(StandardCharsets.UTF_8));
        respond(200, "application/xml; charset=UTF-8", frame, "gzip");
        assertEquals("8.3.27", client.getApiVersion());
    }

    @Test
    void pingPreservesJettyGzipDecodingForHttpError() throws Exception {
        var exception = VirtualFactory.eINSTANCE.createException();
        exception.setDescr("Debugger temporarily unavailable");
        String xml = new MyRuntimeDebugModelXmlSerializer().convertToXml(exception).trim();
        respond(500, "application/xml; charset=UTF-8", gzipBytes(xml.getBytes(StandardCharsets.UTF_8)), "gzip");
        var error = assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
        assertTrue(error.getMessage().contains("Debugger temporarily unavailable"), error.getMessage());
    }

    private String apiVersionXml() throws IOException {
        var version = ResponseFactory.eINSTANCE.createMiscRDbgGetAPIVerResponse();
        version.setVersion("8.3.27");
        return new MyRuntimeDebugModelXmlSerializer().convertToXml(version).trim()
                .replaceFirst("<response:MiscRDbgGetAPIVerResponse ",
                        "<response xmlns:debugRDBGRequestResponse=\"http://v8.1c.ru/8.3/debugger/debugRDBGRequestResponse\" ")
                .replace("</response:MiscRDbgGetAPIVerResponse>", "</response>");
    }

    @Test
    void deserializeUsesTheRootReplacementProvidedByASubclass() throws Exception {
        var serializer = new MyRuntimeDebugModelXmlSerializer() {
            @Override
            protected String replaceRootElement(String xml, String from, String to) {
                return super.replaceRootElement(xml, from, to).replace("8.3.27", "custom version");
            }
        };
        assertEquals("custom version", serializer.deserialize(apiVersionXml(), MiscRDbgGetAPIVerResponse.class).getVersion());
    }

    @Test
    void namedDeserializePassesTheRenamedXmlToTheObjectConversionOverride() throws Exception {
        var serializer = new MyRuntimeDebugModelXmlSerializer() {
            @Override
            protected EObject convertToEObject(String xml) throws IOException {
                var model = (MiscRDbgGetAPIVerResponse) super.convertToEObject(xml);
                model.setVersion("custom " + model.getVersion());
                return model;
            }
        };
        var model = serializer.deserialize("\ufeff" + apiVersionXml(), MiscRDbgGetAPIVerResponse.class,
                "response", "debugRDBGRequestResponse:MiscRDbgGetAPIVerResponse");
        assertEquals("custom 8.3.27", model.getVersion());
    }

    // An extension can inherit the hook from its own parent and delegate model loading back to Coverage41C.
    private static class DelegatingSerializer extends MyRuntimeDebugModelXmlSerializer {
        @Override
        protected EObject convertToEObject(String xml) throws IOException {
            return super.convertToEObject(xml);
        }
    }

    @Test
    void inheritedConversionHookAcceptsCommentsAndProcessingInstructionsAroundTheRoot() throws Exception {
        var serializer = new DelegatingSerializer() { };
        String xml = apiVersionXml();
        String root = xml.substring(xml.indexOf("<response "));
        var model = serializer.deserialize("<!-- <response> -->" + root + "<?note response?><!-- </response> -->",
                MiscRDbgGetAPIVerResponse.class);
        assertEquals("8.3.27", model.getVersion());
    }

    @Test
    void inheritedConversionHookRejectsAMismatchedClosingTag() throws Exception {
        var serializer = new DelegatingSerializer() { };
        String xml = "<response xmlns:model=\"" + ResponsePackage.eINSTANCE.getNsURI()
                + "\"></model:MiscRDbgGetAPIVerResponse>";
        assertThrows(IOException.class, () -> serializer.deserialize(xml, MiscRDbgGetAPIVerResponse.class,
                "response", "model:MiscRDbgGetAPIVerResponse"));
    }

    @Test
    void modifiedRootReplacementStillRejectsAMismatchedClosingTagInTheOriginalXml() {
        var serializer = new MyRuntimeDebugModelXmlSerializer() {
            @Override
            protected String replaceRootElement(String xml, String from, String to) {
                return super.replaceRootElement(xml, from, to).replace("><", "> <");
            }
        };
        String xml = "<response xmlns:model=\"" + ResponsePackage.eINSTANCE.getNsURI()
                + "\"></model:MiscRDbgGetAPIVerResponse>";

        assertThrows(IOException.class, () -> serializer.deserialize(xml, MiscRDbgGetAPIVerResponse.class,
                "response", "model:MiscRDbgGetAPIVerResponse"));
    }

    @Test
    void pingReturnsCommandsFromTheTypedRequestOverride() throws Exception {
        var response = ResponseFactory.eINSTANCE.createRDBGPingDebugUIResponse();
        var command = CommandsFactory.eINSTANCE.createDBGUIExtCmdInfoQuit();
        response.getResult().add(command);
        DebugClient custom = new DebugClient(new MyRuntimeDebugModelXmlSerializer()) {
            @Override
            protected <T extends EObject> T performRuntimeHttpRequest(Request request, Class<T> responseClass) {
                return responseClass.cast(response);
            }
        };
        try (FakeDebugServer dbgs = new FakeDebugServer()) {
            custom.configure(dbgs.url(), UUID.randomUUID(), "DefAlias");
            assertEquals(List.of(command), custom.ping());
            assertEquals(List.of(command), custom.pingResponse().orElseThrow());
        } finally {
            custom.dispose();
        }
    }

    @Test
    void profilingAndTargetAttachmentUseTheRequestBodyOverride() throws Exception {
        List<EObject> requests = new ArrayList<>();
        DebugClient custom = new DebugClient(new MyRuntimeDebugModelXmlSerializer()) {
            @Override
            protected void performRuntimeHttpRequest(Request request, EObject requestContent) {
                requests.add(requestContent);
            }
        };
        UUID session = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        try (FakeDebugServer dbgs = new FakeDebugServer()) {
            custom.configure(dbgs.url(), session, "DefAlias");
            custom.toggleProfiling(session);
            custom.attachRuntimeDebugTargets(List.of(target));
            assertEquals(2, requests.size());
            assertEquals(session.toString(), ((RDBGSetMeasureModeRequest) requests.get(0)).getMeasureModeSeanceID());
            var attach = (RDBGAttachDetachDebugTargetsRequest) requests.get(1);
            assertTrue(attach.isAttach());
            assertEquals(target.toString(), attach.getId().get(0).getId());
        } finally {
            custom.dispose();
        }
    }

    @Test
    void pingWithoutContentReturnsNoCommands() throws Exception {
        respond(204, "application/xml; charset=UTF-8", "");
        assertTrue(client.ping().isEmpty());
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingWithNonXmlContentDoesNotInventCommands() throws Exception {
        respond(200, "text/plain; charset=UTF-8", "unavailable");
        assertTrue(client.ping().isEmpty());
        assertTrue(client.pollDebugServer().isEmpty());
    }

    @Test
    void emptyXmlBodyDoesNotAcknowledgePoll() throws Exception {
        respond(200, "application/xml", "");
        assertTrue(client.pollDebugServer().isEmpty());
    }

    @Test
    void wrongXmlDocumentDoesNotAcknowledgePoll() throws Exception {
        respond(200, "application/xml", "<unavailable/>");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void debuggerXmlCanArriveWithoutContentLength() throws Exception {
        respondChunked(200, "application/xml", emptyPingXml());
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingWithBomAndTrailingNullPaddingIsAcknowledged() throws Exception {
        respond(200, "application/xml; charset=UTF-8", "\ufeff\r\n" + emptyPingXml() + "\r\n\0\0");
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingDecodesDeclaredCharsetBeforeHandlingNullPadding() throws Exception {
        String xml = withXmlDeclaration(emptyPingXml(), "UTF-16");
        byte[] body = ("\ufeff" + xml + "\0\0").getBytes(StandardCharsets.UTF_16LE);
        respond(200, "application/xml; charset=UTF-16LE", body, null);
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingPrefersUtf8BomToConflictingHttpCharset() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(emptyPingXml(), "UTF-8");
        respond(200, "application/xml; charset=ISO-8859-1", xml.getBytes(StandardCharsets.UTF_8), null);
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingRejectsDeclarationThatConflictsWithAuthoritativeBom() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(emptyPingXml(), "ISO-8859-1");
        respond(200, "application/xml; charset=ISO-8859-1", xml.getBytes(StandardCharsets.UTF_8), null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingPrefersHttpCharsetToBomlessUtf16Declaration() throws Exception {
        String xml = withXmlDeclaration(emptyPingXml(), "UTF-8");
        respond(200, "application/xml; charset=UTF-16LE", xml.getBytes(StandardCharsets.UTF_16LE), null);
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingInfersBomlessUtf16FromXmlDeclaration() throws Exception {
        String document = withXmlDeclaration(emptyPingXml(), "UTF-16BE");
        respond(200, "application/xml", document.getBytes(StandardCharsets.UTF_16BE), null);
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingRejectsUtf16BomWithIncompatibleDeclaration() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(emptyPingXml(), "UTF-16BE");
        respond(200, "application/xml", xml.getBytes(StandardCharsets.UTF_16LE), null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsBomlessUtf16WithIncompatibleDeclaration() throws Exception {
        String xml = withXmlDeclaration(emptyPingXml(), "UTF-8");
        respond(200, "application/xml", xml.getBytes(StandardCharsets.UTF_16LE), null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsUtf8BomWithIncompatibleDeclaration() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(emptyPingXml(), "ISO-8859-1");
        respond(200, "application/xml", xml.getBytes(StandardCharsets.UTF_8), null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsBomlessUtf32WithoutAnEncodingDeclaration() throws Exception {
        String document = emptyPingXml();
        String xml = "<?xml version=\"1.0\"?>" + document.substring(document.indexOf("<response"));
        respond(200, "application/xml", xml.getBytes(Charset.forName("UTF-32BE")), null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsBomlessUtf32WithOppositeByteOrderInDeclaration() throws Exception {
        String xml = withXmlDeclaration(emptyPingXml(), "UTF-32BE");
        byte[] frame = FakeDebugServer.deflate(xml.getBytes(Charset.forName("UTF-32LE")));
        respond(200, "application/xml", frame, "deflate");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsUtf32BomWithOppositeByteOrderInDeclaration() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(emptyPingXml(), "UTF-32BE");
        byte[] bytes = xml.getBytes(Charset.forName("UTF-32LE"));
        respond(200, "application/xml; charset=UTF-32LE", bytes, null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsTruncatedUtf32CodeUnitAfterXmlRoot() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(emptyPingXml(), "UTF-32");
        byte[] bytes = xml.getBytes(Charset.forName("UTF-32LE"));
        respond(200, "application/xml", Arrays.copyOf(bytes, bytes.length + 1), null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsMalformedUtf16EvenAfterXmlRoot() throws Exception {
        byte[] body = ("\ufeff" + withXmlDeclaration(emptyPingXml(), "UTF-16")).getBytes(StandardCharsets.UTF_16LE);
        respond(200, "application/xml", Arrays.copyOf(body, body.length + 1), null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsMalformedUtf8EvenAfterXmlRoot() throws Exception {
        byte[] body = emptyPingXml().getBytes(StandardCharsets.UTF_8);
        byte[] malformed = Arrays.copyOf(body, body.length + 1);
        malformed[body.length] = (byte) 0xFF;
        respond(200, "application/xml", malformed, null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsSecondXmlDocumentAfterUtf16Null() throws Exception {
        String document = withXmlDeclaration(emptyPingXml(), "UTF-16");
        byte[] body = ("\ufeff" + document + "\0" + document).getBytes(StandardCharsets.UTF_16LE);
        respond(200, "application/xml", body, null);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    private static String withXmlDeclaration(String xml, String encoding) {
        String content = xml.startsWith("<?xml") ? xml.substring(xml.indexOf("?>") + 2).stripLeading() : xml;
        return "<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>" + content;
    }

    @Test
    void pingRejectsAnotherXmlDocumentAfterNull() throws Exception {
        String document = emptyPingXml();
        respond(200, "application/xml", document + "\0" + document);
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingReadsXmlAcrossConsecutiveGzipMembers() throws Exception {
        byte[] xml = emptyPingXml().getBytes(StandardCharsets.UTF_8);
        int split = xml.length / 2;
        byte[] first = gzipBytes(Arrays.copyOfRange(xml, 0, split));
        byte[] second = gzipBytes(Arrays.copyOfRange(xml, split, xml.length));
        byte[] members = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, members, first.length, second.length);
        respond(200, "application/xml", members, "gzip");
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingRejectsMissingGzipRepresentation() throws Exception {
        respond(200, "application/xml", new byte[0], "gzip");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsGzipXmlWithTruncatedTrailer() throws Exception {
        byte[] frame = gzipBytes(emptyPingXml().getBytes(StandardCharsets.UTF_8));
        respond(200, "application/xml", Arrays.copyOf(frame, frame.length - 1), "gzip");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsGzipXmlWithBadCrc() throws Exception {
        byte[] frame = gzipBytes(emptyPingXml().getBytes(StandardCharsets.UTF_8));
        frame[frame.length - 8] ^= 1;
        respond(200, "application/xml", frame, "gzip");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsGzipXmlWithWrongSize() throws Exception {
        byte[] frame = gzipBytes(emptyPingXml().getBytes(StandardCharsets.UTF_8));
        frame[frame.length - 4] ^= 1;
        respond(200, "application/xml", frame, "gzip");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsGzipXmlWithTrailingBytes() throws Exception {
        byte[] frame = gzipBytes(emptyPingXml().getBytes(StandardCharsets.UTF_8));
        respond(200, "application/xml", Arrays.copyOf(frame, frame.length + 1), "gzip");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingAcceptsZlibDeflateResponse() throws Exception {
        respond(200, "application/xml", zlibDeflate(emptyPingXml()), "deflate");
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingAcceptsRawDeflateWithZlibHeaderPrefix() throws Exception {
        byte[] xml = ("<response xmlns:debugRDBGRequestResponse="
                + "\"http://v8.1c.ru/8.3/debugger/debugRDBGRequestResponse\"/>").getBytes(StandardCharsets.UTF_8);
        // The padded raw stored block starts with 78 9c, which is also a valid zlib header.
        byte[] frame = new byte[5 + 0x9C + 2];
        frame[0] = 0x78;
        frame[1] = (byte) 0x9C;
        frame[3] = 0x63;
        frame[4] = (byte) 0xFF;
        Arrays.fill(frame, 5, frame.length - 2, (byte) ' ');
        System.arraycopy(xml, 0, frame, 5, xml.length);
        frame[frame.length - 2] = 0x03;
        respond(200, "application/xml", frame, "deflate");
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingRejectsZlibDeflateWithInvalidChecksum() throws Exception {
        byte[] frame = zlibDeflate(emptyPingXml());
        frame[frame.length - 1] ^= 1;
        respond(200, "application/xml", frame, "deflate");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsTruncatedZlibDeflate() throws Exception {
        byte[] frame = zlibDeflate(emptyPingXml());
        respond(200, "application/xml", Arrays.copyOf(frame, frame.length - 1), "deflate");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingRejectsBytesAfterZlibDeflateStream() throws Exception {
        byte[] frame = zlibDeflate(emptyPingXml());
        respond(200, "application/xml", Arrays.copyOf(frame, frame.length + 1), "deflate");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    private static byte[] zlibDeflate(String xml) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed)) {
            deflate.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return compressed.toByteArray();
    }

    @Test
    void pingRejectsASecondDeflateStream() throws Exception {
        byte[] frame = FakeDebugServer.deflate(emptyPingXml().getBytes(StandardCharsets.UTF_8));
        byte[] body = Arrays.copyOf(frame, 2 * frame.length);
        System.arraycopy(frame, 0, body, frame.length, frame.length);
        respond(200, "application/xml", body, "deflate");
        assertThrows(RuntimeDebugClientException.class, client::pollDebugServer);
    }

    @Test
    void pingAcceptsCommentsAroundXmlRoot() throws Exception {
        String document = emptyPingXml();
        respond(200, "application/xml", "<!-- <response> -->" + document.substring(document.indexOf("<response"))
                + "<!-- </response> -->");
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void pingAcceptsProcessingInstructionAfterXmlRoot() throws Exception {
        String document = emptyPingXml();
        if (document.endsWith("/>")) {
            document = document.substring(0, document.length() - 2) + "></response>";
        }
        respond(200, "application/xml", document + "<?debugger response?>");
        assertTrue(client.pollDebugServer().orElseThrow().isEmpty());
    }

    @Test
    void directSerializerRejectsUnreadableMeasureAndCollectorRejectsMalformedDocument() throws Exception {
        var response = ResponseFactory.eINSTANCE.createRDBGPingDebugUIResponse();
        response.getResult().add(FakeDebugServer.measure("object", "property", 3, 98765));
        String xml = pingXml(response).replace(">98765<", ">NaN<");
        assertTrue(xml.contains(">NaN<"));
        assertThrows(IOException.class, () -> new MyRuntimeDebugModelXmlSerializer()
                .deserialize(xml, RDBGPingDebugUIResponse.class));
        assertThrows(IOException.class, () -> MyRuntimeDebugModelXmlSerializer.forMeasurementCollection()
                .deserialize(xml + "<response/>", RDBGPingDebugUIResponse.class));
    }

    private String emptyPingXml() throws IOException {
        return pingXml(ResponseFactory.eINSTANCE.createRDBGPingDebugUIResponse());
    }

    @Test
    void pingSignalsReceiptBeforeParsingTheResponse() throws Exception {
        respond(200, "application/xml", emptyPingXml());
        AtomicBoolean received = new AtomicBoolean();
        AtomicBoolean parsed = new AtomicBoolean();
        client.serializer = new MyRuntimeDebugModelXmlSerializer() {
            @Override
            public <T extends EObject> T deserialize(String xml, Class<T> type) throws IOException {
                assertTrue(received.get());
                parsed.set(true);
                return super.deserialize(xml, type);
            }
        };
        assertTrue(client.pingResponse(() -> received.set(true)).isPresent());
        assertTrue(parsed.get());
    }

    private String pingXml(RDBGPingDebugUIResponse response) throws IOException {
        return new MyRuntimeDebugModelXmlSerializer().convertToXml(response).trim()
                .replaceFirst("<response:RDBGPingDebugUIResponse ",
                        "<response xmlns:debugRDBGRequestResponse=\"http://v8.1c.ru/8.3/debugger/debugRDBGRequestResponse\" ")
                .replace("</response:RDBGPingDebugUIResponse>", "</response>");
    }

    @Test
    void noContentCannotSupplyTargetList() throws Exception {
        respond(204, "application/xml", "");
        assertThrows(RuntimeDebugClientException.class, () -> client.getRuntimeDebugTargets(null));
    }

    @Test
    void proxyCannotSupplyTargetList() throws Exception {
        respond(200, "text/plain", "Upstream unavailable");
        assertThrows(RuntimeDebugClientException.class, () -> client.getRuntimeDebugTargets("area"));
    }

    @Test
    void targetListRequiresXmlMediaType() throws Exception {
        respond(200, null, "Not available");
        assertThrows(RuntimeDebugClientException.class, () -> client.getRuntimeDebugTargets(null));
    }

    @Test
    void debugTargetListInfersUtf16LeFromBom() throws Exception {
        UUID targetId = UUID.randomUUID();
        String document = withXmlDeclaration(debugTargetListXml(targetId, "Тест"), "UTF-16");
        byte[] body = ("\ufeff" + document + "\0").getBytes(StandardCharsets.UTF_16LE);
        respond(200, "application/xml", body, null);
        var targets = client.getRuntimeDebugTargets(null);
        assertEquals(1, targets.size());
        assertEquals(targetId.toString(), targets.get(0).getId());
        assertEquals("Тест", targets.get(0).getUserName());
    }

    @Test
    void debugTargetListInfersUtf16BeFromBom() throws Exception {
        UUID targetId = UUID.randomUUID();
        String document = withXmlDeclaration(debugTargetListXml(targetId, "Тест"), "UTF-16");
        byte[] body = ("\ufeff" + document + "\0").getBytes(StandardCharsets.UTF_16BE);
        respond(200, "application/xml", body, null);
        var targets = client.getRuntimeDebugTargets(null);
        assertEquals(1, targets.size());
        assertEquals(targetId.toString(), targets.get(0).getId());
        assertEquals("Тест", targets.get(0).getUserName());
    }

    @Test
    void debugTargetListUsesXmlEncodingDeclarationWithoutHttpCharset() throws Exception {
        String document = withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "ISO-8859-1");
        respond(200, "application/xml", document.getBytes(StandardCharsets.ISO_8859_1), null);
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListPrefersHttpCharsetToXmlEncodingDeclaration() throws Exception {
        String document = withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-8");
        respond(200, "application/xml; charset=ISO-8859-1", document.getBytes(StandardCharsets.ISO_8859_1), null);
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListPrefersUtf16LeBomToConflictingHttpCharsetInDeflate() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-16");
        byte[] compressed = FakeDebugServer.deflate(xml.getBytes(StandardCharsets.UTF_16LE));
        respond(200, "application/xml; charset=UTF-16BE", compressed, "deflate");
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListPrefersUtf16BeBomToConflictingHttpCharsetInGzip() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-16");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(xml.getBytes(StandardCharsets.UTF_16BE));
        }
        respond(200, "application/xml; charset=ISO-8859-1", compressed.toByteArray(), "gzip");
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListInfersUtf32LeFromBom() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-32");
        byte[] bytes = xml.getBytes(Charset.forName("UTF-32LE"));
        respond(200, "application/xml", bytes, null);
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListPrefersUtf32BeBomInDeflatedResponse() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-32BE");
        byte[] compressed = FakeDebugServer.deflate(xml.getBytes(Charset.forName("UTF-32BE")));
        respond(200, "application/xml; charset=UTF-16LE", compressed, "deflate");
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListInfersBomlessUtf32LeFromMatchingDeclaration() throws Exception {
        String xml = withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-32LE");
        respond(200, "application/xml", xml.getBytes(Charset.forName("UTF-32LE")), null);
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListInfersBomlessUtf32BeFromGzipXml() throws Exception {
        String xml = withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-32BE");
        byte[] frame = gzipBytes(xml.getBytes(Charset.forName("UTF-32BE")));
        respond(200, "application/xml", frame, "gzip");
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListAcceptsExplicitUtf32BeWithoutBom() throws Exception {
        String xml = withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-32BE");
        byte[] bytes = xml.getBytes(Charset.forName("UTF-32BE"));
        respond(200, "application/xml; charset=UTF-32BE", bytes, null);
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListRetainsExplicitUtf32LeWithBom() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Démo"), "UTF-32LE");
        byte[] bytes = xml.getBytes(Charset.forName("UTF-32LE"));
        respond(200, "application/xml; charset=UTF-32LE", bytes, null);
        assertEquals("Démo", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListReadsUtf8BomAndPreservesUserName() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Тест"), "UTF-8");
        respond(200, "application/xml", xml.getBytes(StandardCharsets.UTF_8), null);
        assertEquals("Тест", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListReadsDeflatedUtf16Response() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Тест"), "UTF-16");
        byte[] compressed = FakeDebugServer.deflate(xml.getBytes(StandardCharsets.UTF_16LE));
        respond(200, "application/xml", compressed, "deflate");
        assertEquals("Тест", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListReadsGzipUtf16Response() throws Exception {
        String xml = "\ufeff" + withXmlDeclaration(debugTargetListXml(UUID.randomUUID(), "Тест"), "UTF-16");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(xml.getBytes(StandardCharsets.UTF_16BE));
        }
        respond(200, "application/xml", compressed.toByteArray(), "gzip");
        assertEquals("Тест", client.getRuntimeDebugTargets(null).get(0).getUserName());
    }

    @Test
    void debugTargetListAcceptsZlibDeflateResponse() throws Exception {
        UUID targetId = UUID.randomUUID();
        respond(200, "application/xml", zlibDeflate(debugTargetListXml(targetId)), "deflate");
        var targets = client.getRuntimeDebugTargets(null);
        assertEquals(1, targets.size());
        assertEquals(targetId.toString(), targets.get(0).getId());
    }

    @Test
    void debugTargetListAcceptsRawDeflateResponse() throws Exception {
        UUID targetId = UUID.randomUUID();
        respond(200, "application/xml", FakeDebugServer.deflate(debugTargetListXml(targetId).getBytes(StandardCharsets.UTF_8)), "deflate");
        var targets = client.getRuntimeDebugTargets(null);
        assertEquals(1, targets.size());
        assertEquals(targetId.toString(), targets.get(0).getId());
    }

    @Test
    void debugTargetListRejectsConcatenatedDocuments() throws Exception {
        String document = debugTargetListXml(UUID.randomUUID());
        respond(200, "application/xml", document);
        assertEquals(1, client.getRuntimeDebugTargets(null).size());

        respond(200, "application/xml", document + document);
        assertThrows(RuntimeDebugClientException.class, () -> client.getRuntimeDebugTargets(null));
    }

    private String debugTargetListXml(UUID targetId) throws IOException {
        return debugTargetListXml(targetId, "demo_runner");
    }

    private String debugTargetListXml(UUID targetId, String userName) throws IOException {
        var response = ResponseFactory.eINSTANCE.createRDBGSGetDbgTargetsResponse();
        response.getId().add(FakeDebugServer.target(targetId, DebugTargetType.SERVER, userName));
        String modelTag = "response:RDBGSGetDbgTargetsResponse";
        return new MyRuntimeDebugModelXmlSerializer().convertToXml(response).trim()
                .replace("<" + modelTag + " ",
                        "<response xmlns:debugRDBGRequestResponse=\"http://v8.1c.ru/8.3/debugger/debugRDBGRequestResponse\" ")
                .replace("</" + modelTag + ">", "</response>");
    }

    @Test
    void debuggerMayReturnAnEmptyTargetList() throws Exception {
        try (FakeDebugServer dbg = new FakeDebugServer()) {
            dbg.setTargets();
            client.configure(dbg.url(), UUID.randomUUID(), "DefAlias");
            assertTrue(client.getRuntimeDebugTargets(null).isEmpty());
        }
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
    void setMeasureModeAcceptsEmptySuccessfulResponse() throws Exception {
        respond(200, null, "");
        client.toggleProfiling(UUID.randomUUID());
    }

    @Test
    void setMeasureModeAcceptsNoContent() throws Exception {
        respond(204, null, "");
        client.toggleProfiling(null);
    }

    @Test
    void setMeasureModeRejectsProxySuccess() throws Exception {
        respond(200, "text/plain", "Maintenance in progress");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void setMeasureModeRejectsUnexpectedXmlBody() throws Exception {
        respond(200, "application/xml", "<unrelated/>");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(UUID.randomUUID()));
    }

    @Test
    void profilingRejectsMissingDeflateStreamInSuccessfulResponse() throws Exception {
        respond(200, null, new byte[0], "deflate");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingAcceptsNoContentWithDeflateHeader() throws Exception {
        respond(204, null, new byte[0], "deflate");
        client.toggleProfiling(null);
    }

    @Test
    void profilingAcceptsEmptyIdentityAcknowledgement() throws Exception {
        respond(200, null, new byte[0], "identity");
        client.toggleProfiling(null);
    }

    @Test
    void profilingRejectsUnsupportedEncodingEvenWithEmptyBody() throws Exception {
        respond(200, null, new byte[0], "x-unknown");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingRejectsMultipleContentEncodingHeaders() throws Exception {
        respondWithContentEncodings(200, null, new byte[0], -1, List.of("identity", "identity"));
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingRejectsStackedContentEncodings() throws Exception {
        respond(200, null, gzipBytes(new byte[0]), "identity, gzip");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingAcceptsEmptyGzipAcknowledgement() throws Exception {
        respond(200, null, gzipBytes(new byte[0]), "gzip");
        client.toggleProfiling(null);
    }

    @Test
    void profilingAcceptsConsecutiveEmptyGzipMembers() throws Exception {
        byte[] member = gzipBytes(new byte[0]);
        byte[] members = Arrays.copyOf(member, 2 * member.length);
        System.arraycopy(member, 0, members, member.length, member.length);
        respond(200, null, members, "gzip");
        client.toggleProfiling(null);
    }

    @Test
    void profilingAcceptsEmptyGzipWithOptionalHeaderFields() throws Exception {
        byte[] member = gzipBytes(new byte[0]);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        byte[] header = Arrays.copyOf(member, 10);
        header[3] = 0x1E; // FEXTRA, FNAME, FCOMMENT and FHCRC.
        framed.write(header);
        framed.write(new byte[]{5, 0, 'A', 'B', 1, 0, 0x11});
        framed.write("report.xml\0empty acknowledgement\0".getBytes(StandardCharsets.US_ASCII));
        CRC32 headerChecksum = new CRC32();
        headerChecksum.update(framed.toByteArray());
        framed.write((int) headerChecksum.getValue());
        framed.write((int) (headerChecksum.getValue() >>> 8));
        framed.write(member, header.length, member.length - header.length);

        respond(200, null, framed.toByteArray(), "gzip");
        client.toggleProfiling(null);
    }

    @Test
    void profilingRejectsMissingGzipStream() throws Exception {
        respond(200, null, new byte[0], "gzip");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingRejectsTruncatedGzipHeader() throws Exception {
        byte[] member = gzipBytes(new byte[0]);
        respond(200, null, Arrays.copyOf(member, 1), "gzip");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingRejectsTruncatedGzipTrailer() throws Exception {
        byte[] member = gzipBytes(new byte[0]);
        respond(200, null, Arrays.copyOf(member, member.length - 1), "gzip");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingRejectsGzipWithInvalidChecksum() throws Exception {
        byte[] member = gzipBytes(new byte[0]);
        member[member.length - 8] ^= 0x01;
        respond(200, null, member, "gzip");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingRejectsBytesAfterGzipStream() throws Exception {
        byte[] member = gzipBytes(new byte[0]);
        respond(200, null, Arrays.copyOf(member, member.length + 1), "gzip");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingRejectsNonemptyGzipAcknowledgement() throws Exception {
        respond(200, null, gzipBytes(new byte[]{1}), "gzip");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(null));
    }

    @Test
    void profilingAcceptsNoContentWithGzipHeader() throws Exception {
        respond(204, null, new byte[0], "gzip");
        client.toggleProfiling(null);
    }

    private static byte[] gzipBytes(byte[] bytes) throws IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(content)) {
            gzip.write(bytes);
        }
        return content.toByteArray();
    }

    @Test
    void profilingAcceptsDeflateOfEmptyBody() throws Exception {
        respond(200, "application/xml", new byte[]{0x03, 0x00}, "deflate");
        client.toggleProfiling(UUID.randomUUID());
        client.toggleProfiling(null);
    }

    @Test
    void targetAttachmentAcceptsDeflateOfAnEmptyAcknowledgement() throws Exception {
        respond(200, "application/xml", new byte[]{0x03, 0x00}, "deflate");
        client.attachRuntimeDebugTargets(List.of(UUID.randomUUID()));
    }

    @Test
    void profilingAcceptsZlibDeflateOfEmptyBody() throws Exception {
        respond(200, "application/xml", new byte[]{0x78, (byte) 0x9C, 0x03, 0, 0, 0, 0, 0x01},
                "deflate");
        client.toggleProfiling(UUID.randomUUID());
        client.toggleProfiling(null);
    }

    @Test
    void profilingAcceptsRawDeflateWithZlibLikeFirstByte() throws Exception {
        // Empty non-final stored block with padding, followed by an empty final block.
        respond(200, "application/xml", new byte[]{0x78, 0, 0, (byte) 0xFF, (byte) 0xFF, 0x03, 0},
                "deflate");
        client.toggleProfiling(null);
    }

    @Test
    void profilingRejectsZlibDeflateOfNonemptyBody() throws Exception {
        respond(200, "application/xml", new byte[]{0x78, (byte) 0x9C, 0x63, 0, 0, 0, 0x01, 0, 0x01},
                "deflate");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(UUID.randomUUID()));
    }

    @Test
    void profilingRejectsZlibDeflateWithInvalidChecksum() throws Exception {
        respond(200, "application/xml", new byte[]{0x78, (byte) 0x9C, 0x03, 0, 0, 0, 0, 0x02},
                "deflate");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(UUID.randomUUID()));
    }

    @Test
    void profilingRejectsBytesAfterEmptyZlibDeflateStream() throws Exception {
        respond(200, "application/xml", new byte[]{0x78, (byte) 0x9C, 0x03, 0, 0, 0, 0, 0x01, 0},
                "deflate");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(UUID.randomUUID()));
    }

    @Test
    void profilingRejectsDeflateOfNonemptyBody() throws Exception {
        // Final stored block containing a single NUL. XML normalization must not erase it.
        respond(200, "application/xml", new byte[]{0x01, 0x01, 0x00, (byte) 0xFE, (byte) 0xFF, 0x00},
                "deflate");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(UUID.randomUUID()));
    }

    @Test
    void profilingRejectsTruncatedDeflate() throws Exception {
        respond(200, "application/xml", new byte[]{0x03}, "deflate");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(UUID.randomUUID()));
    }

    @Test
    void profilingRejectsBytesAfterEmptyDeflateStream() throws Exception {
        respond(200, "application/xml", new byte[]{0x03, 0x00, 0x00}, "deflate");
        assertThrows(RuntimeDebugClientException.class, () -> client.toggleProfiling(UUID.randomUUID()));
    }

    @Test
    void initSettingsStillAcceptsSuccessfulResponseWithBody() throws Exception {
        respond(200, "text/plain", "Settings accepted");
        client.initSettings(false);
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
        int length = body.isEmpty() ? -1 : body.getBytes(StandardCharsets.UTF_8).length;
        respond(status, contentType, body, length);
    }

    private void respondChunked(int status, String contentType, String body) throws IOException {
        respond(status, contentType, body, 0);
    }

    private void respond(int status, String contentType, String body, int length) throws IOException {
        respond(status, contentType, body.getBytes(StandardCharsets.UTF_8), length, null);
    }

    private void respond(int status, String contentType, byte[] content, String encoding) throws IOException {
        respond(status, contentType, content, content.length, encoding);
    }

    private void respond(int status, String contentType, byte[] content, int length, String encoding) throws IOException {
        respondWithContentEncodings(status, contentType, content, length, encoding == null ? List.of() : List.of(encoding));
    }

    private void respondWithContentEncodings(int status, String contentType, byte[] content, int length,
            List<String> contentEncodings) throws IOException {
        stopResponseServer();
        responseServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        responseServer.createContext("/e1crdbg/rdbg", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            contentEncodings.forEach(encoding -> exchange.getResponseHeaders().add("Content-Encoding", encoding));
            exchange.sendResponseHeaders(status, length);
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
