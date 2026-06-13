#!/usr/bin/env bash
# Reproducible build-performance benchmarks for the Jenesis project; reproduces the README
# "Build performance" tables. Usage, methodology and configuration are in benchmark/README.md.
#   benchmark/benchmark.sh {launch|compile|full|maven|pinning|aot|all}
#
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

MVN="${MVN:-mvn}"
MVN4="${MVN4:-}"
GRAALVM_HOME="${GRAALVM_HOME:-}"
RUNS_COLD="${RUNS_COLD:-5}"
RUNS_WARM="${RUNS_WARM:-3}"
ENGINE="build/jenesis"; [ -f $ENGINE/Project.java ] || ENGINE="sources/build/jenesis"
LAUNCHER="$ROOT/.jenesis/launcher"
EXE=""; NICMD=""; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) EXE=".exe"; NICMD=".cmd";; esac
NATIVE="$ROOT/.jenesis/benchmark-image"
NATIVE_BIN="$NATIVE$EXE"
EDIT="sources/build/jenesis/Project.java"
TF="$(mktemp)"; LOG="$(mktemp)"; trap 'rm -f "$TF" "$LOG"' EXIT
HAVE_GTIME=0; /usr/bin/time -f %e true >/dev/null 2>&1 && HAVE_GTIME=1
HAVE_NET=0; [ -r /proc/net/dev ] && HAVE_NET=1

note() { printf '\n== %s ==\n' "$*"; }
warn() { printf '!! %s\n' "$*" >&2; }

check_env() {
  if command -v powerprofilesctl >/dev/null 2>&1; then
    [ "$(powerprofilesctl get 2>/dev/null)" = performance ] \
      || warn "CPU power profile is not 'performance' - figures will be slow and noisy"
  fi
  java -version >/dev/null 2>&1 || { warn "no java on PATH / JAVA_HOME"; exit 1; }
}

rxtx() { [ "$HAVE_NET" = 1 ] && awk -F'[: ]+' '/eth|wl|en|wlp|enp|eno/{rx+=$3;tx+=$11} END{print rx+0" "tx+0}' /proc/net/dev || echo "0 0"; }

timeit() {
  local b0 b1 rc wall r0 t0; b0=$(rxtx)
  if [ "$HAVE_GTIME" = 1 ]; then
    /usr/bin/time -f "%e" -o "$TF" bash -c "$1" >"$LOG" 2>&1; rc=$?
    wall=$(cut -d' ' -f1 "$TF")
  else
    TIMEFORMAT=%R
    { time bash -c "$1" >"$LOG" 2>&1; } 2>"$TF"; rc=$?
    wall=$(tr -d '[:space:]' < "$TF" | tr ',' '.')
  fi
  b1=$(rxtx); set -- $b0; r0=$1; t0=$2; set -- $b1
  if [ "$HAVE_NET" = 1 ]; then echo "$wall $(( ($1-r0)/1024 )) $(( ($2-t0)/1024 )) $rc"; else echo "$wall na na $rc"; fi
}

median() {
  sort -n | awk '{a[NR]=$1} END{print (NR%2)?a[(NR+1)/2]:(a[NR/2]+a[NR/2+1])/2}'
}

result() {
  if [ "$HAVE_NET" = 1 ]; then printf 'median %6.2fs  (n=%s, net<=%sKB)\n' "$(printf '%s' "$1" | median)" "$2" "$3"
  else printf 'median %6.2fs  (n=%s)\n' "$(printf '%s' "$1" | median)" "$2"; fi
}

bench() {
  local label="$1" runs="$2" setup="$3" cmd="$4" i walls="" worst=0
  printf '%-26s ' "$label"
  for (( i=1; i<=runs; i++ )); do
    [ -n "$setup" ] && bash -c "$setup" >/dev/null 2>&1
    read -r wall rx tx rc <<<"$(timeit "$cmd")"
    walls+="$wall
"
    [ "$rc" != 0 ] && { printf 'FAILED (rc=%s)\n' "$rc"; tail -3 "$LOG" >&2; return 1; }
    [ "$rx" != na ] && [ "$((rx+tx))" -gt "$worst" ] && worst=$((rx+tx))
  done
  result "$walls" "$runs" "$worst"
}

bench_warm() {
  local label="$1" runs="$2" warmup="$3" cmd="$4" i walls="" worst=0
  bash -c "$warmup" >/dev/null 2>&1
  printf '%-26s ' "$label"
  for (( i=1; i<=runs; i++ )); do
    read -r wall rx tx rc <<<"$(timeit "$cmd")"
    walls+="$wall
"
    [ "$rc" != 0 ] && { printf 'FAILED (rc=%s)\n' "$rc"; tail -3 "$LOG" >&2; return 1; }
    [ "$rx" != na ] && [ "$((rx+tx))" -gt "$worst" ] && worst=$((rx+tx))
  done
  result "$walls" "$runs" "$worst"
}

