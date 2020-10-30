package com.clouds42.Commands;

import com.clouds42.CommandLineOptions.ConnectionOptions;
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import org.scalasbt.ipcsocket.UnixDomainSocket;
import org.scalasbt.ipcsocket.Win32NamedPipeSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Mixin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.net.Socket;
import java.util.concurrent.Callable;

public class SendMessageCommand extends BaseCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Mixin
    private ConnectionOptions connectionOptions;

    private String getCommandName() {
        return null;
    }

    @Override
    public Integer call() throws Exception {

        boolean isWindows = System.getProperty ("os.name").toLowerCase().contains("win");

        String pipeName = Utils.getPipeName(connectionOptions);

        logger.info("Trying to send command to main application...");
        Socket client;
        if (isWindows) {
            client = new Win32NamedPipeSocket(pipeName);
        } else {
            client = new UnixDomainSocket(pipeName);
        }
        PrintWriter pipeOut = new PrintWriter(client.getOutputStream(), true);
        BufferedReader pipeIn = new BufferedReader(new InputStreamReader(client.getInputStream()));
        String commandText = new CommandLine(this).getCommandName();
        pipeOut.println(commandText);
        logger.info("Command send finished: " + commandText);
        String result = "";
        for(int i = 0; i < 10; i++) {
            logger.info("Try: " + i);
            try {
                result = pipeIn.readLine();
                break;
            } catch(IOException e) {
                logger.info("Can't read answer from main app...");
                Thread.sleep(10);
            }
        }
        if (result.equals(PipeMessages.OK_RESULT)) {
            logger.info("Command success: " + commandText);
            client.close();
            return CommandLine.ExitCode.OK;
        } else {
            logger.info("Command failed: " + commandText);
            client.close();
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    @Override
    public ConnectionOptions getConnectionOptions() {
        return connectionOptions;
    }
}
