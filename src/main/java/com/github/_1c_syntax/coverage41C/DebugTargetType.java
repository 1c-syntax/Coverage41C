package com.github._1c_syntax.coverage41C;

import java.lang.module.ModuleDescriptor.Version;
import java.util.List;

public enum DebugTargetType {
    UNKNOWN(""),
    CLIENT("Client"),
    MANAGED_CLIENT("ManagedClient"),
    WEB_CLIENT("WEBClient"),
    COM_CONNECTOR("COMConnector"),
    SERVER("Server"),
    SERVER_EMULATION("ServerEmulation"),
    WEB_SERVICE("WEBService"),
    HTTP_SERVICE("HTTPService"),
    ODATA("OData"),
    JOB("JOB"),
    JOB_FILE_MODE("JobFileMode"),
    MOBILE_CLIENT("MobileClient"),
    MOBILE_SERVER("MobileServer"),
    MOBILE_JOB_FILE_MODE("MobileJobFileMode"),
    MOBILE_MANAGED_CLIENT("MobileManagedClient"),
    MOBILE_MANAGED_SERVER("MobileManagedServer");

    private final String literal;

    DebugTargetType(String literal) {
        this.literal = literal;
    }

    public String toString() {
        return this.literal;
    }

    public static List<DebugTargetType> getAutoconnectTargets() {

        var autoconnectTargets = new DebugTargetType[]{
                CLIENT,
                MANAGED_CLIENT,
                WEB_CLIENT,
                COM_CONNECTOR,
                SERVER,
                SERVER_EMULATION,
                WEB_SERVICE,
                HTTP_SERVICE,
                ODATA,
                JOB,
                JOB_FILE_MODE,
                MOBILE_CLIENT,
                MOBILE_SERVER,
                MOBILE_JOB_FILE_MODE,
                MOBILE_MANAGED_CLIENT,
                MOBILE_MANAGED_SERVER
        };

        return List.of(autoconnectTargets);
    }
}