build_launcher() {
  [ -d "$LAUNCHER" ] && return 0
  note "Precompiling the engine to $LAUNCHER"
  rm -rf "$LAUNCHER"; javac -d "$LAUNCHER" $(find -L "$ENGINE" -name '*.java')
}

build_native() {
  [ -x "$NATIVE_BIN" ] && return 0
  [ -n "$GRAALVM_HOME" ] || { warn "GRAALVM_HOME not set - skipping the native launcher"; return 1; }
  build_launcher
  note "Capturing reachability metadata and building the native launcher (one-off)"
  local cfg; cfg="$(mktemp -d)"
  "$GRAALVM_HOME/bin/java" -Djenesis.process.factory=tool -Djenesis.test.skip=true \
      -agentlib:native-image-agent=config-output-dir="$cfg" \
      -cp "$LAUNCHER" build.jenesis.Project build >/dev/null 2>&1
  rm -rf target
  "$GRAALVM_HOME/bin/native-image$NICMD" --no-fallback --add-modules jdk.compiler,jdk.jartool \
      -H:IncludeResourceBundles=com.sun.tools.javac.resources.compiler,com.sun.tools.javac.resources.javac,com.sun.tools.javac.resources.ct,sun.tools.jar.resources.jar \
      -H:ConfigurationFileDirectories="$cfg" \
      -cp "$LAUNCHER" build.jenesis.Project "$NATIVE" >/dev/null 2>&1 \
    && echo "native launcher (in-process javac): $NATIVE_BIN" || { warn "native-image build failed"; return 1; }
}

M_NT() { echo "$1 package -o -q -ntp -DskipTests"; }
M_F()  { echo "$1 package -o -q -ntp"; }
SRC_NT="java -Djenesis.test.skip=true $ENGINE/Project.java build"
JAVAC_NT="java -Djenesis.test.skip=true -cp $LAUNCHER build.jenesis.Project build"
NATIVE_NT="$NATIVE_BIN -Djenesis.test.skip=true build"
SRC_F="java $ENGINE/Project.java build"

table_launch() {
  note "Table: build-tool launch overhead (run 'help', no project work)"
  build_launcher
  bench_warm "source"      "$RUNS_WARM" "java $ENGINE/Project.java help" "java $ENGINE/Project.java help"
  bench_warm "precompiled" "$RUNS_WARM" "java -cp $LAUNCHER build.jenesis.Project help" "java -cp $LAUNCHER build.jenesis.Project help"
  build_native && bench_warm "native" "$RUNS_WARM" "$NATIVE_BIN help" "$NATIVE_BIN help"
}

table_compile() {
  note "Table: compile + package, tests compiled but not run"
  build_launcher; build_native
  local m; m="$(M_NT "$MVN")"
  echo "-- cold (empty target/) --"
  bench "maven3"      "$RUNS_COLD" "rm -rf target" "$m"
  bench "jenesis-source"  "$RUNS_COLD" "rm -rf target" "$SRC_NT"
  bench "jenesis-precompiled" "$RUNS_COLD" "rm -rf target" "$JAVAC_NT"
  [ -x "$NATIVE_BIN" ] && bench "jenesis-native" "$RUNS_COLD" "rm -rf target" "$NATIVE_NT"
  echo "-- warm no-op (nothing changed) --"
  bench_warm "maven3"      "$RUNS_WARM" "rm -rf target; $m" "$m"
  bench_warm "jenesis-source"  "$RUNS_WARM" "rm -rf target; $SRC_NT" "$SRC_NT"
  bench_warm "jenesis-precompiled" "$RUNS_WARM" "rm -rf target; $JAVAC_NT" "$JAVAC_NT"
  [ -x "$NATIVE_BIN" ] && bench_warm "jenesis-native" "$RUNS_WARM" "rm -rf target; $NATIVE_NT" "$NATIVE_NT"
  echo "-- one-line edit to a main source --"
  if git diff --quiet -- "$EDIT" 2>/dev/null; then
    bench_warm "maven3"      "$RUNS_WARM" "git checkout -- $EDIT; rm -rf target; $m" "printf '\n//e%s\n' \"\$(date +%s%N)\">>$EDIT; $m"
    bench_warm "jenesis-source"  "$RUNS_WARM" "git checkout -- $EDIT; rm -rf target; $SRC_NT" "printf '\n//e%s\n' \"\$(date +%s%N)\">>$EDIT; $SRC_NT"
    bench_warm "jenesis-precompiled" "$RUNS_WARM" "git checkout -- $EDIT; rm -rf target; $JAVAC_NT" "printf '\n//e%s\n' \"\$(date +%s%N)\">>$EDIT; $JAVAC_NT"
    [ -x "$NATIVE_BIN" ] && bench_warm "jenesis-native" "$RUNS_WARM" "git checkout -- $EDIT; rm -rf target; $NATIVE_NT" "printf '\n//e%s\n' \"\$(date +%s%N)\">>$EDIT; $NATIVE_NT"
    git checkout -- "$EDIT" 2>/dev/null
  else
    warn "skipping the edit table: $EDIT has uncommitted changes (commit or stash them to measure it)"
  fi
  echo "-- spurious touch (content identical) --"
  bench "maven3"      "$RUNS_WARM" "rm -rf target>/dev/null 2>&1; $m>/dev/null 2>&1; touch $EDIT" "$m"
  bench "jenesis-source"  "$RUNS_WARM" "rm -rf target>/dev/null 2>&1; $SRC_NT>/dev/null 2>&1; touch $EDIT" "$SRC_NT"
  bench "jenesis-precompiled" "$RUNS_WARM" "rm -rf target>/dev/null 2>&1; $JAVAC_NT>/dev/null 2>&1; touch $EDIT" "$JAVAC_NT"
  [ -x "$NATIVE_BIN" ] && bench "jenesis-native" "$RUNS_WARM" "rm -rf target>/dev/null 2>&1; $NATIVE_NT>/dev/null 2>&1; touch $EDIT" "$NATIVE_NT"
}

