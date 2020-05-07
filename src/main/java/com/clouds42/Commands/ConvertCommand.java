package com.clouds42.Commands;

import com.clouds42.BuildConfig;
import com.clouds42.CommandLineOptions.*;
import com.clouds42.PipeMessages;
import com.clouds42.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FileInputStream;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = PipeMessages.CONVERT_COMMAND, mixinStandardHelpOptions = true, version = BuildConfig.APP_VERSION,
        description = "Convert results from internal uuid-based format",
        sortOptions = false)
public class ConvertCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Mixin
    private OutputOptions outputOptions;

    @Mixin
    private ConvertOptions convertOptions;

    @Mixin
    private MetadataOptions metadataOptions;

    @Mixin
    private FilterOptions filterOptions;

    @Mixin
    private LoggingOptions loggingOptions;

    @Override
    public Integer call() throws Exception {

        Map<URI, Map<BigDecimal, Boolean>> coverageData = new HashMap<URI,Map<BigDecimal, Boolean>>();

        Map<String, URI> uriListByKey = Utils.readMetadata(metadataOptions, filterOptions, coverageData);

        FileInputStream fileIS = new FileInputStream(convertOptions.getInputRawXmlFile());
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = builderFactory.newDocumentBuilder();
        Document xmlDocument = builder.parse(fileIS);
        NodeList fileNodeList = xmlDocument.getElementsByTagName("file");
        for(int fileNodeNumber = 0; fileNodeNumber < fileNodeList.getLength(); fileNodeNumber++) {
            Node fileNode = fileNodeList.item(fileNodeNumber);
            String fileKey = fileNode.getAttributes().getNamedItem("path").getTextContent();
            if (fileKey.startsWith("/")) {
                fileKey = fileKey.substring(1);
            }
            URI fileUri = uriListByKey.get(fileKey);
            if (fileUri == null) {
                logger.error("Can't find file key: " + fileKey);
                continue;
            }
            Map<BigDecimal, Boolean> coverMap = coverageData.get(fileUri);
            if (!coverMap.isEmpty()) {
                NodeList lineNoNodeList = fileNode.getChildNodes();
                for (int lineNoNodeNumber = 0; lineNoNodeNumber < lineNoNodeList.getLength(); lineNoNodeNumber++) {
                    Node lineNoNode = lineNoNodeList.item(lineNoNodeNumber);
                    if (!lineNoNode.getNodeName().equals("lineToCover")) {
                        continue;
                    }
                    if (!lineNoNode.getAttributes().getNamedItem("covered").getTextContent().equals("true")) {
                        continue;
                    }
                    BigDecimal lineNo = new BigDecimal(lineNoNode.getAttributes().getNamedItem("lineNumber").getTextContent());
                    if (!coverMap.containsKey(lineNo)) {
                        if (loggingOptions.isVerbose()) {
                            logger.info("Can't find line to cover " + lineNo + " in module " + fileUri);
                            try {
                                Stream<String> all_lines = Files.lines(Paths.get(fileUri));
                                String specific_line_n = all_lines.skip(lineNo.longValue() - 1).findFirst().get();
                                logger.info(">>> " + specific_line_n);
                            } catch (Exception e) {
                                logger.error(e.getLocalizedMessage());
                            }
                        }
                    } else {
                        coverMap.put(lineNo, true);
                    }
                }
            }
        }
        Utils.dumpCoverageFile(coverageData, metadataOptions, outputOptions);
        logger.info("Convert done");

        return CommandLine.ExitCode.OK;
    }
}
