#!/usr/bin/env bash
#
# Happy-path test for the POSIX jpx launcher (sdk/jpx/bin/jpx). Exercises the
# offline usage and validation paths, then installs and launches a sample tool
# from a file-backed Maven repository staged in a temporary directory, with the
# home redirected so the installation never touches the real ~/.jenesis/jpx.
# Exits 0 only when every check passes.
#
# Prerequisite: the SDK must have been staged before this script runs
# (the matching GitHub Actions job builds Jenesis with `stage` and copies
# the produced modular jar into sdk/jpx/lib).

set -eu

# This script lives at <repo>/sdk/jpx/tests/, so its parent is the SDK home.
SDK_HOME="$(cd "$(dirname "$0")/.." && pwd -P)"

MODULE_JAR=""
for candidate in "${SDK_HOME}/lib"/*.jar; do
    if [ -f "$candidate" ]; then
        MODULE_JAR="$candidate"
        break
    fi
done
if [ -z "$MODULE_JAR" ]; then
    echo "jpx-tests: no jar at ${SDK_HOME}/lib - stage the SDK first" >&2
    exit 1
fi

# The sample tool is compiled with the same Java the launcher resolves.
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/javac" ]; then
    JAVAC="${JAVA_HOME}/bin/javac"
    JAR="${JAVA_HOME}/bin/jar"
else
    JAVAC="javac"
    JAR="jar"
fi

TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

dump_and_fail() {
    echo "  fail: $1" >&2
    [ -n "${2:-}" ] && printf '%s\n' "$2" >&2
    exit 1
}

# [1/6] --help prints the usage and exits 0
echo "[1/6] jpx --help"
OUT="$("${SDK_HOME}/bin/jpx" --help 2>&1)" || dump_and_fail "jpx --help exited non-zero" "$OUT"
printf '%s' "$OUT" | grep -qF "Usage: jpx" || dump_and_fail "missing usage banner" "$OUT"
echo "  ok"

# [2/6] no target prints the usage and exits 64
echo "[2/6] jpx without a target"
set +e
OUT="$("${SDK_HOME}/bin/jpx" 2>&1)"
RC=$?
set -e
[ "$RC" = "64" ] || dump_and_fail "expected exit 64, got $RC" "$OUT"
printf '%s' "$OUT" | grep -qF "Usage: jpx" || dump_and_fail "missing usage banner" "$OUT"
echo "  ok"

# [3/6] an unknown option prints the usage and exits 64
echo "[3/6] jpx with an unknown option"
set +e
OUT="$("${SDK_HOME}/bin/jpx" --unknown target 2>&1)"
RC=$?
set -e
[ "$RC" = "64" ] || dump_and_fail "expected exit 64, got $RC" "$OUT"
printf '%s' "$OUT" | grep -qF "Unknown option: --unknown" || dump_and_fail "missing unknown-option line" "$OUT"
echo "  ok"

# [4/6] a malformed --hash is rejected before any resolution work
echo "[4/6] jpx with a malformed --hash"
set +e
OUT="$("${SDK_HOME}/bin/jpx" --hash=xyz target 2>&1)"
RC=$?
set -e
[ "$RC" != "0" ] || dump_and_fail "expected a validation failure" "$OUT"
printf '%s' "$OUT" | grep -qF "at least 32 hex characters" || dump_and_fail "missing checksum complaint" "$OUT"
echo "  ok"

# [5/6] install and launch a sample tool from a file-backed Maven repository;
# the redirected home deliberately has no .m2 repository, so the installation
# must succeed without a local Maven cache to materialize into.
echo "[5/6] jpx install and launch"
mkdir -p "$TMPDIR/src/exampletool" "$TMPDIR/classes" "$TMPDIR/home"
cat > "$TMPDIR/src/exampletool/Main.java" <<'SOURCE'
package exampletool;
public class Main {
    public static void main(String[] arguments) throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of(arguments[0]), "jpx-sdk-test");
        System.exit(7);
    }
}
SOURCE
"$JAVAC" -d "$TMPDIR/classes" "$TMPDIR/src/exampletool/Main.java"
REPO="$TMPDIR/repo/org/example/tool/1.0"
mkdir -p "$REPO"
"$JAR" --create --file "$REPO/tool-1.0.jar" --main-class exampletool.Main -C "$TMPDIR/classes" .
cat > "$REPO/tool-1.0.pom" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <groupId>org.example</groupId>
    <artifactId>tool</artifactId>
    <version>1.0</version>
</project>
POM
export JAVA_OPTS="-Duser.home=$TMPDIR/home -Djenesis.maven.uri=file://$TMPDIR/repo/"
set +e
"${SDK_HOME}/bin/jpx" org.example:tool@1.0 "$TMPDIR/marker.txt"
RC=$?
set -e
[ "$RC" = "7" ] || dump_and_fail "expected the tool's exit 7, got $RC"
[ "$(cat "$TMPDIR/marker.txt")" = "jpx-sdk-test" ] || dump_and_fail "marker file not written by the tool"
DESCRIPTOR="$TMPDIR/home/.jenesis/jpx/org.example--tool@1.0/jpx.properties"
[ -f "$DESCRIPTOR" ] || dump_and_fail "no descriptor at $DESCRIPTOR"
grep -qF "classpath=tool-1.0.jar" "$DESCRIPTOR" || dump_and_fail "descriptor does not record the Maven jar name" "$(cat "$DESCRIPTOR")"
echo "  ok"

# [6/6] --hash verifies the recorded checksum prefix and rejects a mismatch
echo "[6/6] jpx --hash verification"
CHECKSUM="$(sed -n 's/^checksum=SHA-256\///p' "$DESCRIPTOR")"
[ -n "$CHECKSUM" ] || dump_and_fail "no checksum recorded in $DESCRIPTOR"
set +e
"${SDK_HOME}/bin/jpx" --hash="$(printf '%.32s' "$CHECKSUM")" org.example:tool@1.0 "$TMPDIR/marker.txt"
RC=$?
set -e
[ "$RC" = "7" ] || dump_and_fail "expected the tool's exit 7 under --hash, got $RC"
set +e
OUT="$("${SDK_HOME}/bin/jpx" --hash=00000000000000000000000000000000 org.example:tool@1.0 "$TMPDIR/marker.txt" 2>&1)"
RC=$?
set -e
[ "$RC" != "0" ] || dump_and_fail "expected a checksum mismatch failure" "$OUT"
printf '%s' "$OUT" | grep -qF "Checksum mismatch" || dump_and_fail "missing mismatch message" "$OUT"
echo "  ok"

echo "jpx-tests: all checks passed"
