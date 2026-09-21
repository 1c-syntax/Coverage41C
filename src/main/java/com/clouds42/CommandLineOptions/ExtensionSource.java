/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2024
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
package com.clouds42.CommandLineOptions;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * Дерево исходников расширения: имя расширения в информационной базе и путь к его выгрузке.
 * Задаётся опцией {@code --extension <name>=<path>}.
 */
public class ExtensionSource {

    private final String name;
    private final String path;

    public ExtensionSource(String name, String path) {
        this.name = name;
        this.path = path;
    }

    public static ExtensionSource parse(String value) {
        int separatorIndex = value.indexOf('=');
        if (separatorIndex < 0) {
            throw new IllegalArgumentException(
                    String.format("Expected <extensionName>=<path>, but was '%s'", value));
        }
        String name = value.substring(0, separatorIndex);
        String path = value.substring(separatorIndex + 1);
        if (name.isEmpty()) {
            throw new IllegalArgumentException(String.format("Extension name is empty in '%s'", value));
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException(String.format("Extension sources path is empty in '%s'", value));
        }
        return new ExtensionSource(name, path);
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return name + "=" + path;
    }

    public static class Converter implements ITypeConverter<ExtensionSource> {
        @Override
        public ExtensionSource convert(String value) {
            try {
                return parse(value);
            } catch (IllegalArgumentException e) {
                throw new TypeConversionException(e.getMessage());
            }
        }
    }
}
