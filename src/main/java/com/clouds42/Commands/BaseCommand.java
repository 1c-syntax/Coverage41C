package com.clouds42.Commands;

import com.clouds42.CommandLineOptions.ConnectionOptions;
import com.clouds42.Utils;
import org.scalasbt.ipcsocket.UnixDomainServerSocket;
import org.scalasbt.ipcsocket.Win32NamedPipeServerSocket;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

public abstract class BaseCommand {

    public abstract ConnectionOptions getConnectionOptions();

    private static final Map<String, ServerSocket> sockets = new HashMap<>();
    protected void closeSocket() throws IOException {
        ServerSocket serverSocket = getServerSocket(false);
        if (serverSocket != null) {
            serverSocket.close();
            String pipeName = Utils.getPipeName(getConnectionOptions());
            sockets.remove(pipeName);

        }
    }

    protected ServerSocket getServerSocket(boolean create) throws IOException {
        boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

        String pipeName = Utils.getPipeName(getConnectionOptions());
        ServerSocket serverSocket = sockets.get(pipeName);
        if(serverSocket == null && create) {
            if (isWindows) {
                serverSocket = new Win32NamedPipeServerSocket(pipeName);
            } else {
                serverSocket = new UnixDomainServerSocket(pipeName);
            }
            sockets.put(pipeName, serverSocket);
        }
        return serverSocket;
    }
}
