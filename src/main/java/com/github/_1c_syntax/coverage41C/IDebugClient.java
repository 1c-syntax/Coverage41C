package com.github._1c_syntax.coverage41C;

import java.util.List;
import java.util.UUID;

public interface IDebugClient {
    void connect(String password) throws DebugClientException;

    void connectTargets(List<String> debugAreaNames, List<DebugTargetType> debugTargetTypes) throws DebugClientException;

    void disconnect() throws DebugClientException;

    void enableProfiling(UUID measureUuid) throws DebugClientException;

    void disableProfiling() throws DebugClientException;

    void ping() throws DebugClientException;
}