table_full() {
  note "Table: full build, all tests (test-bound; ~25 KB symmetric metadata fetch, network required)"
  local m; m="$(M_F "$MVN")"
  bench      "jenesis cold"          1 "rm -rf target" "$SRC_F"
  bench      "maven3 cold"           1 "rm -rf target" "$m"
  bench_warm "maven3 warm no-op"     1 "rm -rf target; $m"     "$m"
  bench_warm "jenesis warm no-op"    2 "rm -rf target; $SRC_F" "$SRC_F"
}

table_maven() {
  note "Table: Maven 3 vs Maven 4 (both honour the pinned maven-compiler-plugin)"
  [ -n "$MVN4" ] || { warn "set MVN4=<maven-4 launcher> to run this table"; return 1; }
  local m3 m4; m3="$(M_NT "$MVN")"; m4="$(M_NT "$MVN4")"
  echo "-- cold --"
  bench "maven3" "$RUNS_COLD" "rm -rf target" "$m3"
  bench "maven4" "$RUNS_COLD" "rm -rf target" "$m4"
  echo "-- warm no-op --"
  bench_warm "maven3" "$RUNS_WARM" "rm -rf target; $m3" "$m3"
  bench_warm "maven4" "$RUNS_WARM" "rm -rf target; $m4" "$m4"
}

table_aot() {
  note "Table: JDK 25 AOT cache (JEP 514/515) for the precompiled launcher"
  build_launcher
  local jar="$ROOT/.jenesis/launcher.jar" aot="$ROOT/.jenesis/build.aot"
  rm -f "$jar"; jar --create --file "$jar" -C "$LAUNCHER" .
  local B="-Djenesis.test.skip=true -cp $jar build.jenesis.Project build"
  note "recording run (-XX:AOTCacheOutput captures classes + method profiles)"
  rm -rf target "$aot"
  java -XX:AOTCacheOutput="$aot" $B >/dev/null 2>&1 || { warn "AOT cache training failed (needs JDK 25+)"; rm -f "$jar"; return 1; }
  echo "-- cold --"
  bench "precompiled (jar)"       "$RUNS_COLD" "rm -rf target" "java $B"
  bench "precompiled (jar) + AOT" "$RUNS_COLD" "rm -rf target" "java -XX:AOTCache=$aot $B"
  echo "-- warm no-op --"
  bench_warm "precompiled (jar)"       "$RUNS_WARM" "rm -rf target; java $B"                  "java $B"
  bench_warm "precompiled (jar) + AOT" "$RUNS_WARM" "rm -rf target; java -XX:AOTCache=$aot $B" "java -XX:AOTCache=$aot $B"
  rm -f "$jar" "$aot"
}

table_pinning() {
  note "Table: dependency pinning - default (checksums verified) vs versions (checksums stripped)"
  build_launcher
  local def="java -Djenesis.test.skip=true -cp $LAUNCHER build.jenesis.Project build"
  local ver="java -Djenesis.dependency.pin=versions -Djenesis.test.skip=true -cp $LAUNCHER build.jenesis.Project build"
  echo "-- cold (Dependencies step runs; default validates artifact digests) --"
  bench "default"      "$RUNS_COLD" "rm -rf target" "$def"
  bench "pin=versions" "$RUNS_COLD" "rm -rf target" "$ver"
  echo "-- warm no-op (Dependencies step cached; no validation either way) --"
  bench_warm "default"      "$RUNS_WARM" "rm -rf target; $def" "$def"
  bench_warm "pin=versions" "$RUNS_WARM" "rm -rf target; $ver" "$ver"
}

check_env
case "${1:-}" in
  launch)  table_launch ;;
  compile) table_compile ;;
  full)    table_full ;;
  maven)   table_maven ;;
  pinning) table_pinning ;;
  aot)     table_aot ;;
  all)     table_launch; table_compile; table_full; table_maven; table_pinning; table_aot ;;
  *) echo "usage: $0 {launch|compile|full|maven|pinning|aot|all}"; exit 1 ;;
esac
note "done: ${1:-}"
