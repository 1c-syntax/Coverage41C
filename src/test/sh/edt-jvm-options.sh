#!/bin/sh
# Real launcher/JVM checks: user startup options belong only to the application.
set -eu
launcher=$1
shift
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' 0
mkdir -p "$fixture/bin" "$fixture/first/plugins" "$fixture/second/plugins" "$fixture/classes" "$fixture/ipc"
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
REAL_JAVA=$(command -v "${java_bin}java")
export REAL_JAVA
for jar do
    cp "$jar" "$fixture/first/plugins/"
    cp "$jar" "$fixture/second/plugins/"
done
cat > "$fixture/bin/ring" <<'RING'
#!/bin/sh
[ "${EDT_EMPTY_RING:-}" != 1 ] || exit 0
printf '%s\n' "${EDT_FIXTURE_FIRST:-$EDT_FIXTURE/first}" "$EDT_FIXTURE/second"
RING
cat > "$fixture/bin/java" <<'JAVA'
#!/bin/sh
case " $* " in
    *' com.clouds42.EdtLocation '*)
        [ "${EDT_SELECTOR_NOISE:-}" != 1 ] || printf '%s\n' 'unrelated selector stdout'
        [ "${EDT_SELECTOR_DUPLICATE:-}" != 1 ] || printf '%s\n' 'COVERAGE41C_EDT_LOCATION=/untrusted'
        [ "${EDT_SELECTOR_FAIL:-}" != 1 ] || exit 23
        exec "$REAL_JAVA" "$@" ;;
esac
if [ -n "${EDT_SELECTED_FILE:-}" ]; then printf '%s\n' "$EDT_LOCATION" > "$EDT_SELECTED_FILE"; fi
if [ "${EDT_REAL_APP:-1}" = 1 ]; then exec "$REAL_JAVA" "$@"; fi
printf '%s\n' "$@"
JAVA
chmod +x "$fixture/bin/ring" "$fixture/bin/java"
cat > "$fixture/OptionsAgent.java" <<'JAVA'
import java.nio.file.*;
public class OptionsAgent {
    public static void premain(String marker) throws Exception {
        Files.writeString(Path.of(marker), System.getProperty("coverage41c.option", "missing") + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
JAVA
"${java_bin}javac" -proc:none --release 11 -d "$fixture/classes" "$fixture/OptionsAgent.java"
printf 'Premain-Class: OptionsAgent\n' > "$fixture/agent.mf"
"${java_bin}jar" cfm "$fixture/agent.jar" "$fixture/agent.mf" -C "$fixture/classes" OptionsAgent.class
run_launcher() {
    env EDT_FIXTURE="$fixture" EDT_LOCATION= JAVA_HOME="$fixture" TMPDIR="$fixture/ipc" \
        PATH="$fixture/bin:$PATH" "$@" "$launcher" -V
}
version=$(run_launcher env)
status=0
assert_clean_ipc() {
    for result in "$fixture/ipc"/coverage41c-edt.*; do
        if [ -e "$result" ]; then echo 'EDT result file was not removed' >&2; return 1; fi
    done
}
for source in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS; do
    for kind in gc jmx jdwp agent; do
        case "$kind" in
            gc) flags=-Xlog:gc ;;
            jmx) flags='-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.local.port=17019' ;;
            jdwp) flags='-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:17020' ;;
            agent) flags= ;;
        esac
        marker="$fixture/$source-$kind.agent"
        flags="$flags -javaagent:$fixture/agent.jar=$marker -Dcoverage41c.option=preserved"
        if run_launcher env "$source=$flags" > "$fixture/output" 2> "$fixture/errors" \
                && grep -Fxq "$version" "$fixture/output" \
                && [ "$(cat "$marker")" = preserved ] && assert_clean_ipc; then
            echo "PASS startup $source $kind"
        else
            echo "FAIL startup $source $kind" >&2
            cat "$fixture/errors" >&2
            [ ! -f "$marker" ] || cat "$marker" >&2
            status=1
        fi
    done
