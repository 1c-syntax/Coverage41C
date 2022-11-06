package com.github._1c_syntax.coverage41C;

import com.clouds42.CommandLineOptions.*;
import com.github._1c_syntax.coverage41C.EDT.DebugClientEDT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.module.ModuleDescriptor;
import java.util.List;
import java.util.UUID;

public class CoverageManager {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final DebugClientEDT debugClient;

    private final ConnectionOptions connectionOptions;
    private final DebuggerOptions debuggerOptions;

    public CoverageManager(CoverageCollector collector,
                           ConnectionOptions connectionOptions,
                           DebuggerOptions debuggerOptions) {

        this.connectionOptions = connectionOptions;
        this.debuggerOptions = debuggerOptions;

        debugClient = new DebugClientEDT();
        debugClient.setCollector(collector);
    }

    public void connect() throws DebugClientException {
        debugClient.configure(
                connectionOptions.getDebugServerUrl(),
                connectionOptions.getInfobaseAlias());

        debugClient.connect(debuggerOptions.getPassword());

        ModuleDescriptor.Version apiver = ModuleDescriptor.Version.parse(debugClient.getApiVersion());
        var debugTargets = debuggerOptions.getAutoconnectTargets();

        debugClient.setupSettings(
                debuggerOptions.getDebugAreaNames(),
                filterTargetsByApiVersion(debugTargets, apiver));

        debugClient.connectTargets(debuggerOptions);
    }

    public void disconnect() throws DebugClientException {
        debugClient.disconnect();
    }

    public void start(UUID measureUuid) throws DebugClientException {
        debugClient.enableProfiling(measureUuid);
    }

    public void stop() throws DebugClientException {
        debugClient.disableProfiling();
    }

    public void ping() throws DebugClientException {
        debugClient.ping();
    }

    private static List<DebugTargetType> filterTargetsByApiVersion(List<DebugTargetType> debugTargets, ModuleDescriptor.Version ApiVersion) {
        List<DebugTargetType> debugTypes = new java.util.ArrayList<>(debugTargets);

        if (ModuleDescriptor.Version.parse("8.3.16").compareTo(ApiVersion) > 0) {
            debugTypes.remove(DebugTargetType.MOBILE_MANAGED_CLIENT);
            logger.info("[{}] was removed", DebugTargetType.MOBILE_MANAGED_CLIENT);
        }
        return debugTypes;
    }
}
