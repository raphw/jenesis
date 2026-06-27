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
closed-world analysis cannot see, so it shows the whole loop in a single build: a
test captures the reachability metadata, and the native build picks it up directly.
`Sample` loads its greeter by a name assembled at run time -
`Class.forName("sample." + name)` - and invokes it reflectively, so neither the class
nor its methods are statically reachable.

This demo is local-only: it needs the GraalVM `native-image` tool (and, for the
capture, the GraalVM tracing agent) on the build's toolchain, which the CI runners
do not carry, so unlike most demos it is not part of the CI matrix.

Build the native image
----------------------

A single build captures the metadata and compiles the image in one pass. The capture
attaches GraalVM's tracing agent to the test run (`-Djenesis.observe.native=true`),
and the native compilation reads what it recorded (`-Djenesis.java.native=true`).
`native-image` is located the same way every external tool is - `GRAALVM_HOME` first,
then the running JDK's own `bin/` (`java.home`), then `PATH` - so either run the build
with a GraalVM JDK:

    ~/.sdkman/candidates/java/25.0.3-graal/bin/java -Djenesis.observe.native=true -Djenesis.java.native=true build/jenesis/Project.java stage

or keep your usual JDK 25 and point `GRAALVM_HOME` at a GraalVM install (here one
managed by [SDKMAN](https://sdkman.io/), `sdk install java 25.0.3-graal`):

    GRAALVM_HOME=~/.sdkman/candidates/java/25.0.3-graal java -Djenesis.observe.native=true -Djenesis.java.native=true build/jenesis/Project.java stage

The build compiles the modules, runs the test under the agent, then runs
`native-image` over the produced module path. The image build is the slow step (a
minute or two - it analyses the whole reachable program), after which the `stage`
goal collects the standalone binary into its canonical target directory:

    target/stage/native/output/demo.graal.image

Run it directly - there is no `java` in the command, because there is no JVM:

    target/stage/native/output/demo.graal.image            # Hello, world, from a native binary built by Jenesis (reflectively)!
    target/stage/native/output/demo.graal.image Ada        # Hello, Ada, from a native binary built by Jenesis (reflectively)!

The result is a ~15 MB ELF executable (`.exe` on Windows, a Mach-O binary on macOS)
that links `java.base` statically and starts without a runtime. The greeting comes
back through reflection - which only works because the test capture told native-image
to keep `sample.Greeter`. Build with `-Djenesis.java.native=true` alone (no
`-Djenesis.observe.native=true`, so nothing captures the reflection) and the binary
fails at run time with `ClassNotFoundException: sample.Greeter`: the closed-world
analysis dropped the class it never saw referenced.

How the test capture reaches the image, in one build
----------------------------------------------------

Where does that metadata come from? Jenesis runs GraalVM's tracing agent the same way
it runs JaCoCo coverage (`../demo-20-code-coverage`): as a **test-observation engine**.
The `demo.graal.image.test` module exercises exactly the reflective call `Sample`
makes, so `-Djenesis.observe.native=true` attaches `-agentlib:native-image-agent` to
the test JVM, and the agent records every reflective, JNI, resource and proxy access
the test triggers, into a `native-image/` directory in the test's own output.

The reason this can feed the same build is the assembler's two-phase layout. An
assembler returns an `AssemblyDescriptor` - a per-module *build* phase plus optional
later phases - and the heavy packaging steps, `native-image` among them, run in a
*package* phase wired as a second, cross-module level: it runs after every module has
built. Each module's `inventory.properties` names its `artifact`; a test module's also
records the artifact it tests (`test`) and its capture (`nativeimage`), recorded the same
cross-module way as `package`/`image`/`jmod` - but under `nativeimage/`, not `reports/`,
because the metadata is build-internal (fed back into the image) rather than a report
meant for an external tool. The package phase reads its sibling modules' inventories,
and a `reachability` step collects the capture of the test module whose `test` names
this module's `artifact` - so each native image gets exactly its own test's metadata,
never another module's - into a config directory `native-image` points
`-H:ConfigurationFileDirectories` at, discovering it by content exactly as it would a
committed file. The main module never has to depend on the test module that `requires`
it - the cycle that would otherwise force a hand-off never arises.

For review, the capture is readable in the test module's build output:

    target/build/modules/compose/module/<test-module>/produce/assemble/observed/native-image/report/output/nativeimage/reachability-metadata.json

which for this demo holds exactly the `sample.Greeter` constructor and `greet`
method. The committed-metadata route still exists for published artifacts: drop a
file under `sources/META-INF/native-image/` and it is copied verbatim into the jar
(files under `META-INF/` are resources), where `native-image` - and a plain JVM, and
any other tool that honours `META-INF/native-image/` - discovers it. This demo needs
no such file; the single build feeds the metadata straight through.

How the native-image step fits the build
-----------------------------------------

Native compilation is opt-in through a single boolean property,
`-Djenesis.java.native=true`. When it is set, `InferredMultiProjectAssembler` wires a
`native-image` step in the package phase that runs for every module declaring a main
class - exactly the `@jenesis.main` field that `../demo-06-java-modular-executable`
uses for `jpackage`, read from the same `module.properties`. Modules without a main
class (the test module) produce no image. The step reuses the launcher coordinates the
build already derived - the produced module path and `--module <module>/<main-class>` -
adds the test-captured config directory, and invokes:

    native-image --no-fallback -H:ConfigurationFileDirectories=<captured> -o <name> --module-path <jars> --module <module>/<main-class>

The produced binary is recorded in the package phase's `inventory.properties` under
this module's `native` key, and the `STAGE` module's `native` step collects it into
`stage/native/output/` - the native-image analogue of `stage/packages` (jpackage) and
`stage/runtime` (jlink).

Layout
------

    demo/demo-38-native-image
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- sources/
    |   |-- module-info.java     module demo.graal.image (@jenesis.main sample.Sample)
    |   `-- sample/
    |       |-- Sample.java       main; loads the greeter reflectively by a run-time name
    |       `-- Greeter.java      the reflective target (reached only via Class.forName)
    `-- test/
        |-- module-info.java     open module demo.graal.image.test (@jenesis.test demo.graal.image)
        `-- imagetest/SampleTest.java   exercises the reflection so the agent records it

With a `module-info.java` and no `pom.xml`, Jenesis auto-detects the
MODULAR_TO_MAVEN layout, exactly as `../demo-02-java-modular` does. The module is
named `demo.graal.image` rather than `demo.native.image` because `native` is a Java
reserved word and cannot be a module-name segment. There is no committed
`META-INF/native-image/` - the metadata is captured and consumed within the build.

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