done
failure=0
run_launcher env EDT_EMPTY_RING=1 > "$fixture/output" 2> "$fixture/errors" || failure=$?
# No usable SDK must fail; the public baseline delegates its diagnostic to Java main.
if [ "$failure" != 0 ] \
        && grep -Eq 'EDT_LOCATION|NoClassDefFoundError: com/_1c/g5/v8/dt/debug/' "$fixture/errors" \
        && assert_clean_ipc; then
    echo 'PASS empty ring'
else
    echo 'FAIL empty ring' >&2; status=1
fi
# No JVM or SDK stdout is part of the path protocol.
if run_launcher env EDT_SELECTOR_NOISE=1 > "$fixture/output" \
        && grep -Fxq "$version" "$fixture/output" && assert_clean_ipc; then
    echo 'PASS selector stdout noise'
else
    echo 'FAIL selector stdout noise' >&2; status=1
fi
# Selection must also work without any writable temporary directory.
if run_launcher env TMPDIR=/dev/null/unwritable JAVA_OPTS=-Djava.io.tmpdir=/dev/null/unwritable \
        > "$fixture/output" && grep -Fxq "$version" "$fixture/output"; then
    echo 'PASS readonly temporary directory'
else
    echo 'FAIL readonly temporary directory' >&2; status=1
