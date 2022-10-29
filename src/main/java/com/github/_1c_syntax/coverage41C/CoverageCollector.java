package com.github._1c_syntax.coverage41C;

import com.clouds42.CommandLineOptions.MetadataOptions;
import com.clouds42.CommandLineOptions.OutputOptions;
import com.clouds42.Utils;

import java.math.BigDecimal;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class CoverageCollector {

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

    public Map<String, URI> getUriListByKey() {
        return uriListByKey;
    }

    public void readMetadata(MetadataOptions metadataOptions) throws Exception {
        uriListByKey = Utils.readMetadata(metadataOptions, coverageData);
    }

    public void dumpCoverageFile(MetadataOptions metadataOptions, OutputOptions outputOptions) {
        Utils.dumpCoverageFile(coverageData, metadataOptions, outputOptions);
    }

    public Map<BigDecimal, Integer> get(URI uri) {
        return coverageData.get(uri);
    }

    public void clean() {
        coverageData.forEach((uri, bigDecimalIntegerMap) ->
                bigDecimalIntegerMap.replaceAll((k, v) -> 0));
    }
}
