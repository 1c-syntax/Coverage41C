#!/bin/sh
# Run EDT discovery on the real JVM, then inspect the classpath passed to the application.
set -eu
launcher=$1
shift
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/first/plugins" "$fixture/second/plugins" "$fixture/classes"
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
REAL_JAVA=$(command -v "${java_bin}java")
export REAL_JAVA
for jar do
    cp "$jar" "$fixture/first/plugins/"
    cp "$jar" "$fixture/second/plugins/"
done
cat > "$fixture/bin/ring" <<'RING'
#!/bin/sh
printf '%s\n' "${EDT_FIRST:-$EDT_FIXTURE/first}" "$EDT_FIXTURE/second"
RING
cat > "$fixture/bin/java" <<'JAVA'
#!/bin/sh
case " $* " in
    *' com.clouds42.EdtLocation '*)
        [ -z "${EDT_SELECTOR_PID_FILE:-}" ] || printf '%s\n' "$$" > "$EDT_SELECTOR_PID_FILE"
        if [ -n "${EDT_APP_CLASSPATH:-}" ]; then
            # A native Windows JVM uses CRLF when invoked by a Unix-style launcher.
            crlf=$(printf '\r\n.')
            set -- "-Dline.separator=${crlf%.}" "$@"
        fi
        exec "$REAL_JAVA" "$@" ;;
esac
if [ -n "${EDT_APP_CLASSPATH:-}" ]; then
    [ "$EDT_LOCATION" = "$EDT_FIRST/plugins" ] || exit 1
    previous=
    for arg do
        case "$previous" in
            -cp|-classpath|--class-path) printf '%s.' "$arg" > "$EDT_APP_CLASSPATH"; break ;;
        esac
        previous=$arg
    done
    exec "$REAL_JAVA" "$@"
fi
printf '%s\n' "$@"
JAVA
chmod +x "$fixture/bin/ring" "$fixture/bin/java"

# Eclipse installations may expose class directories, archives or a mixture of both.
manual="$fixture/manual EDT"
mkdir -p "$manual/unpacked/plugins" "$manual/archives/plugins" "$manual/mixed/plugins"
# On Unix a bundle name can contain literal escapes, protocol-looking text and a final newline.
archive_suffix='.bundle%0A\copy
COVERAGE41C_EDT_LOCATION=filename
'
if ! touch "$manual/$archive_suffix" 2>/dev/null; then archive_suffix=.bundle; fi
for jar in "$fixture/first/plugins"/*.jar; do
    name=${jar##*/}
    bundle="$manual/unpacked/plugins/${name%.jar}"
    mkdir "$bundle"
    (cd "$bundle" && "${java_bin}jar" xf "$jar")
    archive="$manual/archives/plugins/${name%.jar}$archive_suffix"
    cp "$jar" "$archive"
    case "$name" in
        *.core_[0-9]*) ln -s "$bundle" "$manual/mixed/plugins/" ;;
        *.model_*) ln -s "$archive" "$manual/mixed/plugins/" ;;
        *) cp "$jar" "$manual/mixed/plugins/" ;;
    esac
done
for layout in unpacked archives; do
    EDT_LOCATION="$manual/$layout/plugins" "$launcher" validate -P "$(dirname "$0")/../resources" -s configuration \
        --strict --exclusion-report "$fixture/$layout.json"
    [ -s "$fixture/$layout.json" ]
    echo "PASS explicit EDT $layout"
done

expect_installation() {
    EDT_FIXTURE="$fixture" EDT_LOCATION= JAVA_HOME="$fixture" PATH="$fixture/bin:$PATH" \
        "$launcher" -V > "$fixture/arguments"
    grep -Fq "$fixture/$1/plugins/com._1c.g5.v8.dt.debug.core_" "$fixture/arguments"
}