fi
failure=0
run_launcher env EDT_SELECTOR_DUPLICATE=1 > "$fixture/output" 2> "$fixture/errors" || failure=$?
if [ "$failure" != 0 ]; then echo 'PASS ambiguous protocol rejected'; else status=1; fi
# Spaces, Unicode and a leading dash in a path segment must survive IPC unchanged.
mkdir -p "$fixture/-EDT тест/plugins"
for jar in "$fixture/first/plugins"/*.jar; do cp "$jar" "$fixture/-EDT тест/plugins/"; done
if run_launcher env EDT_FIXTURE_FIRST="$fixture/-EDT тест" EDT_REAL_APP=0 > "$fixture/output" \
        && grep -Fq "$fixture/-EDT тест/plugins/com._1c.g5.v8.dt.debug.core_" "$fixture/output"; then
    echo 'PASS special EDT path'
else
    echo 'FAIL special EDT path' >&2; status=1
fi
# Exercise cygpath option parsing on Linux with real relative paths and a real application JVM.
if [ "$(uname -s)" = Linux ]; then
    mkdir "$fixture/windows-bin"
    printf '#!/bin/sh\nprintf "%%s\\n" "$EDT_TEST_KERNEL"\n' > "$fixture/windows-bin/uname"
    cat > "$fixture/windows-bin/cygpath" <<'CYGPATH'
#!/bin/sh
if [ "$1" = --mixed ] && [ "$2" = --file ] && [ "$3" = - ]; then cat; exit; fi
while [ "$#" -gt 0 ]; do
    case "$1" in
        --unix|--mixed|--path|--ignore) shift ;;
        --) shift; break ;;
        -*) echo "cygpath: unknown option $1" >&2; exit 2 ;;
        *) break ;;
    esac
done
[ "$#" = 1 ] && printf '%s\n' "$1"
CYGPATH
    chmod +x "$fixture/windows-bin/"*
    ln -s first/plugins "$fixture/-sdk"
    ln -s first "$fixture/-edt"
    for kernel in CYGWIN_NT MSYS_NT; do
        for location in -sdk ''; do
            expected=${location:--edt/plugins}
            if (cd "$fixture" && run_launcher env PATH="$fixture/windows-bin:$fixture/bin:$PATH" \
                    EDT_TEST_KERNEL="$kernel" EDT_LOCATION="$location" EDT_FIXTURE_FIRST=-edt \
                    EDT_SELECTED_FILE="$fixture/selected" > "$fixture/output" 2> "$fixture/errors") \
                    && grep -Fxq "$version" "$fixture/output" && grep -Fxq -e "$expected" "$fixture/selected"; then
                echo "PASS $kernel relative EDT path $expected"
            else
                echo "FAIL $kernel relative EDT path $expected" >&2
                cat "$fixture/errors" >&2
                status=1
            fi
        done
    done
fi

# Check which SDK the real application gets, including when a second valid SDK follows it.
check_sdk() {
    expected="$1/plugins"
    if [ "$separator" = ';' ]; then expected=$(cygpath --mixed "$expected"); fi
    shift
    if run_launcher env EDT_SELECTED_FILE="$fixture/selected" "$@" > "$fixture/output" 2> "$fixture/errors" \
            && grep -Fxq "$version" "$fixture/output" && grep -Fxq "$expected" "$fixture/selected"; then
        echo "PASS $scenario"
    else
        echo "FAIL $scenario" >&2
        cat "$fixture/errors" >&2
        status=1
    fi
}
case $(uname) in CYGWIN*|MSYS*|MINGW*) separator=';' ;; *) separator=: ;; esac
shifted="$fixture/first${separator}EDT"
mv "$fixture/first" "$shifted"
scenario='ring skips a classpath separator in the installation root'
check_sdk "$fixture/second" EDT_FIXTURE_FIRST="$shifted"
if run_launcher env EDT_LOCATION="$shifted/plugins" > "$fixture/output" 2> "$fixture/errors" \
        || ! grep -Fq 'classpath separator' "$fixture/errors"; then
    echo 'FAIL explicit EDT root separator diagnostic' >&2
    status=1
else
    echo 'PASS explicit EDT root separator diagnostic'
fi
mv "$shifted" "$fixture/first"
for core in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.core_[0-9]*.jar; do
    renamed="$core${separator}v1.bundle"
    mv "$core" "$renamed"
    scenario='ring skips a classpath separator in a bundle name'
    check_sdk "$fixture/second"
    if run_launcher env EDT_LOCATION="$fixture/first/plugins" > "$fixture/output" 2> "$fixture/errors"; then
        echo 'FAIL explicit EDT without a usable core library' >&2
        status=1
    else
        echo 'PASS explicit EDT without a usable core library'
    fi
    mv "$renamed" "$core"
done

optional="$fixture/first/plugins/com._1c.g5.v8.dt.debug.optional${separator}unused"
cp "$fixture/agent.jar" "$optional.jar"
touch "$optional.txt"
scenario='ring keeps an SDK with unusable optional entries'
check_sdk "$fixture/first"
if run_launcher env EDT_LOCATION="$fixture/first/plugins" EDT_EMPTY_RING=1 > "$fixture/output" \
        && grep -Fxq "$version" "$fixture/output" \
        && run_launcher env EDT_LOCATION="$fixture/first/plugins" EDT_REAL_APP=0 > "$fixture/arguments" \
        && ! grep -Fq "$optional" "$fixture/arguments"; then
    echo 'PASS explicit EDT skips unusable optional entries'
else
    echo 'FAIL explicit EDT skips unusable optional entries' >&2
    status=1
fi
rm "$optional.jar" "$optional.txt"

# This SDK uses the default java.net opening and, in strict mode, two additional module permissions.
cat > "$fixture/RuntimeDebugClientException.java" <<'JAVA'
package com._1c.g5.v8.dt.debug.core.runtime.client;
public class RuntimeDebugClientException extends Exception {
    static {
        Module base = Object.class.getModule();
        Module logging = java.util.logging.Logger.class.getModule();
        if (!base.isOpen("java.net", RuntimeDebugClientException.class.getModule())
                || (System.getenv("EDT_EXTRA_ACCESS") != null && (!base.isOpen("jdk.internal.misc", logging)
                || !base.isExported("jdk.internal.ref", logging)))) {
            throw new IllegalStateException("SDK module permissions missing");
        }
        if (System.getProperty("coverage41c.visibility") != null
                || java.util.Arrays.stream(ProcessHandle.current().info().arguments().orElse(new String[0]))
                        .anyMatch(arg -> arg.contains("private-marker"))) {
            throw new IllegalStateException("Private options reached SDK probe");
        }
    }
    public RuntimeDebugClientException(String message) { super(message); }
    public RuntimeDebugClientException(Throwable cause) { super(cause); }
    public RuntimeDebugClientException(String message, Throwable cause) { super(message, cause); }
}
JAVA
"${java_bin}javac" -proc:none --release 11 -d "$fixture/classes" "$fixture/RuntimeDebugClientException.java"
for core in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.core_[0-9]*.jar; do
    "${java_bin}jar" uf "$core" -C "$fixture/classes" com
done
opens=java.base/jdk.internal.misc=java.logging,private-marker
exports=java.base/jdk.internal.ref=java.logging
scenario='SDK requires the default --add-opens'
check_sdk "$fixture/first"
scenario='SDK requires extra permissions that are absent'
check_sdk "$fixture/second" EDT_EXTRA_ACCESS=1
for source in JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS JAVA_OPTS COVERAGE41_C_OPTS; do
    scenario="SDK requires --add-opens and --add-exports from $source"
    # Native JVM environment variables use C whitespace, including vertical tab. Gradle uses xargs.
    gap=' '
    case "$source" in JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS) gap=$(printf '\013') ;; esac
    flags="--add-opens=$opens${gap}--add-exports=$exports"
    case "$source" in JAVA_OPTS|COVERAGE41_C_OPTS|JDK_JAVA_OPTIONS)
        flags="--add-opens '$opens'${gap}--add-exports $exports" ;;
    esac
    check_sdk "$fixture/first" EDT_EXTRA_ACCESS=1 "$source=$flags -Dcoverage41c.visibility=private-marker"
    scenario="flags inside a property in $source do not grant access"
    check_sdk "$fixture/second" EDT_EXTRA_ACCESS=1 "$source=-Dlabel='$flags'"
done
marker="$fixture/module-access.agent"
printf '%s\n' '--add-opens' "\"$opens\"" "--add-exports=$exports" \
    "-javaagent:$fixture/agent.jar=$marker" '-Dcoverage41c.option=preserved' > "$fixture/module options"
for source in JAVA_OPTS JDK_JAVA_OPTIONS COVERAGE41_C_OPTS; do
    scenario="module permissions from $source argument file"
    rm -f "$marker"
    check_sdk "$fixture/first" EDT_EXTRA_ACCESS=1 "$source=\"@$fixture/module options\""
    if [ "$(cat "$marker")" != preserved ]; then echo 'FAIL argument file agent count' >&2; status=1; fi
done
for core in "$fixture/first/plugins"/com._1c.g5.v8.dt.debug.core_[0-9]*.jar; do
    cp "$fixture/second/plugins/${core##*/}" "$core"
