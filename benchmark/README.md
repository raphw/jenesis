Build-performance benchmarks
============================

`benchmark.sh` reproduces the numbers in the README's [Build performance](../README.md#build-performance)
section. It compares Maven against the three ways the Jenesis build is launched (source, `javac`-precompiled,
and `native-image`) across launch overhead, compile-and-package, full builds, Maven 3 vs Maven 4, and the
dependency-pinning modes.

Running
-------

    benchmark/benchmark.sh compile     # one table
    benchmark/benchmark.sh all         # every table

Subcommands: `launch`, `compile`, `full`, `maven`, `pinning`, `aot`, `all`. (`aot` measures *Java AOT* - JDK 25's
command-line AOT cache for the compiled launcher, JEP 514/515, captured via a recording run; this is the JVM cache,
*not* Graal `native-image`. It mirrors the `launch` and `compile` scenarios - launch overhead, cold, warm no-op,
one-line edit and spurious touch - and needs JDK 25+.)

Configuration (environment variables, all optional):

| Variable       | Default        | Purpose                                                              |
|----------------|----------------|----------------------------------------------------------------------|
| `JAVA_HOME`    | java on `PATH` | JDK 25+ for the Jenesis and Maven builds                             |
| `MVN`          | `mvn`          | Maven 3 launcher                                                     |
| `MVN4`         | unset          | Maven 4 launcher; the `maven` table is skipped without it           |
| `GRAALVM_HOME` | unset          | GraalVM 25+; the native launcher is skipped without it              |
| `RUNS_COLD`    | `5`            | repetitions for cold builds                                          |
| `RUNS_WARM`    | `3`            | repetitions for warm and incremental builds                         |

The script prepares what it needs: it precompiles the engine into `.jenesis/launcher` for the precompiled
launcher, and (when `GRAALVM_HOME` is set) captures reachability metadata and builds a native launcher once.

It is `bash` (not POSIX `sh`) and runs on Linux, macOS and Windows (Git Bash). Where GNU `time` is absent
(macOS, Windows) it falls back to the shell's `time` keyword for wall-clock, and where `/proc/net/dev` is
absent it omits the `net<=NKB` column rather than asserting zero bytes.

Continuous integration
----------------------

The `Build performance benchmarks` GitHub Action (`.github/workflows/benchmark.yml`) runs these tables on
Linux, macOS and Windows on demand: trigger it from the Actions tab, optionally choosing which tables and how
many repetitions, and it uploads a `benchmark-<os>.txt` per runner. Runner figures are noisier than a
controlled local machine (shared, virtualized cores), so treat them as cross-platform smoke and relative
comparisons rather than the headline numbers.

Methodology
-----------

These controls are what make the comparison fair; they are the conclusions of a long measurement exercise.

- **External timing.** Wall-clock comes from `/usr/bin/time` (its `%e` field), never a build tool's own
  "BUILD SUCCESS in N s" line. The cold figure is the median of `RUNS_COLD`, the rest of `RUNS_WARM`.
- **CPU power profile.** Run on a `performance` profile. A laptop's default `power-saver` pins the cores low
  (here ~1.4 GHz vs ~3.6 GHz) and inflates every figure ~2.6x; the script warns if the profile is not
  `performance`. Keep the machine on AC and otherwise idle.
- **Warm caches, and network proven absent.** Both tools run with warm dependency caches (`~/.m2`,
  `.jenesis/cache`). Each run records the host network byte delta and prints `net<=NKB`; with warm caches the
  compile, incremental and launch builds transfer **0 KB**. The only exception is the full build with tests,
  which makes a ~25 KB `maven-metadata.xml` lookup for the `RELEASE`-versioned external test tools - the same on
  both tools - so the `full` table needs the network and does not assert zero bytes.
- **Like-for-like work.** Maven runs with `-DskipTests`, which compiles the test sources but skips executing
  them; Jenesis's `-Djenesis.test.skip` likewise compiles the test module and skips only the runner. Both
  therefore compile the same 130 main + 112 test sources. (Maven's `-Dmaven.test.skip=true` would skip compiling
  the tests entirely and is *not* comparable.) The full table runs the real ~1049-test suite on both.
- **First-run caveat.** A from-scratch machine pays one-time dependency and external-tool downloads the warm
  numbers exclude. In particular, the first test-running build of a session populates the external-tool cache,
  so when comparing cold full builds make sure that cache is warm on both sides first.

Notes on the figures
---------------------

- The `native` (Graal) launcher this script builds carries `jdk.compiler`/`jdk.jartool` (`--add-modules`) and forces
  javac's and jar's message bundles in (`-H:IncludeResourceBundles`, without which the in-process compiler fails
  non-deterministically on an un-recorded bundle), so it runs `javac` in-process; with the ahead-of-time-compiled
  compiler - no JVM startup, no JIT warm-up - it is the fastest configuration measured here, cold and warm. A bare
  Graal native image without those modules has no in-process JDK tools and forks an external `javac`, making it the
  slowest on a cold build instead (the `jenesis.process.factory=tool|fork` property forces either path). This is the
  Graal `native-image` launcher; the `aot` table measures the separate *Java AOT* JVM cache.
- `pin=versions` (checksums stripped, versions kept) shows no measurable speedup: the warm hot path is the
  incremental cache's MD5 hashing, and the SHA-256 artifact validation it removes runs only on a cold
  `Dependencies` step and is negligible.
- Maven 4.0.0-rc-5 needs the `maven-compiler-plugin` pin the project's `pom.xml` already carries (its default
  3.13.0 cannot read Java 25 bytecode); with it, Maven 3 and 4 are equivalent bar ~0.9 s of Maven 4 startup
  overhead.
