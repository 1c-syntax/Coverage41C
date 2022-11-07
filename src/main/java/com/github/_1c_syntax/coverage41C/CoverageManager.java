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

    private final IDebugClient debugClient;

    private final DebuggerOptions debuggerOptions;

    public CoverageManager(CoverageCollector collector,
                           ConnectionOptions connectionOptions,
                           DebuggerOptions debuggerOptions) {

        this.debuggerOptions = debuggerOptions;

        debugClient = new DebugClientEDT(collector,
                connectionOptions.getDebugServerUrl(),
                connectionOptions.getInfobaseAlias());
    }

    public void connect() throws DebugClientException {

        debugClient.connect(debuggerOptions.getPassword());

        var areaNames = debuggerOptions.getDebugAreaNames();
        var debugTargets = debuggerOptions.getAutoconnectTargets();
        debugClient.connectTargets(areaNames, debugTargets);
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
}