# Validate with exactly the classpath supplied by ring, even when a regular installation follows it.
layouts_ok=true
for layout in unpacked archives mixed; do
    plugins="$manual/$layout/plugins"
    expected=
    for bundle in "$plugins"/*; do expected="$expected:$bundle"; done
    mkdir "$plugins/com._1c.g5.v8.dt.debug.unchecked.jar"
    touch "$plugins/com._1c.g5.v8.dt.debug.unchecked.bundle"
    if EDT_FIXTURE="$fixture" EDT_FIRST="$manual/$layout" EDT_LOCATION= JAVA_HOME="$fixture" \
            EDT_APP_CLASSPATH="$fixture/$layout.classpath" PATH="$fixture/bin:$PATH" \
            "$launcher" validate -P "$(dirname "$0")/../resources" -s configuration \
            --strict --exclusion-report "$fixture/ring-$layout.json" \
            && [ -s "$fixture/ring-$layout.json" ] && actual=$(cat "$fixture/$layout.classpath") \
            && [ "${actual#*":$fixture/"}" = "${expected#":$fixture/"}." ]; then
        echo "PASS ring EDT $layout"
    else
        echo "FAIL ring EDT $layout" >&2
        layouts_ok=false
    fi
done
"$layouts_ok"

# An unpacked bundle must pass the same checks on required APIs and unused bytecode as a JAR.
for core in "$manual/unpacked/plugins"/com._1c.g5.v8.dt.debug.core_[0-9]*; do
    required="$core/com/_1c/g5/v8/dt/debug/core/runtime/client/RuntimeDebugClientException.class"
    mv "$required" "$fixture/required.class"
    EDT_FIRST="$manual/unpacked" expect_installation second
    mv "$fixture/required.class" "$required"
    printf '\312\376\272\276\000\000\177\377' > "$core/Unused.class"
    EDT_FIRST="$manual/unpacked" expect_installation second
    rm "$core/Unused.class"
done
echo 'PASS ring skips incompatible unpacked EDT'

# Files and directories without a usable bundle stay off the selected classpath.
mkdir "$fixture/first/plugins/com._1c.g5.v8.dt.debug.unchecked.jar"
touch "$fixture/first/plugins/com._1c.g5.v8.dt.debug.unchecked.bundle"
expect_installation first
if grep -Fq '/com._1c.g5.v8.dt.debug.unchecked.' "$fixture/arguments"; then
    echo 'FAIL ring added an unchecked EDT bundle' >&2
    exit 1
fi
unreadable_bundles() {
    chmod 000 "$@"
    if [ ! -r "$1" ]; then
        expect_installation second
    fi
    chmod 600 "$@"
}
unreadable_bundles "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.core_[0-9]*.jar
unreadable_bundles "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar

# Keep unpacking and repacking in the fixture; the EDT supplied for compilation stays intact.
missing_class() {
    missing=$1
    shift
    for jar do
        unpacked=$(mktemp -d "$fixture/bundle.XXXXXX")
        (cd "$unpacked" && "${java_bin}jar" xf "$jar" && rm -f "$missing" && "${java_bin}jar" cMf "$jar" .)
    done
    expect_installation second
    for jar do
        cp "$fixture/second/plugins/${jar##*/}" "$jar"
    done
}
missing_class com/_1c/g5/v8/dt/debug/core/runtime/client/RuntimeDebugClientException.class \
    "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.core_[0-9]*.jar
missing_class com/_1c/g5/v8/dt/debug/model/rdbg/request/response/RDBGSetMeasureModeRequest.class \
    "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar
# The public factory interface still loads when its implementation is missing.
missing_class com/_1c/g5/v8/dt/debug/model/rdbg/request/response/impl/ResponseFactoryImpl.class \
    "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar
# A present public API can still fail when a factory creates its model objects.
missing_class com/_1c/g5/v8/dt/debug/model/rdbg/request/response/impl/RDBGSetMeasureModeRequestImpl.class \
    "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar

# Factory verification can require model types through transitive dependencies.
missing_class com/_1c/g5/v8/dt/debug/model/rdbg/request/response/RDBGEvalExprRequest.class \
    "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar

# Used factory APIs must exist; unused APIs must not reject an installation.
for factory_method in createRDBGSetMeasureModeRequest createRDBGPingDebugUIResponse createRDBGGetBreakpointsRequest; do
    for jar in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar; do
        unpacked=$(mktemp -d "$fixture/method.XXXXXX")
        factory_class=com/_1c/g5/v8/dt/debug/model/rdbg/request/response/ResponseFactory.class
        (cd "$unpacked" && "${java_bin}jar" xf "$jar" "$factory_class")
        offset=$(LC_ALL=C grep -abo "$factory_method" "$unpacked/$factory_class")
        offset=${offset%%:*}
        printf x | dd of="$unpacked/$factory_class" bs=1 seek="$offset" conv=notrunc 2>/dev/null
        "${java_bin}jar" uf "$jar" -C "$unpacked" "$factory_class"
    done
    case "$factory_method" in
        # This factory method is not referenced by the application.
        createRDBGGetBreakpointsRequest) expect_installation first ;;
        *) expect_installation second ;;
    esac
    for jar in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar; do
        cp "$fixture/second/plugins/${jar##*/}" "$jar"
    done
done

application_lib="$(dirname "$launcher")/../lib"
cat > "$fixture/ProfilingProbe.java" <<'JAVA'
import com.clouds42.DebugClient;
import com.clouds42.MyRuntimeDebugModelXmlSerializer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

class ProfilingProbe {
    public static void main(String[] args) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", request -> {
                requests.incrementAndGet();
                request.sendResponseHeaders(204, -1);
                request.close();
            });
            server.start();
            DebugClient client = new DebugClient(new MyRuntimeDebugModelXmlSerializer());
            try {
                client.configure("http://127.0.0.1:" + server.getAddress().getPort(), UUID.randomUUID(), "fixture");
                client.toggleProfiling(UUID.randomUUID());
                if (requests.get() != 1) throw new AssertionError("Expected a profiling request");
            } finally {
                client.dispose();
            }
        } finally {
            server.stop(0);
        }
    }
}
JAVA
"${java_bin}javac" -proc:none --release 11 -cp "$application_lib/*:$fixture/second/plugins/*" \
    -d "$fixture/classes" "$fixture/ProfilingProbe.java"

# Factory creation alone does not check the interface or its implementation.
mkdir "$fixture/mutated"
for type in RDBGSetMeasureModeRequest impl/RDBGSetMeasureModeRequestImpl; do
    for jar in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar; do
        class=com/_1c/g5/v8/dt/debug/model/rdbg/request/response/$type.class
        (cd "$fixture/mutated" && "${java_bin}jar" xf "$jar" "$class")
        offset=$(LC_ALL=C grep -abo setMeasureModeSeanceID "$fixture/mutated/$class")
        offset=${offset%%:*}
        printf x | dd of="$fixture/mutated/$class" bs=1 seek="$offset" conv=notrunc 2>/dev/null
        "${java_bin}jar" uf "$jar" -C "$fixture/mutated" "$class"
    done
    # The mutated SDK must fail at the actual API call, and the selected one must work.
    if "$REAL_JAVA" -cp "$fixture/classes:$application_lib/*:$fixture/first/plugins/*" ProfilingProbe \
            > "$fixture/profiling-log" 2>&1; then
        echo "Mutated SDK unexpectedly enabled profiling: $type" >&2
        exit 1
    fi
    grep -Eq '(NoSuchMethodError|AbstractMethodError).*setMeasureModeSeanceID' "$fixture/profiling-log"
    expect_installation second
    "$REAL_JAVA" -cp "$fixture/classes:$application_lib/*:$fixture/second/plugins/*" ProfilingProbe
    echo "PASS EDT profiling with incompatible $type"
    for jar in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar; do
        cp "$fixture/second/plugins/${jar##*/}" "$jar"
    done
