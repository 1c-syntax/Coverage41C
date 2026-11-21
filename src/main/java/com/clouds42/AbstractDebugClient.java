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
import com._1c.g5.v8.dt.debug.model.virtual.Exception;
import com._1c.g5.v8.dt.internal.debug.core.model.RuntimePresentationConverter;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugModelXmlSerializer;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public abstract class AbstractDebugClient {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String UTF_32_CHARSET_NAME = "UTF-32";
    private static final String XML_DECLARATION = "<?xml";
    private static final byte[] XML_DECLARATION_PREFIX = XML_DECLARATION.getBytes(StandardCharsets.US_ASCII);
    private static final Pattern XML_DECLARATION_ENCODING = Pattern.compile(
            "[ \t\r\n]encoding[ \t\r\n]*=[ \t\r\n]*(['\"])([A-Za-z][A-Za-z0-9._-]*)\\1");
    protected RuntimeDebugModelXmlSerializer serializer;

    public AbstractDebugClient(RuntimeDebugModelXmlSerializer serializer) {
        this.serializer = serializer;
    }

    protected HttpClient createHttpClient() {
        return this.createHttpClient(60000L);
    }

    protected HttpClient createHttpClient(long idleTimeout) {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);

        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));
        httpClient.setFollowRedirects(true);
        httpClient.setUserAgentField(new HttpField("User-Agent", "1CV8"));
        httpClient.setIdleTimeout(idleTimeout);
        return httpClient;
    }

    protected Request buildRequest(HttpClient httpClient, HttpMethod method, String componentUrl) {
        return httpClient.newRequest(this.toUri(componentUrl))
                .method(method)
                .headers(httpFields -> httpFields
                        .add(HttpHeader.ACCEPT, "application/xml")
                        .add(HttpHeader.CONNECTION, HttpHeader.KEEP_ALIVE.asString())
                        .add(HttpHeader.CONTENT_TYPE, "application/xml")
                        .add("1C-ApplicationName", "1C:Enterprise DT")
                );
    }

    protected String getComponentUrl(String debugServerUrl, String suffix) {
        StringBuilder componentUrl = new StringBuilder();
        componentUrl.append(debugServerUrl);
        if (debugServerUrl.charAt(debugServerUrl.length() - 1) != '/') {
            componentUrl.append('/');
        }

        return componentUrl.append(suffix).toString();
    }

    protected <T extends EObject> T performRuntimeHttpRequest(Request request, Class<T> responseClass) throws RuntimeDebugClientException {
        return AbstractDebugClient.performRuntimeHttpRequest(this, request, null, responseClass);
    }

    protected void performRuntimeHttpRequest(Request request, EObject requestContent) throws RuntimeDebugClientException {
        AbstractDebugClient.performRuntimeHttpRequest(this, request, requestContent, null);
    }

    protected static <T extends EObject> T performRuntimeHttpRequest(AbstractDebugClient client, Request request,
            EObject requestContent, Class<T> responseClass) throws RuntimeDebugClientException {
        return requestResponse(client, request, requestContent, responseClass).model();
    }

    protected record DebugResponse<T extends EObject>(int status, T model, boolean hasBody) {}

    protected <T extends EObject> DebugResponse<T> requestResponse(Request request, Class<T> responseClass)
            throws RuntimeDebugClientException {
        return requestResponse(this, request, null, responseClass);
    }

    protected DebugResponse<EObject> requestResponse(Request request, EObject requestContent)
            throws RuntimeDebugClientException {
        return requestResponse(this, request, requestContent, null);
    }

    protected <T extends EObject> DebugResponse<T> requestCompleteXml(Request request, EObject requestContent,
            Class<T> responseClass)
            throws RuntimeDebugClientException {
        return requestResponse(this, request, requestContent, responseClass, ResponseBody.COMPLETE_XML);
    }

    protected DebugResponse<EObject> requestAcknowledgement(Request request, EObject requestContent)
            throws RuntimeDebugClientException {
        return requestResponse(this, request, requestContent, null, ResponseBody.ACKNOWLEDGEMENT);
    }

    private static <T extends EObject> DebugResponse<T> requestResponse(AbstractDebugClient client, Request request,
            EObject requestContent, Class<T> responseClass) throws RuntimeDebugClientException {
        return requestResponse(client, request, requestContent, responseClass, ResponseBody.LEGACY_XML);
    }

    private enum ResponseBody { LEGACY_XML, COMPLETE_XML, ACKNOWLEDGEMENT }

    private static <T extends EObject> DebugResponse<T> requestResponse(AbstractDebugClient client, Request request,
            EObject requestContent, Class<T> responseClass, ResponseBody bodyMode) throws RuntimeDebugClientException {
        try {
            if (requestContent != null) {
                try {
                    String serializedRequest = client.serializer.serialize(requestContent);
                    request.body(new StringRequestContent(serializedRequest));
                } catch (IOException e) {
                    throw new RuntimeDebugClientException("Error occurred while processing request", e);
                }
            }

            AtomicReference<String> strictResponseEncoding = bodyMode == ResponseBody.LEGACY_XML
                    ? null : captureStrictResponseEncoding(request);
            CompletableResponseListener listener = new CompletableResponseListener(request, 2147483647);
            ContentResponse response = listener.send().get(60L, TimeUnit.SECONDS);
            int status = response.getStatus();
            if (HttpStatus.isSuccess(status)) {
                String encoding = strictResponseEncoding == null ? null : strictResponseEncoding.get();
                byte[] body = response.getContent();
                boolean hasBody = body != null && body.length > 0;
                if (bodyMode == ResponseBody.ACKNOWLEDGEMENT) {
                    boolean valid = isEmptyAcknowledgement(body, status, encoding);
                    if (!valid && !hasBody) {
                        throw new RuntimeDebugClientException("Missing or unsupported debugger acknowledgement body");
                    }
                    return new DebugResponse<>(status, null, !valid);
                }
                if (status == HttpStatus.NO_CONTENT_204 || responseClass == null
                        || !"application/xml".equalsIgnoreCase(response.getMediaType())
                        || (!hasBody && isIdentityEncoding(encoding))) {
                    return new DebugResponse<>(status, null, hasBody);
                }
                try {
                    String xml = bodyMode == ResponseBody.COMPLETE_XML
                            ? getCompleteXmlFromContent(response, encoding) : getStringFromContent(response);
                    return new DebugResponse<>(status, responseClass.cast(client.serializer.deserialize(xml, responseClass)),
                            true);
                } catch (IOException | ClassCastException e) {
                    throw new RuntimeDebugClientException("Error occurred while processing response", e);
                }
            } else if (response.getContent() != null && response.getContent().length > 0) {
                String errorMessage = RuntimePresentationConverter.presentation(response.getContent());

                try {
                    Exception exception = client.serializer.deserialize(errorMessage, Exception.class, "exception", "Exception");
                    throw new RuntimeDebugClientException("Unsuccessful response from 1C:Enterprise" + exception.getDescr());
                } catch (IOException e) {
                    logger.debug("exception raw data `[{}]`", errorMessage);
                    throw new RuntimeDebugClientException("Error occurred while processing response", e);
                }
            } else {
                throw new RuntimeDebugClientException("Unsuccessful response from 1C:Enterprise status: " + response.getStatus() + " " + response.getReason());
            }
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            throw new RuntimeDebugClientException(e);
        }
    }

    private static AtomicReference<String> captureStrictResponseEncoding(Request request) {
        AtomicReference<String> encoding = new AtomicReference<>();
        request.onResponseHeader((response, field) -> {
            if (!HttpStatus.isSuccess(response.getStatus()) || field.getHeader() != HttpHeader.CONTENT_ENCODING) {
                return true;
            }
            encoding.accumulateAndGet(field.getValue(), (previous, current) ->
                    previous == null ? current : previous + "," + current);
            // Retain compressed bytes so strict responses can validate the complete frame.
            return false;
        });
        return encoding;
    }

    private static boolean isEmptyAcknowledgement(byte[] content, int status, String encoding)
            throws RuntimeDebugClientException {
        boolean empty = content == null || content.length == 0;
        boolean identity = isIdentityEncoding(encoding);
        if (empty || identity) {
            return empty && (status == HttpStatus.NO_CONTENT_204 || identity);
        }
        if ("deflate".equalsIgnoreCase(encoding)) {
            return isEmptyDeflateAcknowledgement(content);
        }
        return "gzip".equalsIgnoreCase(encoding) && isEmptyGzipAcknowledgement(content);
    }

    private static boolean isEmptyDeflateAcknowledgement(byte[] content) throws RuntimeDebugClientException {
        Inflater inflater = new Inflater(!hasZlibHeader(content));
        try {
            inflater.setInput(content);
            // One decoded byte is enough to reject a nonempty acknowledgement.
            return inflater.inflate(new byte[1]) == 0 && inflater.finished() && inflater.getRemaining() == 0;
        } catch (DataFormatException e) {
            throw new RuntimeDebugClientException("Malformed deflate debugger acknowledgement", e);
        } finally {
            inflater.end();
        }
    }

    private static boolean isEmptyGzipAcknowledgement(byte[] content) throws RuntimeDebugClientException {
        try {
            return inflateGzip(content, null);
        } catch (IOException e) {
            throw new RuntimeDebugClientException("Malformed gzip debugger acknowledgement", e);
        }
    }

    // Without an output buffer, stop as soon as an acknowledgement contains any decoded data.
    private static boolean inflateGzip(byte[] content, ByteArrayOutputStream decoded) throws IOException {
        if (content.length == 0) {
            throw new IOException("Missing gzip debugger response body");
        }
        ByteBuffer source = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN);
        byte[] buffer = new byte[decoded == null ? 1 : 8192];
        CRC32 checksum = new CRC32();
        Inflater inflater = new Inflater(true);
        try {
            while (source.hasRemaining()) {
                if (!readGzipMemberHeader(source)) {
                    throw new IOException("Malformed gzip member header in debugger response");
                }
                inflater.reset();
                inflater.setInput(source);
                checksum.reset();
                while (!inflater.finished()) {
                    int position = source.position();
                    int size = inflater.inflate(buffer);
                    if (size > 0) {
                        if (decoded == null) {
                            return false;
                        }
                        checksum.update(buffer, 0, size);
                        decoded.write(buffer, 0, size);
                    } else if (!inflater.finished() && source.position() == position) {
                        throw new IOException("Incomplete gzip member in debugger response");
                    }
                }
                if (source.remaining() < Long.BYTES
                        || Integer.toUnsignedLong(source.getInt()) != checksum.getValue()
                        || source.getInt() != (int) inflater.getBytesWritten()) {
                    throw new IOException("Missing or invalid gzip member trailer in debugger response");
                }
            }
            return true;
        } catch (DataFormatException e) {
            throw new IOException("Malformed gzip data in debugger response", e);
        } finally {
            inflater.end();
        }
    }

    private static boolean readGzipMemberHeader(ByteBuffer source) {
        int start = source.position();
        if (source.remaining() < 10 || Short.toUnsignedInt(source.getShort()) != 0x8B1F || source.get() != 8) {
            return false;
        }
        int flags = Byte.toUnsignedInt(source.get());
        if ((flags & 0xE0) != 0) {
            return false;
        }
        source.position(start + 10);
        // RFC 1952 orders the optional fields FEXTRA, FNAME, FCOMMENT and FHCRC.
        if ((flags & 0x04) != 0) {
            if (source.remaining() < Short.BYTES) {
                return false;
            }
            int extraLength = Short.toUnsignedInt(source.getShort());
            if (source.remaining() < extraLength) {
                return false;
            }
            source.position(source.position() + extraLength);
        }
        if (((flags & 0x08) != 0 && !skipGzipHeaderString(source))
                || ((flags & 0x10) != 0 && !skipGzipHeaderString(source))) {
            return false;
        }
        if ((flags & 0x02) == 0) {
            return true;
        }
        if (source.remaining() < Short.BYTES) {
            return false;
        }
        CRC32 checksum = new CRC32();
        checksum.update(source.array(), start, source.position() - start);
        return (checksum.getValue() & 0xFFFF) == Short.toUnsignedInt(source.getShort());
    }

    private static boolean skipGzipHeaderString(ByteBuffer source) {
        while (source.hasRemaining()) {
            if (source.get() == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasZlibHeader(byte[] content) {
        if (content.length < 2) {
            return false;
        }
        int compression = Byte.toUnsignedInt(content[0]);
        int header = (compression << 8) | Byte.toUnsignedInt(content[1]);
        // RFC 1950: deflate method, window no larger than 32 KiB and a valid header check.
        return (compression & 0x0F) == 8 && (compression >>> 4) <= 7 && header % 31 == 0;
    }

    private static boolean isIdentityEncoding(String encoding) {
        return encoding == null || "identity".equalsIgnoreCase(encoding);
    }

    private static byte[] decodeResponseBody(byte[] content, String encoding, ResponseBody bodyMode)
            throws IOException {
        if (!"deflate".equalsIgnoreCase(encoding)) {
            if (bodyMode == ResponseBody.LEGACY_XML || isIdentityEncoding(encoding)) {
                return content;
            }
            if (!"gzip".equalsIgnoreCase(encoding)) {
                throw new IOException("Unsupported debugger response content encoding: " + encoding);
            }
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            inflateGzip(content, decoded);
            return decoded.toByteArray();
        }
        if (!hasZlibHeader(content)) {
            return inflateDeflate(content, true, bodyMode);
        }
        try {
            return inflateDeflate(content, false, ResponseBody.COMPLETE_XML);
        } catch (IOException zlibFailure) {
            // A valid raw deflate stream can happen to start with the same bytes as a zlib header.
            try {
                return inflateDeflate(content, true, bodyMode);
            } catch (IOException rawFailure) {
                zlibFailure.addSuppressed(rawFailure);
                throw zlibFailure;
            }
        }
    }

    private static byte[] inflateDeflate(byte[] content, boolean raw, ResponseBody bodyMode) throws IOException {
        Inflater inflater = new Inflater(raw);
        try (ByteArrayInputStream source = new ByteArrayInputStream(content);
             InputStream in = new InflaterInputStream(source, inflater)) {
            byte[] decoded = in.readAllBytes();
            if (bodyMode == ResponseBody.COMPLETE_XML
                    && (!inflater.finished() || inflater.getRemaining() != 0 || source.available() != 0)) {
                throw new IOException("Incomplete or trailing deflate data in debugger XML response");
            }
            return decoded;
        } finally {
            inflater.end();
        }
    }

    private static String getCompleteXmlFromContent(ContentResponse response, String contentEncoding)
            throws IOException {
        byte[] content = response.getContent();
        if (content == null || content.length == 0) {
            throw new IOException("Missing encoded debugger XML response body");
        }
        byte[] body = decodeResponseBody(content, contentEncoding, ResponseBody.COMPLETE_XML);
        String charset = response.getEncoding();
        // RFC 7303 §3.2 gives the BOM priority over the HTTP charset.
        Charset byteCharset = xmlBomCharset(body);
        if (byteCharset == null && charset == null) {
            byteCharset = xmlPrologByteCharset(body);
        }
        Charset encoding;
        if (byteCharset != null) {
            encoding = byteCharset;
        } else if (charset != null) {
            encoding = lookupCharset(charset);
        } else {
            encoding = inferXmlCharset(body);
        }
        String xml = encoding.newDecoder().decode(ByteBuffer.wrap(body)).toString();
        int start = xml.startsWith("\ufeff") ? 1 : 0;
        int end = xml.length();
        while (start < end && isXmlWhitespace(xml.charAt(start))) {
            start++;
        }
        if (byteCharset != null) {
            validateXmlDeclaration(xml, start, byteCharset, body);
        }
        while (start < end && (xml.charAt(end - 1) == '\0' || isXmlWhitespace(xml.charAt(end - 1)))) {
            end--;
        }
        // Leave the complete XML for the parser. An interior NUL or a second document is not padding.
        return xml.substring(start, end);
    }

    private static Charset xmlBomCharset(byte[] body) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(body);
        if (body.length >= 4) {
            int bom = bytes.getInt(0);
            // The UTF-32LE BOM starts with the same two bytes as UTF-16LE.
            if (bom == 0xFFFE0000 || bom == 0x0000FEFF) {
                return lookupCharset(UTF_32_CHARSET_NAME);
            }
        }
        if (removeBOM(body) != 0) {
            return StandardCharsets.UTF_8;
        }
        if (body.length < 2) {
            return null;
        }
        int bom = Short.toUnsignedInt(bytes.getShort(0));
        return bom == 0xFEFF || bom == 0xFFFE ? StandardCharsets.UTF_16 : null;
    }

    private static Charset xmlPrologByteCharset(byte[] body) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(body);
        if (body.length >= 8) {
            long signature = bytes.getLong(0);
            if (signature == 0x0000003C0000003FL) {
                return lookupCharset(UTF_32_CHARSET_NAME + "BE");
            }
            if (signature == 0x3C0000003F000000L) {
                return lookupCharset(UTF_32_CHARSET_NAME + "LE");
            }
        }
        if (body.length >= 4) {
            int signature = bytes.getInt(0);
            if (signature == 0x003C003F) {
                return StandardCharsets.UTF_16BE;
            }
            if (signature == 0x3C003F00) {
                return StandardCharsets.UTF_16LE;
            }
        }
        return null;
    }

    private static Charset inferXmlCharset(byte[] body) throws IOException {
        int start = 0;
        while (start < body.length && isXmlWhitespace((char) body[start])) {
            start++;
        }
        int prefixLength = XML_DECLARATION_PREFIX.length;
        if (body.length - start <= prefixLength || !isXmlWhitespace((char) body[start + prefixLength])
                || Arrays.mismatch(body, start, start + prefixLength, XML_DECLARATION_PREFIX, 0, prefixLength) != -1) {
            return StandardCharsets.UTF_8;
        }

        // XML declarations in ASCII-compatible encodings can be read before decoding the document body.
        int end = start + prefixLength;
        while (end < body.length && body[end] != '<' && body[end] != '>') {
            end++;
        }
        if (end < body.length && body[end] == '>' && body[end - 1] == '?') {
            String declaration = new String(body, start, end + 1 - start, StandardCharsets.US_ASCII);
            Charset declared = xmlDeclaredCharset(declaration, 0);
            if (declared != null) {
                return declared;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void validateXmlDeclaration(String xml, int start, Charset byteCharset, byte[] body)
            throws IOException {
        Charset declared = xmlDeclaredCharset(xml, start);
        if (byteCharset.equals(StandardCharsets.UTF_16) || byteCharset.name().equals(UTF_32_CHARSET_NAME)) {
            String bomCharsetName = byteCharset.name() + (body[0] == (byte) 0xFF ? "LE" : "BE");
            if (declared == null || declared.equals(byteCharset) || declared.name().equals(bomCharsetName)) {
                return;
            }
        } else if (byteCharset.equals(declared) || (byteCharset.equals(StandardCharsets.UTF_8) && declared == null)) {
            return;
        }
        throw new IOException("Debugger XML encoding declaration does not match the response byte encoding");
    }

    private static Charset xmlDeclaredCharset(String xml, int start) throws IOException {
        int prefixEnd = start + XML_DECLARATION.length();
        if (prefixEnd >= xml.length() || !xml.startsWith(XML_DECLARATION, start)
                || !isXmlWhitespace(xml.charAt(prefixEnd))) {
            return null;
        }
        int end = xml.indexOf("?>", prefixEnd);
        if (end == -1) {
            return null;
        }
        Matcher encoding = XML_DECLARATION_ENCODING.matcher(xml).region(prefixEnd, end);
        return encoding.find() ? lookupCharset(encoding.group(2)) : null;
    }

    private static Charset lookupCharset(String name) throws IOException {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid debugger response charset", e);
        }
    }

    private static boolean isXmlWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }

    private static String getStringFromContent(ContentResponse response) throws IOException{
        String charset = response.getEncoding();
        String encodingHeader = response.getHeaders().get(HttpHeader.CONTENT_ENCODING);
        byte[] origContent = decodeResponseBody(response.getContent(), encodingHeader, ResponseBody.LEGACY_XML);
        int offset = removeBOM(origContent);
        int lenValue = origContent.length;
        for (int i = offset; i<lenValue; i++ ) {
            if(origContent[i] == 0) {
                lenValue = i;
                break;
            }
        }
        byte[] content = new byte[lenValue - offset];
        System.arraycopy(origContent, offset, content,0, content.length);
        Charset encoding = charset == null ? StandardCharsets.UTF_8 : Charset.forName(charset);
        String contentString = new String(content, encoding)
                .replaceFirst("^([\\W]+)<","<");
        contentString = Utils.normalizeXml(contentString);
        return contentString;
    }

    private static int removeBOM(byte[] origContent) {
        int offset = 0;
        if(origContent.length > 2
                && origContent[0] == (byte)-17
                && origContent[1] == (byte)-69
                && origContent[2] == (byte)-65) {
            offset = 3;
        }
        return offset;
    }

    private URI toUri(String uriString) {
        URI uri = URI.create(uriString);
        if (uri.getHost() != null) {
            return uri;
        } else {
            try {
                URL url = new URL(uriString);
                Field hostField = URI.class.getDeclaredField("host");
                hostField.setAccessible(true);
                hostField.set(uri, url.getHost());
                Field portField = URI.class.getDeclaredField("port");
                portField.setAccessible(true);
                portField.set(uri, url.getPort());
                return uri;
            } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException | MalformedURLException var6) {
                throw new IllegalArgumentException(var6);
            }
        }
    }
}
