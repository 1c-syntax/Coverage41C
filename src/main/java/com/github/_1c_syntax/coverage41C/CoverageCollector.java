package com.github._1c_syntax.coverage41C;

import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.clouds42.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;


public class CoverageCollector {

    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final Map<URI, Map<BigDecimal, Integer>> coverageData = new HashMap<>() {
        @Override
        public Map<BigDecimal, Integer> get(Object key) {
            Map<BigDecimal, Integer> map = super.get(key);
            if (map == null) {
                map = new HashMap<>();
                put((URI) key, map);
            }
            return map;
        }
    };

    private Map<String, URI> uriListByKey;
    Set<String> externalDataProcessorsUriSet = new HashSet<>();

    private boolean rawMode;
    private boolean verbose;
    private String extensionName;
    private String externalDataProcessorUrl;

    public void setOptions(boolean rawMode, boolean verbose, String extensionName, String externalDataProcessorUrl) {
        this.rawMode = rawMode;
        this.verbose = verbose;
        this.extensionName = extensionName;
        this.externalDataProcessorUrl = externalDataProcessorUrl;
    }

    @CheckForNull
    public URI getUri(String objectId, String propertyId) {
        String key = getUriKey(objectId, propertyId);
        URI uri;
        if (rawMode) {
            uri = URI.create("file:///" + key);
        } else {
            uri = uriListByKey.get(key);
        }

        return uri;
    }

    private String getUriKey(String objectId, String propertyId) {
        return Utils.getUriKey(objectId, propertyId);
    }

    public void addCoverage(URI uri, BigDecimal lineNo, Integer count) {
        Map<BigDecimal, Integer> coverMap = coverageData.get(uri);
        if (!coverMap.isEmpty() || rawMode) {
            if (!rawMode && !coverMap.containsKey(lineNo)) {
                if (verbose) {
                    logger.info("Can't find line to cover {} in module {}", lineNo, uri);
                    try {
                        Stream<String> all_lines = Files.lines(Paths.get(uri));
                        Optional<String> first = all_lines.skip(lineNo.longValue() - 1).findFirst();
                        if (first.isPresent()) {
                            String specific_line_n = first.get();
                            logger.info(">>> {}", specific_line_n);
                        }
                    } catch (Exception e) {
                        logger.error(e.getLocalizedMessage());
                    }
                }
            } else {
                int currentValue = coverMap.getOrDefault(lineNo, 0);
                if (currentValue < 0) {
                    currentValue = 0;
                }
                coverMap.put(lineNo, currentValue + count);
            }
        }
    }

    public void readMetadata(MetadataOptions metadataOptions) throws Exception {
        uriListByKey = Utils.readMetadata(metadataOptions, coverageData);
    }

    public void dumpCoverageFile(MetadataOptions metadataOptions, OutputOptions outputOptions) {
        Utils.dumpCoverageFile(coverageData, metadataOptions, outputOptions);
    }

    public boolean isFiltered(String moduleUrl, String moduleExtensionName) {
        if (verbose && !moduleUrl.isEmpty() && !externalDataProcessorsUriSet.contains(moduleUrl)) {
            logger.info("Found external data processor: {}", moduleUrl);
            externalDataProcessorsUriSet.add(moduleUrl);
        }

        if (rawMode) {
            return true;
        }

        return extensionName.equals(moduleExtensionName) && externalDataProcessorUrl.equals(moduleUrl);

    }

    public void clean() {
        coverageData.forEach((uri, bigDecimalIntegerMap) ->
                bigDecimalIntegerMap.replaceAll((k, v) -> 0));
    }
}
