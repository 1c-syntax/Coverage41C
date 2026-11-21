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

import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoBase;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGPingDebugUIResponse;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeDebugModelXmlSerializer;
import com._1c.g5.v8.dt.internal.debug.core.runtime.client.RuntimeExtendedMetaData;
import com.google.common.base.Preconditions;
import com.google.inject.Singleton;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.IllegalValueException;
import org.eclipse.emf.ecore.xmi.XMLHelper;
import org.eclipse.emf.ecore.xmi.XMLLoad;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.SAXXMLHandler;
import org.eclipse.emf.ecore.xmi.impl.XMLLoadImpl;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceImpl;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class MyRuntimeDebugModelXmlSerializer extends RuntimeDebugModelXmlSerializer {
    private static final String TRACE_OPTION = "/trace/dbgs/responses";
    private static final Charset ENCODING;
    private static final String TAG_OPENING = "<";
    private static final String TAG_CLOSING = "/>";
    private static final String RUNTIME_REQUEST_XML_TAG = "request";
    private static final String RUNTIME_RESPONSE_XML_TAG = "response";
    private static final String RUNTIME_RESPONSE_XML_SCHEME_NAME = "debugRDBGRequestResponse";
    private static final String CLASS_NAME_IMPL_PART = "Impl$";
    private static final String DELIMITER = ":";
    private final boolean collectingMeasurements;
    private final ThreadLocal<ResponseRead> extensionRead = new ThreadLocal<>();

    static {
        ENCODING = StandardCharsets.UTF_8;
    }

    public MyRuntimeDebugModelXmlSerializer() {
        this(false);
    }

    private MyRuntimeDebugModelXmlSerializer(boolean collectingMeasurements) {
        this.collectingMeasurements = collectingMeasurements;
    }

    public static MyRuntimeDebugModelXmlSerializer forMeasurementCollection() {
        return new MyRuntimeDebugModelXmlSerializer(true);
    }

    private static final class ResponseRead {
        final String from;
        final String to;
        String original;
        String renamed;

        ResponseRead(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    @Override
    public String serialize(EObject serializeFrom) throws IOException {
        Preconditions.checkArgument(serializeFrom != null);
        StringBuilder replaceFrom = new StringBuilder();
        replaceFrom.append("response");
        replaceFrom.append(":");
        replaceFrom.append(serializeFrom.getClass().getSimpleName().replaceAll("Impl$", ""));
        String replace = replaceFrom.toString();
        String xml = this.convertToXml(serializeFrom).trim();
        String result = '\ufeff' + this.replaceRootElement(xml, replace, "request");

        return result;
    }

    @Override
    public <T extends EObject> T deserialize(String xmlString, Class<T> deserializeTo) throws IOException {
        Preconditions.checkArgument(xmlString != null);
        Preconditions.checkArgument(deserializeTo != null);
        StringBuilder replaceTo = new StringBuilder();
        replaceTo.append("debugRDBGRequestResponse");
        replaceTo.append(":");
        replaceTo.append(deserializeTo.getSimpleName());
        return this.deserialize(xmlString, deserializeTo, "response", replaceTo.toString());
    }

    @Override
    public <T extends EObject> T deserialize(String xmlString, Class<T> deserializeTo, String replaceFrom, String replaceTo) throws IOException {
        Preconditions.checkArgument(xmlString != null);
        Preconditions.checkArgument(deserializeTo != null);
        Preconditions.checkArgument(replaceFrom != null);
        Preconditions.checkArgument(replaceTo != null);
        String xml = this.removeUtf8Bom(xmlString);
        if (getClass() == MyRuntimeDebugModelXmlSerializer.class) {
            return (T) loadResponse(xml, replaceFrom, replaceTo,
                    collectingMeasurements && deserializeTo == RDBGPingDebugUIResponse.class);
        }
        ResponseRead previous = extensionRead.get();
        extensionRead.set(new ResponseRead(replaceFrom, replaceTo));
        try {
            return (T) this.convertToEObject(this.replaceRootElement(xml, replaceFrom, replaceTo));
        } finally {
            if (previous == null) {
                extensionRead.remove();
            } else {
                extensionRead.set(previous);
            }
        }
    }

    private static EObject loadResponse(String xml, String replaceFrom, String replaceTo,
                                        boolean keepSiblingMeasures) throws IOException {
        XMLResourceImpl resource = new XMLResourceImpl() {
            @Override
            protected XMLLoad createXMLLoad() {
                return new XMLLoadImpl(createXMLHelper()) {
                    @Override
                    protected void handleErrors() throws IOException {
                        if (keepSiblingMeasures) {
                            resource.getErrors().removeIf(MyRuntimeDebugModelXmlSerializer::recordInvalidCommandAttribute);
                        }
                        super.handleErrors();
                    }

                    @Override
                    protected DefaultHandler makeDefaultHandler() {
                        return new ResponseRootHandler(resource, helper, options, replaceFrom, replaceTo);
                    }
                };
            }
        };
        return loadModel(xml, resource);
    }

    // Keep parse status on the command so the collector can apply its user filter before reporting it.
    private static final class InvalidAttribute extends AdapterImpl {
    }

    public static boolean hasInvalidAttribute(DBGUIExtCmdInfoBase command) {
        return command.eAdapters().stream().anyMatch(InvalidAttribute.class::isInstance);
    }

    private static boolean recordInvalidCommandAttribute(Resource.Diagnostic diagnostic) {
        if (!(diagnostic instanceof IllegalValueException)) {
            return false;
        }
        IllegalValueException error = (IllegalValueException) diagnostic;
        if (!(error.getFeature() instanceof EAttribute) || error.getFeature().isMany()
                || !(error.getWrappedException() instanceof IllegalArgumentException)) {
            return false;
        }
        EObject owner = error.getObject();
        for (EObject parent = owner; parent != null; parent = parent.eContainer()) {
            if (parent instanceof DBGUIExtCmdInfoBase) {
                DBGUIExtCmdInfoBase command = (DBGUIExtCmdInfoBase) parent;
                owner.eUnset(error.getFeature());
                if (!hasInvalidAttribute(command)) {
                    command.eAdapters().add(new InvalidAttribute());
                }
                return true;
            }
        }
        return false;
    }

    protected EObject convertToEObject(String xmlString) throws IOException {
        ResponseRead read = extensionRead.get();
        if (read != null && xmlString.equals(read.renamed)) {
            // A delegating extension still validates the original document, including its matching end tag.
            return loadResponse(read.original, read.from, read.to, false);
        }
        return loadModel(xmlString, new XMLResourceImpl());
    }

    private static EObject loadModel(String xmlString, XMLResourceImpl resource) throws IOException {
        Map<Object, Object> loadOptions = new HashMap();
        loadOptions.put("EXTENDED_META_DATA", true);
        loadOptions.put("LAX_FEATURE_PROCESSING", true);
        resource.setEncoding(ENCODING.name());
        resource.load(new InputSource(new StringReader(xmlString)), loadOptions);
        return resource.getContents().get(0);
    }

    private static final class ResponseRootHandler extends SAXXMLHandler {
        private final String originalName;
        private final String modelName;
        private int depth;

        private ResponseRootHandler(XMLResource resource, XMLHelper helper, Map<?, ?> options,
                                    String originalName, String modelName) {
            super(resource, helper, options);
            this.originalName = originalName;
            this.modelName = modelName;
        }

        // Alias the outer element for EMF while the SAX parser validates the original document.
        @Override
        public void startElement(String uri, String localName, String name) {
            super.startElement(uri, localName, nameForModel(name, depth++));
        }

        @Override
        public void endElement(String uri, String localName, String name) {
            super.endElement(uri, localName, nameForModel(name, --depth));
        }

        private String nameForModel(String name, int elementDepth) {
            return elementDepth == 0 && originalName.equals(name) ? modelName : name;
        }
    }

    protected String convertToXml(EObject eObject) throws IOException {
        XMLResourceImpl resource = new XMLResourceImpl();
        resource.setEncoding(ENCODING.name());
        resource.getContents().add(eObject);
        Map<Object, Object> saveOptions = new HashMap();
        saveOptions.put("EXTENDED_META_DATA", new RuntimeExtendedMetaData());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        resource.save(outputStream, saveOptions);
        return new String(outputStream.toByteArray(), ENCODING);
    }

    protected String replaceRootElement(String xmlString, String replaceFromTag, String replaceToTag) {

        String renamed = xmlString.replaceFirst("<" + replaceFromTag, "<" + replaceToTag);
        if (!renamed.endsWith("/>")) {
            StringBuilder builder = new StringBuilder(renamed);
            int replaceingIndex = renamed.lastIndexOf(replaceFromTag);
            if (replaceingIndex != -1) {
                builder.replace(replaceingIndex, replaceingIndex + replaceFromTag.length(), replaceToTag);
            }

            renamed = builder.toString();
        }

        ResponseRead read = extensionRead.get();
        if (read != null && read.from.equals(replaceFromTag) && read.to.equals(replaceToTag)) {
            read.original = xmlString;
            read.renamed = renamed;
        }
        return renamed;
    }

    protected String removeUtf8Bom(String string) {
        return string.startsWith(String.valueOf('\ufeff')) ? string.substring(1) : string;
    }
}