done

# Exercise broken static initialization in a fresh probe JVM. The first candidate
# must neither poison EMF's registry for the second one nor block discovery.
mkdir -p "$fixture/replacement"
cat > "$fixture/ResponseFactoryImpl.java" <<'SOURCE'
package com._1c.g5.v8.dt.debug.model.rdbg.request.response.impl;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.ResponseFactory;
public class ResponseFactoryImpl {
    public static ResponseFactory init() {
        org.eclipse.emf.ecore.EPackage.Registry.INSTANCE.clear();
        System.out.println("probe output must not become an EDT path");
        if ("hang".equals(System.getenv("EDT_PROBE_FIXTURE"))) {
            String marker = System.getenv("EDT_PROBE_PID_FILE");
            if (marker != null) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(marker),
                            Long.toString(ProcessHandle.current().pid()));
                } catch (java.io.IOException error) { throw new IllegalStateException(error); }
            }
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            }
        }
        throw new IllegalStateException("broken factory initialization");
    }
}
SOURCE
application_lib="$(dirname "$launcher")/../lib"
"${java_bin}javac" -proc:none --release 11 -cp "$application_lib/*:$fixture/second/plugins/*" \
    -d "$fixture/replacement" "$fixture/ResponseFactoryImpl.java"
for jar in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar; do
    "${java_bin}jar" uf "$jar" -C "$fixture/replacement" .
done
expect_installation second
EDT_PROBE_FIXTURE=hang expect_installation second
# Terminating the selector must also stop its own hanging probe.
EDT_PROBE_FIXTURE=hang EDT_PROBE_PID_FILE="$fixture/probe.pid" \
    EDT_SELECTOR_PID_FILE="$fixture/selector.pid" expect_installation second &
selection=$!
attempt=0
while [ ! -s "$fixture/probe.pid" ] || [ ! -s "$fixture/selector.pid" ]; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 100 ]; then kill "$selection" 2>/dev/null || :; exit 1; fi
    sleep 0.1
done
probe_pid=$(cat "$fixture/probe.pid")
selector_pid=$(cat "$fixture/selector.pid")
kill -TERM "$selector_pid"
failure=0
wait "$selection" || failure=$?
attempt=0
while kill -0 "$probe_pid" 2>/dev/null && [ "$attempt" -lt 30 ]; do
    attempt=$((attempt + 1))
    sleep 0.1
done
if kill -0 "$probe_pid" 2>/dev/null; then
    kill -KILL "$probe_pid" 2>/dev/null || :
    echo 'FAIL selector signal left its probe running' >&2
    exit 1
fi
[ "$failure" != 0 ]
echo 'PASS selector signal cleanup'
for jar in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.model_*.jar; do
    cp "$fixture/second/plugins/${jar##*/}" "$jar"
done

echo 'class Sample {}' > "$fixture/Sample.java"
"${java_bin}javac" --release 11 -d "$fixture/classes" "$fixture/Sample.java"
cp "$fixture/classes/Sample.class" "$fixture/original.class"
printf '\177\377' | dd of="$fixture/classes/Sample.class" bs=1 seek=6 conv=notrunc 2>/dev/null
extra="$fixture/first/plugins/com._1c.g5.v8.dt.debug.extra_1.jar"
"${java_bin}jar" cf "$extra" -C "$fixture/classes" Sample.class
expect_installation second

# A class for a newer runtime in a multi-release JAR does not shadow its base version.
mkdir -p "$fixture/classes/META-INF/versions/99"
mv "$fixture/classes/Sample.class" "$fixture/classes/META-INF/versions/99/"
cp "$fixture/original.class" "$fixture/classes/Sample.class"
printf 'Multi-Release: true\n' > "$fixture/manifest"
"${java_bin}jar" cfm "$extra" "$fixture/manifest" -C "$fixture/classes" .
expect_installation first

sh "$(dirname "$0")/edt-jvm-options.sh" "$launcher" "$@"
