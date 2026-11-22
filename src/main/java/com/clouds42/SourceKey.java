/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2026
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/** Source identity is encoded in the raw path, so it survives the existing XML transport. */
public final class SourceKey {
    private static final String PREFIX = "source-v1/";

    private SourceKey() { }

    public static String of(String objectId, String propertyId, String extension, String url) {
        return qualify(moduleKey(objectId, propertyId), extension, url);
    }

    public static String moduleKey(String objectId, String propertyId) {
        requirePathSegment(objectId, "objectID");
        requirePathSegment(propertyId, "propertyID");
        String key = Utils.getUriKey(objectId, propertyId);
        String path = "/" + key;
        // RAW serialization reads URI.getPath(). Escapes, query and fragment data cannot change the key.
        if (!path.equals(URI.create(path).getPath())) {
            throw new IllegalArgumentException("Debugger module ID does not round-trip through a URI path");
        }
        return key;
    }

    private static void requirePathSegment(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0 || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid " + field + " in debugger module ID");
        }
    }

    public static String qualify(String legacyKey, String extension, String url) {
        return PREFIX + encode(extension) + "/" + encode(url) + "/" + legacyKey;
    }

    private static String encode(String value) {
        return value == null || value.isEmpty() ? "-"
                : Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isQualified(String key) {
        // A legacy key may have source-v1 as its object ID. Qualified keys contain more separators.
        return key.startsWith(PREFIX) && key.indexOf('/', PREFIX.length()) >= 0;
    }

    public static String normalizeRawKey(String key, boolean declaredRaw) {
        boolean qualified = isQualified(key);
        String[] module = legacy(key).split("/", -1);
        // Legacy RAW has no format marker. Finished reports identify modules by BSL file paths.
        if (module.length != 2 || (!declaredRaw && !qualified && module[1].toLowerCase(Locale.ROOT).endsWith(".bsl"))) {
            throw new IllegalArgumentException("Invalid RAW module path: " + key);
        }
        String moduleKey;
        try {
            moduleKey = moduleKey(module[0], module[1]);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid RAW module path: " + key, error);
        }
        if (qualified) {
            String[] parts = qualifiedParts(key);
            return qualify(moduleKey, decodeSourcePart(key, parts[1]), decodeSourcePart(key, parts[2]));
        }
        return moduleKey;
    }

    public static boolean matchesSource(String key, Map<String, String> selectedUrls) {
        String[] parts = qualifiedParts(key);
        String extension = decodeSourcePart(key, parts[1]);
        return decodeSourcePart(key, parts[2]).equals(selectedUrls.get(extension));
    }

    public static String selectionHint(String key) {
        return selectionHint(key, false);
    }

    public static String selectionHint(String key, boolean namedSources) {
        String[] parts = qualifiedParts(key);
        String extension = decodeSourcePart(key, parts[1]);
        String url = decodeSourcePart(key, parts[2]);
        if (namedSources && url.isEmpty()) {
            return "--source " + extension + "=DIR";
        }
        if (extension.isEmpty() && url.isEmpty()) {
            return "main configuration (omit -e and -x)";
        }
        return ((extension.isEmpty() ? "" : "-e " + extension + " ")
                + (url.isEmpty() ? "" : "-x " + url)).trim();
    }

    private static String decodeSourcePart(String key, String part) {
        if (part.equals("-")) {
            return "";
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
            if (!encode(decoded).equals(part)) {
                throw new IllegalArgumentException("Expected unpadded Base64 URL-safe UTF-8");
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid raw source key: " + key, error);
        }
    }

    public static String legacy(String key) {
        if (!isQualified(key)) {
            return key;
        }
        String[] parts = qualifiedParts(key);
        return parts[3] + "/" + parts[4];
    }

    private static String[] qualifiedParts(String key) {
        String[] parts = key.split("/", -1);
        if (parts.length != 5 || parts[1].isEmpty() || parts[2].isEmpty()
                || parts[3].isEmpty() || parts[4].isEmpty()) {
            throw new IllegalArgumentException("Invalid raw source key: " + key);
        }
        return parts;
    }
}
