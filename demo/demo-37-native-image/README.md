Native image (GraalVM) demo
============================

Compile a modular Java application ahead of time into a single standalone native
executable with GraalVM `native-image` - a binary that starts in milliseconds and
carries no Java runtime, because the runtime it needs is linked into the binary
itself. It is the ahead-of-time counterpart of `../demo-06-java-modular-executable`:
where that demo packages a `jpackage` app-image (your jar plus a trimmed JVM that
runs it), this one produces a self-contained machine-code program with no JVM at
all.

The demo deliberately reaches for **reflection**, the thing native-image's
closed-world analysis cannot see, so it shows the whole loop: a test captures the
reachability metadata, you commit it next to the sources, and the native build picks
it up. `Sample` loads its greeter by a name assembled at run time -
`Class.forName("sample." + name)` - and invokes it reflectively, so neither the class
nor its methods are statically reachable.

This demo is local-only: it needs the GraalVM `native-image` tool (and, for the
capture, the GraalVM tracing agent) on the build's toolchain, which the CI runners
do not carry, so unlike most demos it is not part of the CI matrix.

Build the native image
----------------------

`native-image` is located the same way every external tool is - `GRAALVM_HOME`
first, then the running JDK's own `bin/` (`java.home`), then `PATH`. So either run
the build with a GraalVM JDK:

    ~/.sdkman/candidates/java/25.0.3-graal/bin/java -Djenesis.java.native=true build/jenesis/Project.java

or keep your usual JDK 25 and point `GRAALVM_HOME` at a GraalVM install (here one
managed by [SDKMAN](https://sdkman.io/), `sdk install java 25.0.3-graal`):

    GRAALVM_HOME=~/.sdkman/candidates/java/25.0.3-graal java -Djenesis.java.native=true build/jenesis/Project.java

The build compiles the modules, runs the test, then runs `native-image` over the
produced module path. The image build is the slow step (a minute or two - it
analyses the whole reachable program), after which the standalone binary lands at:

    target/build/modules/compose/module/module-sources/produce/assemble/native-image/output/native/demo.graal.image

Run it directly - there is no `java` in the command, because there is no JVM:

    .../native/demo.graal.image            # Hello, world, from a native binary built by Jenesis (reflectively)!
    .../native/demo.graal.image Ada        # Hello, Ada, from a native binary built by Jenesis (reflectively)!

The result is a ~6 MB ELF executable (`.exe` on Windows, a Mach-O binary on macOS)
that links `java.base` statically and starts without a runtime. The greeting comes
back through reflection - which only works because the committed metadata told
native-image to keep `sample.Greeter`. Delete
`sources/META-INF/native-image/demo.graal.image/reachability-metadata.json`, rebuild,
and the binary fails at run time with `ClassNotFoundException: sample.Greeter`: the
closed-world analysis dropped the class it never saw referenced.

Capturing the metadata with the tracing agent
----------------------------------------------

Where does that metadata come from? Jenesis runs GraalVM's tracing agent the same
way it runs JaCoCo coverage (`../demo-19-code-coverage`): as a **test-observation
engine**. The `demo.graal.image.test` module exercises exactly the reflective call
`Sample` makes, so building on a GraalVM JDK with

    java -Djenesis.observe.native=true build/jenesis/Project.java stage

attaches `-agentlib:native-image-agent` to the test JVM, and the agent records every
reflective, JNI, resource and proxy access the test triggers. The capture is staged
as a report:

    target/stage/reports/native-image/<module>/reachability-metadata.json

which for this demo holds exactly the `sample.Greeter` constructor and `greet`
method. You review that, then commit the app-relevant entries into the module's
`sources/META-INF/native-image/` (here under `demo.graal.image/`).

How the committed metadata reaches the build
--------------------------------------------

`META-INF/native-image/` is the GraalVM-standard location: native-image scans it
inside every jar on the module path and applies whatever configuration it finds, so
nothing has to point the build at the file. Jenesis packages it there for free -
files under `META-INF/` are copied into the jar verbatim (they are resources, not
sources to compile), so `sources/META-INF/native-image/demo.graal.image/reachability-metadata.json`
lands at `META-INF/native-image/demo.graal.image/` in the produced jar, and the
native build on that jar discovers it automatically.

That keeps capture and build deliberately **separate**: the agent only ships in the
GraalVM runtime (so the observation engine needs no coordinate to resolve), and the
metadata it records is staged for you to vet and commit before the image is built
from it, rather than fed blindly into the same build. The same committed file also
serves a plain JVM run and any other tool that honours `META-INF/native-image/`.

How the native-image step fits the build
-----------------------------------------

Native compilation is opt-in through a single boolean property,
`-Djenesis.java.native=true`. When it is set, `InferredMultiProjectAssembler` wires a
per-module `native-image` step that runs for every module declaring a main class -
exactly the `@jenesis.main` field that `../demo-06-java-modular-executable` uses for
`jpackage`, read from the same `module.properties`. Modules without a main class
(the test module) are skipped. The step reuses the launcher coordinates the build
already derived - the produced module path and `--module <module>/<main-class>` - and
invokes:

    native-image --no-fallback -o <name> --module-path <jars> --module <module>/<main-class>

The native binary is **not** collected by the `STAGE` module (there is no
`stage/native` analogue of `stage/packages`); it stays under its step's output
directory shown above.

Layout
------

    demo/demo-37-native-image
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- sources/
    |   |-- module-info.java     module demo.graal.image (@jenesis.main sample.Sample)
    |   |-- META-INF/native-image/demo.graal.image/reachability-metadata.json
    |   `-- sample/
    |       |-- Sample.java       main; loads the greeter reflectively by a run-time name
    |       `-- Greeter.java      the reflective target (reached only via Class.forName)
    `-- test/
        |-- module-info.java     open module demo.graal.image.test (@jenesis.test demo.graal.image)
        `-- imagetest/SampleTest.java   exercises the reflection so the agent records it

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
  reachability metadata for anything dynamic - the loop this demo walks through.

So the two are alternatives, not a progression: pick jpackage for a faithful,
no-extra-tooling bundle of the very JVM you tested against, and native-image when
startup latency and footprint matter more than build simplicity.
