Native image (GraalVM) demo
============================

Compile a modular Java application ahead of time into a single standalone native
executable with GraalVM `native-image` - a binary that starts in milliseconds and
carries no Java runtime, because the runtime it needs is linked into the binary
itself. It is the ahead-of-time counterpart of `../demo-06-java-modular-executable`:
where that demo packages a `jpackage` app-image (your jar plus a trimmed JVM that
runs it), this one produces a self-contained machine-code program with no JVM at
all. The project is the same minimal shape - a `module-info.java` plus a single
Java source with a `main` - only here Jenesis hands the resolved module path to
`native-image` instead of `jpackage`.

This demo is local-only: it needs the GraalVM `native-image` tool on the build's
toolchain (see below), which the CI runners do not carry, so unlike most demos it
is not part of the CI matrix.

Run it
------

`native-image` is located the same way every external tool is - `GRAALVM_HOME`
first, then the running JDK's own `bin/` (`java.home`), then `PATH`. So either run
the build with a GraalVM JDK:

    ~/.sdkman/candidates/java/25.0.3-graal/bin/java -Djenesis.java.native=true build/jenesis/Project.java

or keep your usual JDK 25 and point `GRAALVM_HOME` at a GraalVM install (here one
managed by [SDKMAN](https://sdkman.io/), `sdk install java 25.0.3-graal`):

    GRAALVM_HOME=~/.sdkman/candidates/java/25.0.3-graal java -Djenesis.java.native=true build/jenesis/Project.java

Either way the build compiles the module, jars it, then runs `native-image` over
the produced module path. The image build is the slow step (around a minute - it
analyses the whole reachable program), after which the standalone binary lands at:

    target/build/modules/compose/module/module-sources/produce/assemble/native-image/output/native/demo.graal.image

Run it directly - there is no `java` in the command, because there is no JVM:

    .../native/demo.graal.image            # prints: Hello, world, from a native binary built by Jenesis!
    .../native/demo.graal.image Ada Lovelace   # Hello, Ada Lovelace, from a native binary built by Jenesis!

The result is a ~6 MB ELF executable (`.exe` on Windows, a Mach-O binary on macOS)
that links `java.base` statically and starts without a runtime.

How the native-image step fits the build
-----------------------------------------

Native compilation is opt-in through a single boolean property,
`-Djenesis.java.native=true`. When it is set, `InferredMultiProjectAssembler` wires a
per-module `native-image` step that runs for every module declaring a main class -
exactly the `@jenesis.main` field that `../demo-06-java-modular-executable` uses for
`jpackage`, read from the same `module.properties`. Modules without a main class are
skipped, so a library in a multi-module build is left alone.

The step reuses the launcher coordinates the build already derived: the module path
is the produced jar plus its resolved runtime dependency jars, and the launcher is
`--module <module>/<main-class>` (the same `--module` jpackage would receive). It
invokes:

    native-image --no-fallback -o <name> --module-path <jars> --module <module>/<main-class>

`--no-fallback` forces a real native image (never a JVM-backed fallback), and the
binary is named after the module's coordinate (`demo.graal.image`), the same name
jpackage's `--name` derives.

Unlike the `jpackage` app-image, the native binary is **not** collected by the
`STAGE` module (there is no `stage/native` analogue of `stage/packages`); it stays
under its step's output directory shown above. The point of the demo is producing
the binary, not staging it.

A self-contained module by design
---------------------------------

This module has no external dependencies on purpose. `native-image` performs a
closed-world static analysis: code reached only through reflection, JNI, resource
lookups, or dynamic proxies is invisible to it unless described by *reachability
metadata*. A dependency-free, reflection-free program needs none, so it compiles
cleanly with nothing but `--no-fallback`. That keeps the demo about the build
wiring rather than about feeding GraalVM a configuration.

When a real application does need metadata, the step picks it up automatically: a
`native-image/` directory among the module's inputs (for instance a
`sources/native-image/` resource folder) is passed through as
`-H:ConfigurationFileDirectories=<dir>`. The usual way to produce that directory is
to run the application once under the tracing agent
(`-agentlib:native-image-agent=config-output-dir=...`), which records the reflective
accesses the static analysis cannot see. This demo has nothing to record, so it
ships no such directory.

Layout
------

    demo/demo-32-native-image
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    `-- sources/
        |-- module-info.java     module demo.graal.image (@jenesis.main, no dependencies)
        `-- sample/Sample.java   main(String[] args); prints a greeting

With a `module-info.java` and no `pom.xml`, Jenesis auto-detects the
MODULAR_TO_MAVEN layout, exactly as `../demo-02-java-modular` does. The module is
named `demo.graal.image` rather than `demo.native.image` because `native` is a Java
reserved word and cannot be a module-name segment.

native-image vs. jpackage
-------------------------

Both `../demo-06-java-modular-executable` (jpackage) and this demo turn a modular
app into something a user runs without installing a JDK, but they differ in kind:

- **jpackage** ships your bytecode plus a `jlink`-trimmed JVM. Startup is normal
  JVM startup; the program is the same bytecode, just bundled. The image is tens of
  megabytes (the JVM dominates) and needs no extra build tooling beyond the JDK.
- **native-image** compiles the program *and* the runtime it touches into one
  machine-code binary ahead of time. Startup is near-instant and the binary is
  small, but it needs GraalVM at build time, a slow closed-world compile, and
  reachability metadata for anything dynamic. It is the better fit for short-lived
  CLIs and fast-scaling services where JVM warm-up cost dominates.

So the two are alternatives, not a progression: pick jpackage for a faithful,
no-extra-tooling bundle of the very JVM you tested against, and native-image when
startup latency and footprint matter more than build simplicity.
