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

import org.eclipse.emf.common.util.Enumerator;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum DebugTargetType implements Enumerator {
    UNKNOWN(0, "Unknown", "Unknown"),
    CLIENT(1, "Client", "Client"),
    MANAGED_CLIENT(2, "ManagedClient", "ManagedClient"),
    WEB_CLIENT(3, "WEBClient", "WEBClient"),
    COM_CONNECTOR(4, "COMConnector", "COMConnector"),
    SERVER(5, "Server", "Server"),
    SERVER_EMULATION(6, "ServerEmulation", "ServerEmulation"),
    WEB_SERVICE(7, "WEBService", "WEBService"),
    HTTP_SERVICE(8, "HTTPService", "HTTPService"),
    ODATA(9, "OData", "OData"),
    JOB(10, "JOB", "JOB"),
    JOB_FILE_MODE(11, "JobFileMode", "JobFileMode"),
    MOBILE_CLIENT(12, "MobileClient", "MobileClient"),
    MOBILE_SERVER(13, "MobileServer", "MobileServer"),
    MOBILE_JOB_FILE_MODE(14, "MobileJobFileMode", "MobileJobFileMode"),
    MOBILE_MANAGED_CLIENT(15, "MobileManagedClient", "MobileManagedClient");

    public static final int UNKNOWN_VALUE = 0;
    public static final int CLIENT_VALUE = 1;
    public static final int MANAGED_CLIENT_VALUE = 2;
    public static final int WEB_CLIENT_VALUE = 3;
    public static final int COM_CONNECTOR_VALUE = 4;
    public static final int SERVER_VALUE = 5;
    public static final int SERVER_EMULATION_VALUE = 6;
    public static final int WEB_SERVICE_VALUE = 7;
    public static final int HTTP_SERVICE_VALUE = 8;
    public static final int ODATA_VALUE = 9;
    public static final int JOB_VALUE = 10;
    public static final int JOB_FILE_MODE_VALUE = 11;
    public static final int MOBILE_CLIENT_VALUE = 12;
    public static final int MOBILE_SERVER_VALUE = 13;
    public static final int MOBILE_JOB_FILE_MODE_VALUE = 14;
    public static final int MOBILE_MANAGED_CLIENT_VALUE = 15;
    private static final DebugTargetType[] VALUES_ARRAY = new DebugTargetType[]{UNKNOWN, CLIENT, MANAGED_CLIENT, WEB_CLIENT, COM_CONNECTOR, SERVER, SERVER_EMULATION, WEB_SERVICE, HTTP_SERVICE, ODATA, JOB, JOB_FILE_MODE, MOBILE_CLIENT, MOBILE_SERVER, MOBILE_JOB_FILE_MODE, MOBILE_MANAGED_CLIENT};
    public static final List<DebugTargetType> VALUES = Collections.unmodifiableList(Arrays.asList(VALUES_ARRAY));
    private final int value;
    private final String name;
    private final String literal;

    private DebugTargetType(int value, String name, String literal) {
        this.value = value;
        this.name = name;
        this.literal = literal;
    }

    public int getValue() {
        return this.value;
    }

    public String getName() {
        return this.name;
    }

    public String getLiteral() {
        return this.literal;
    }

    public String toString() {
        return this.literal;
    }
}

