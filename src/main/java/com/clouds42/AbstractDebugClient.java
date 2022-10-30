/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2022
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
import com._1c.g5.v8.dt.internal.debug.core.model.RuntimePresentationConverter;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugModelXmlSerializer;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public abstract class AbstractDebugClient {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    protected RuntimeDebugModelXmlSerializer serializer;

    public AbstractDebugClient(RuntimeDebugModelXmlSerializer serializer) {
        this.serializer = serializer;
    }

    protected HttpClient createHttpClient() {
        return this.createHttpClient(60000L);
    }

    protected HttpClient createHttpClient(long idleTimeout) {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setTrustAll(true);
        var httpClient = new HttpClient(sslContextFactory);
        httpClient.setFollowRedirects(true);
        httpClient.setUserAgentField(new HttpField("User-Agent", "1CV8"));
        httpClient.setIdleTimeout(idleTimeout);
        return httpClient;
    }

    protected Request buildRequest(HttpClient httpClient, HttpMethod method, String componentUrl) {
        return httpClient.newRequest(this.toUri(componentUrl))
                .method(method)
                .header(HttpHeader.ACCEPT, "application/xml")
                .header(HttpHeader.CONNECTION, HttpHeader.KEEP_ALIVE.asString())
                .header(HttpHeader.CONTENT_TYPE, "application/xml")
                .header("1C-ApplicationName", "1C:Enterprise DT");
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

    protected static <T extends EObject> T performRuntimeHttpRequest(AbstractDebugClient abstractDebugClient, Request request, EObject requestContent, Class<T> responseClass) throws RuntimeDebugClientException {
        try {
            if (requestContent != null) {
                try {
                    String serializedRequest = abstractDebugClient.serializer.serialize(requestContent);
                    request.content(new StringContentProvider(serializedRequest));
                } catch (IOException e) {
                    throw new RuntimeDebugClientException("Error occurred while processing request", e);
                }
            }

            FutureResponseListener listener = new FutureResponseListener(request, 2147483647);
            request.send(listener);
            ContentResponse response = listener.get(60L, TimeUnit.SECONDS);
            int status = response.getStatus();
            if (HttpStatus.isSuccess(status)) {
                if (responseClass != null && status != 204) {
                    String type = response.getMediaType();
                    if (!type.equalsIgnoreCase("application/xml")) {
                        return null;
                    }
                    try {
                        String contentString = getStringFromContent(response);
                        return abstractDebugClient.serializer.deserialize(contentString, responseClass);
                    } catch (IOException e) {
                        logger.error("Get stuff from server.");
                        e.printStackTrace();
                        throw new RuntimeDebugClientException(
                                "Error occurred while processing response", e);
                    }
                } else {
                    return null;
                }
            } else if (response.getContent() != null) {
                String errorMessage = RuntimePresentationConverter.presentation(response.getContent());

                try {
                    Exception exception = (Exception) abstractDebugClient.serializer.deserialize(errorMessage, EObject.class, "exception", "Exception");
                    throw new RuntimeDebugClientException("Unsuccessful response from 1C:Enterprise" + exception.getMessage());
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

    private static String getStringFromContent(ContentResponse response) throws IOException {
        HttpFields headers = response.getHeaders();
        String charset = response.getEncoding();
        HttpField contentEncoding = headers.getField(HttpHeader.CONTENT_ENCODING);
        byte[] origContent = response.getContent();
        HttpField len = headers.getField(HttpHeader.CONTENT_LENGTH);
        int lenValue = len.getIntValue();
        if (contentEncoding != null && "deflate".equalsIgnoreCase(contentEncoding.getValue())) {
            try (InputStream bis = new ByteArrayInputStream(origContent)) {
                try (InputStream in = new InflaterInputStream(bis, new Inflater(true))) {
                    origContent = in.readAllBytes();
                    lenValue = origContent.length;
                }
            }
        }
        int offset = removeBOM(origContent);
        for (int i = offset; i < lenValue; i++) {
            if (origContent[i] == 0) {
                lenValue = i;
                break;
            }
        }
        byte[] content = new byte[lenValue - offset];
        System.arraycopy(origContent, offset, content, 0, content.length);
        String contentString = new String(content, Charset.forName(charset))
                .replaceFirst("^([\\W]+)<", "<");
        contentString = Utils.normalizeXml(contentString);
        return contentString;
    }

    private static int removeBOM(byte[] origContent) {
        int offset = 0;
        if (origContent.length > 2
                && origContent[0] == (byte) -17
                && origContent[1] == (byte) -69
                && origContent[2] == (byte) -65) {
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
            } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException |
                     MalformedURLException var6) {
                throw new IllegalArgumentException(var6);
            }
        }
    }
}