done

# Also account for preview bytecode when selecting the SDK.
echo 'class Preview {}' > "$fixture/Preview.java"
"${java_bin}javac" -proc:none -d "$fixture/classes" "$fixture/Preview.java"
printf '\377\377' | dd of="$fixture/classes/Preview.class" bs=1 seek=4 conv=notrunc 2>/dev/null
"${java_bin}jar" cf "$fixture/first/plugins/com._1c.g5.v8.dt.debug.preview_1.jar" -C "$fixture/classes" Preview.class
# Gradle defines the script's default options itself. Values inherited from the caller do not reach the application.
for inherited in --enable-preview "@$fixture/absent-options"; do
    if run_launcher env EDT_REAL_APP=0 "DEFAULT_JVM_OPTS=$inherited" > "$fixture/output" 2> "$fixture/errors" \
            && grep -Fq "$fixture/second/plugins/com._1c.g5.v8.dt.debug.core_" "$fixture/output"; then
        echo 'PASS inherited DEFAULT_JVM_OPTS ignored during discovery'
    else
        echo 'FAIL discovery used inherited DEFAULT_JVM_OPTS' >&2
        cat "$fixture/errors" >&2
        status=1
    fi
done
for source in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS; do
    for quoting in plain quoted property; do
        case "$quoting" in
            plain) flags=--enable-preview; expected=first ;;
            quoted) flags='"--enable-preview"'; expected=first ;;
            property) flags='-Dlabel=" --enable-preview "'; expected=second ;;
        esac
        if run_launcher env EDT_REAL_APP=0 "$source=$flags" > "$fixture/output" 2> "$fixture/errors" \
                && grep -Fq "$fixture/$expected/plugins/com._1c.g5.v8.dt.debug.core_" "$fixture/output" \
                && assert_clean_ipc; then
            echo "PASS preview $source $quoting"
        else
            echo "FAIL preview $source $quoting" >&2
            cat "$fixture/errors" >&2
            status=1
        fi
    done
done
# Java launcher options can expand an argument file without sharing it with the probe JVM.
mkdir "$fixture/args directory"
agent_marker="$fixture/argfile.agent"
printf '# comment\r\n"--enable-preview"\r\n-javaagent:%s=%s\r\n-Dcoverage41c.option=preserved\r\n' \
    "$fixture/agent.jar" "$agent_marker" > "$fixture/args directory/preview"
