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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Source identity is encoded in the raw path, so it survives the existing XML transport. */
public final class SourceKey {
    private static final String PREFIX = "source-v1/";

    private SourceKey() { }

    public static String of(String objectId, String propertyId, String extension, String url) {
        return qualify(moduleKey(objectId, propertyId), extension, url);
    }

    public static String moduleKey(String objectId, String propertyId) {
        validateModuleComponent(objectId, "object");
        validateModuleComponent(propertyId, "property");
        return Utils.getUriKey(objectId, propertyId);
    }

    private static void validateModuleComponent(String value, String component) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Invalid module " + component + " identifier");
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
        return key.startsWith(PREFIX);
    }

    public static String selectionHint(String key) {
        legacy(key); // Validate structure before decoding source identity.
        String[] parts = key.split("/", -1);
        try {
            String extension = parts[1].equals("-") ? ""
                    : new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String url = parts[2].equals("-") ? ""
                    : new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            if (extension.isEmpty() && url.isEmpty()) {
                return "main configuration (omit -e and -x)";
            }
            return ((extension.isEmpty() ? "" : "-e " + extension + " ")
                    + (url.isEmpty() ? "" : "-x " + url)).trim();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid raw source key: " + key, error);
        }
    }

    public static String legacy(String key) {
        if (!isQualified(key)) {
            return key;
        }
        String[] parts = key.split("/", -1);
        if (parts.length != 5 || parts[1].isEmpty() || parts[2].isEmpty()
                || parts[3].isEmpty() || parts[4].isEmpty()) {
            throw new IllegalArgumentException("Invalid raw source key: " + key);
        }
        return parts[3] + "/" + parts[4];
    }
}
