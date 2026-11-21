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

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/** Selects debugger bundles that the JVM running the Unix launcher can load. */
public final class EdtLocation {
    private static final String DEBUG_PREFIX = "com._1c.g5.v8.dt.debug.";
    private static final Pattern MAIN_CORE = Pattern.compile("core_[0-9]");
    private static final String MEASURE_REQUEST =
            "com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGSetMeasureModeRequest";
    private static final int MAX_CLASS_VERSION = 44 + Runtime.version().feature();
    private static final Object PROBE_LOCK = new Object();
    private static Process activeProbe;
    private static boolean stopping;

    private EdtLocation() { }

    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("--probe")) {
            List<String> bundles = args.length == 2 ? bundlePaths(args[1]) : Arrays.asList(args).subList(2, args.length);
            boolean compatible;
            try (URLClassLoader dependencies = applicationLibraries()) {
                compatible = compatibleInstallation(bundles, requiredClasses(), dependencies);
            }
            // SDK initialization may leave non-daemon threads behind, even on success.
            System.exit(compatible ? 0 : 1);
        }
        BufferedReader locations = new BufferedReader(new InputStreamReader(System.in));
        boolean launcher = args.length == 1 && args[0].equals("--launcher");
        boolean protocol = launcher || (args.length > 0 && args[0].equals("--protocol"));
        List<String> options = launcher
                ? EdtProbeOptions.readFrom(locations)
                : EdtProbeOptions.fromVmArguments(ManagementFactory.getRuntimeMXBean().getInputArguments());
        Runtime.getRuntime().addShutdownHook(new Thread(EdtLocation::stopActiveProbe, "edt-probe-cleanup"));
        String root;
        while ((root = locations.readLine()) != null) {
            if (root.isEmpty()) {
                continue;
            }
            List<String> bundles = bundlePaths(root);
            if (!bundles.isEmpty() && probeInstallation(root, bundles, options)) {
                String selected = root + "/plugins";
                String location = protocol ? "COVERAGE41C_EDT_LOCATION=" + selected : selected;
                if (launcher) {
                    // The Unix script reads LF records, including when the selector runs on a native Windows JVM.
                    String classpath = String.join(File.pathSeparator, bundles).replace("%", "%25").replace("\n", "%0A");
                    System.out.print(location + "\nCOVERAGE41C_EDT_CLASSPATH=" + classpath + "\n");
                } else {
                    System.out.println(location);
                }
                return;
            }
        }
    }

    private static List<String> bundlePaths(String root) {
        File plugins = new File(root, "plugins");
        // URLClassLoader can open paths which Java's -classpath cannot represent.
        File[] files = plugins.listFiles(file -> file.getName().startsWith(DEBUG_PREFIX)
                && file.getPath().indexOf(File.pathSeparatorChar) < 0 && isBundle(file));
        List<String> paths = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                paths.add(root + "/plugins/" + file.getName());
            }
        }
        return paths;
    }

    private static boolean isBundle(File file) {
        if (!file.canRead()) {
            return false;
        }
        if (file.isDirectory()) {
            String[] entries = file.list();
            return entries != null && entries.length > 0;
        }
        if (!file.isFile()) {
            return false;
        }
        if (file.getName().endsWith(".jar")) {
            return true;
        }
        // Eclipse archives can have any suffix. Ignore unrelated files next to the bundles.
        try (ZipFile ignored = new ZipFile(file)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean probeInstallation(String root, List<String> bundles, List<String> options) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.addAll(options);
        command.addAll(Arrays.asList("-cp", new File(applicationDirectory(), "*").getPath(),
                EdtLocation.class.getName(), "--probe", root));
        command.addAll(bundles);
        // Every candidate gets fresh SDK and EMF static state; none is loaded into the selector.
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeAll(Arrays.asList(
                "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        Process probe;
        synchronized (PROBE_LOCK) {
            if (stopping) {
                throw new IOException("EDT discovery is stopping");
            }
            activeProbe = builder.start();
            probe = activeProbe;
        }
        try {
            return probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while checking EDT", e);
        } finally {
            synchronized (PROBE_LOCK) {
                stopProbe(probe);
                activeProbe = null;
            }
        }
    }

    private static void stopActiveProbe() {
        synchronized (PROBE_LOCK) {
            stopping = true;
            stopProbe(activeProbe);
            activeProbe = null;
        }
    }

    private static void stopProbe(Process probe) {
        if (probe != null && probe.isAlive()) {
            probe.destroyForcibly();
            try {
                probe.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<String> requiredClasses() throws IOException {
        try (InputStream input = EdtLocation.class.getResourceAsStream("/META-INF/coverage41c/required-classes.txt")) {
            if (input == null) {
                throw new IOException("The distribution is missing its list of required EDT classes");
            }
            return Arrays.asList(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n"));
        }
    }

    private static URLClassLoader applicationLibraries() throws IOException {
        // Only the installed application's lib directory may supply dependencies to the candidate.
        // In particular, do not inherit an EDT installation from the invoking JVM's classpath.
        File appJar = applicationJar();
        File directory = appJar.getParentFile();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jar")
                && !name.startsWith(DEBUG_PREFIX) && !name.equals(appJar.getName()));
        if (files == null) {
            throw new IOException("Cannot list the installed application libraries in " + directory);
        }
        List<URL> libraries = new ArrayList<>();
        for (File file : files) {
            libraries.add(file.toURI().toURL());
        }
        return new URLClassLoader(libraries.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    private static File applicationDirectory() throws IOException {
        return applicationJar().getParentFile();
    }

    private static File applicationJar() throws IOException {
        try {
            return new File(EdtLocation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Cannot locate the installed application libraries", e);
        }
    }

    private static boolean compatibleInstallation(List<String> bundles, List<String> required, ClassLoader dependencies)
            throws IOException {
        List<URL> candidate = new ArrayList<>();
        boolean core = false;
        boolean model = false;
        for (String path : bundles) {
            File file = new File(path);
            candidate.add(file.toURI().toURL());
            String bundle = file.getName().substring(DEBUG_PREFIX.length());
            boolean hasClasses;
            try {
                hasClasses = verifyBundleClasses(file);
            } catch (IOException | UncheckedIOException e) {
                return false;
            }
            // Version-specific core_v* fragments do not supply the main core classes.
            core |= hasClasses && MAIN_CORE.matcher(bundle).lookingAt();
            model |= hasClasses && bundle.startsWith("model_");
        }
        if (!core || !model) {
            return false;
        }
        List<URL> probeClasspath = new ArrayList<>(candidate);
        probeClasspath.add(applicationJar().toURI().toURL());
        try (URLClassLoader loader = new URLClassLoader(probeClasspath.toArray(new URL[0]), dependencies)) {
            for (String name : required) {
                Class<?> type = Class.forName(name, true, loader);
                if (type.getClassLoader() != loader
                        || !candidate.contains(type.getProtectionDomain().getCodeSource().getLocation())) {
                    return false;
                }
            }
            return linkRequiredMembers(loader) && createRequiredModels(loader);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    private static boolean verifyBundleClasses(File bundle) throws IOException {
        if (bundle.isDirectory()) {
            Path root = bundle.toPath();
            try (Stream<Path> entries = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
                Iterator<Path> classes = entries.filter(path -> isClassEntry(root.relativize(path).toString()
                        .replace(File.separatorChar, '/')) && Files.isRegularFile(path)).iterator();
                boolean hasClasses = classes.hasNext();
                while (classes.hasNext()) {
                    verifyClass(Files.newInputStream(classes.next()));
                }
                return hasClasses;
            }
        }
        try (JarFile archive = new JarFile(bundle, false, ZipFile.OPEN_READ, JarFile.runtimeVersion());
             Stream<JarEntry> entries = archive.versionedStream()) {
            Iterator<JarEntry> classes = entries.filter(entry -> isClassEntry(entry.getName())).iterator();
            boolean hasClasses = classes.hasNext();
            while (classes.hasNext()) {
                verifyClass(archive.getInputStream(classes.next()));
            }
            return hasClasses;
        }
    }

    private static boolean isClassEntry(String name) {
        return name.endsWith(".class") && !name.equals("module-info.class") && !name.startsWith("META-INF/");
    }

    private static void verifyClass(InputStream input) throws IOException {
        try (DataInputStream data = new DataInputStream(input)) {
            if (!supportsClassVersion(data)) {
                throw new IOException("Unsupported EDT class version");
            }
        }
    }

    private static boolean linkRequiredMembers(ClassLoader loader) throws IOException {
        try (InputStream input = EdtLocation.class.getResourceAsStream("/META-INF/coverage41c/required-members.txt")) {
            if (input == null) {
                throw new IOException("The distribution is missing its list of required EDT members");
            }
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] member = line.split(" ");
                Class<?> owner = Class.forName(member[1], false, loader);
                String name = member[2];
                String descriptor = member[3];
                Class<?> caller = Class.forName(member[4], false, loader);
                // In particular, a public lookup cannot resolve a protected super constructor.
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(caller, MethodHandles.lookup());
                String opcode = member[0];
                if (descriptor.startsWith("(")) {
                    MethodType signature = MethodType.fromMethodDescriptorString(descriptor, loader);
                    switch (opcode) {
                        case "invokeinterface":
                            if (!owner.isInterface()) {
                                return false;
                            }
                            lookup.findVirtual(owner, name, signature);
                            break;
                        case "invokevirtual":
                            if (owner.isInterface()) {
                                return false;
                            }
                            lookup.findVirtual(owner, name, signature);
                            break;
                        case "invokestatic":
                            lookup.findStatic(owner, name, signature);
                            break;
                        case "invokespecial":
                            if ("<init>".equals(name)) {
                                lookup.findConstructor(owner, signature);
                            } else {
                                lookup.findSpecial(owner, name, signature, caller);
                            }
                            break;
                        default:
                            return false;
                    }
                } else {
                    Class<?> fieldType = MethodType.fromMethodDescriptorString("()" + descriptor, loader).returnType();
                    switch (opcode) {
                        case "getfield":
                            lookup.findGetter(owner, name, fieldType);
                            break;
                        case "putfield":
                            lookup.findSetter(owner, name, fieldType);
                            break;
                        case "getstatic":
                            lookup.findStaticGetter(owner, name, fieldType);
                            break;
                        case "putstatic":
                            lookup.findStaticSetter(owner, name, fieldType);
                            break;
                        default:
                            return false;
                    }
                }
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return false;
        }
    }

    private static boolean createRequiredModels(ClassLoader loader) throws IOException {
        try (InputStream input = EdtLocation.class.getResourceAsStream(
                "/META-INF/coverage41c/required-factory-methods.txt")) {
            if (input == null) {
                throw new IOException("The distribution is missing its list of required EDT factory methods");
            }
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] call = line.split(" ");
                Class<?> factoryType = Class.forName(call[0], true, loader);
                Class<?> modelType = Class.forName(call[2], true, loader);
                Object factory = factoryType.getField("eINSTANCE").get(null);
                // Look up only the exact signature used by the application. Enumerating methods
                // would also resolve optional model types that coverage collection never uses.
                Object model = MethodHandles.publicLookup().findVirtual(factoryType, call[1],
                        MethodType.methodType(modelType)).invoke(factory);
                if (!modelType.isInstance(model)) {
                    return false;
                }
                if (MEASURE_REQUEST.equals(call[2])) {
                    MethodHandles.publicLookup().findVirtual(modelType, "setMeasureModeSeanceID",
                            MethodType.methodType(void.class, String.class))
                            .invoke(model, "00000000-0000-0000-0000-000000000001");
                }
            }
            return true;
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            // SDK initialization and factory implementations can throw linkage or runtime errors.
            return false;
        }
    }

    private static boolean supportsClassVersion(DataInputStream data) throws IOException {
        if (data.readInt() != 0xCAFEBABE) {
            return false;
        }
        int minor = data.readUnsignedShort();
        int major = data.readUnsignedShort();
        if (major < 45 || major > MAX_CLASS_VERSION) {
            return false;
        }
        // From Java 12, non-zero minor versions are reserved for this JVM's preview features.
        return major < 56 || minor == 0 || (minor == 0xffff && major == MAX_CLASS_VERSION
                && ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--enable-preview"));
    }
}