for source in JAVA_OPTS JDK_JAVA_OPTIONS COVERAGE41_C_OPTS; do
    rm -f "$agent_marker"
    flags='"@args directory/preview"'
    if (cd "$fixture" && run_launcher env "$source=$flags" > "$fixture/output" 2> "$fixture/errors") \
            && grep -Fxq "$version" "$fixture/output" && [ "$(cat "$agent_marker")" = preserved ] \
            && (cd "$fixture" && run_launcher env EDT_REAL_APP=0 "$source=$flags" > "$fixture/output") \
            && grep -Fq "$fixture/first/plugins/com._1c.g5.v8.dt.debug.core_" "$fixture/output"; then
        echo "PASS preview argument file $source"
    else
        echo "FAIL preview argument file $source" >&2
        cat "$fixture/errors" >&2
        status=1
    fi
done
printf '# --enable-preview\r\n--enable-preview#not-an-option\r\n-Dlabel=" --enable-preview "\r\n' > "$fixture/args directory/ignored"
if (cd "$fixture" && run_launcher env EDT_REAL_APP=0 'JAVA_OPTS="@args directory/ignored"' > "$fixture/output") \
        && grep -Fq "$fixture/second/plugins/com._1c.g5.v8.dt.debug.core_" "$fixture/output"; then
    echo 'PASS preview argument file ignores comments and property values'
else
    status=1
fi

# Compare continued and quoted flags with a probe run by the real Java launcher.
cat > "$fixture/PreviewFlagProbe.java" <<'JAVA'
import java.lang.management.ManagementFactory;
class PreviewFlagProbe {
    public static void main(String[] args) {
        if (!ManagementFactory.getRuntimeMXBean().getInputArguments().contains("--enable-preview")) {
            throw new AssertionError("Java did not enable preview");
        }
    }
}
JAVA
"${java_bin}javac" -proc:none -d "$fixture/classes" "$fixture/PreviewFlagProbe.java"
printf '%s\n' '"--enable-\' '   preview"' > "$fixture/args directory/continued"
(cd "$fixture" && "$REAL_JAVA" '@args directory/continued' -cp classes PreviewFlagProbe)
if (cd "$fixture" && run_launcher env EDT_REAL_APP=0 'JAVA_OPTS="@args directory/continued"' > "$fixture/output") \
        && grep -Fq "$fixture/first/plugins/com._1c.g5.v8.dt.debug.core_" "$fixture/output"; then
    echo 'PASS preview argument file continued line'
else
    status=1
fi
printf '%s\n' '@preview' > "$fixture/args directory/nested"
for flags in '"@args directory/nested"' '@@preview'; do
    if ! (cd "$fixture" && run_launcher env EDT_REAL_APP=0 "JAVA_OPTS=$flags" > "$fixture/output") \
            || ! grep -Fq "$fixture/second/plugins/com._1c.g5.v8.dt.debug.core_" "$fixture/output"; then
        status=1
    fi
done
failure=0
(cd "$fixture" && run_launcher env 'JAVA_OPTS="@args directory/missing"' > "$fixture/output" 2> "$fixture/errors") \
    || failure=$?
if [ "$failure" != 0 ] && grep -Fq 'args directory/missing' "$fixture/errors"; then
    echo 'PASS missing Java argument file diagnostic'
else
    status=1
fi
rm -f "$agent_marker"
if (cd "$fixture" && run_launcher env EDT_LOCATION="$fixture/first/plugins" \
        'JAVA_OPTS="@args directory/preview"' > "$fixture/output") \
        && grep -Fxq "$version" "$fixture/output" && [ "$(cat "$agent_marker")" = preserved ]; then
    echo 'PASS explicit EDT with Java argument file'
else
    status=1
fi
rm "$fixture/first/plugins/com._1c.g5.v8.dt.debug.preview_1.jar"
failure=0
run_launcher env EDT_SELECTOR_FAIL=1 > "$fixture/output" 2> "$fixture/errors" || failure=$?
if [ "$failure" = 23 ] && assert_clean_ipc; then echo 'PASS selector failure cleanup'; else status=1; fi
exit "$status"
