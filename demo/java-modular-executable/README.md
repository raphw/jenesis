Executable Java (modular) demo
==============================

A minimal **modular** project that is also **runnable**: a `module-info.java`
plus a single Java source with a `main` method and one real module dependency
(`org.slf4j`), packaged into a native application image by the JDK's `jpackage`.
It is the executable counterpart of `../java-modular`, and its POM-based sibling
is `../java-pom-executable`.

Declaring the entry point with `@jenesis.main`
----------------------------------------------

A module becomes runnable when the build knows its main class. In the modular
layout that is declared with a `@jenesis.main` Javadoc tag on `module-info.java`:

    /**
     * @jenesis.release 25
     * @jenesis.main sample.Sample
     */
    module demo.modular.executable {
        exports sample;
    }

The build parses that tag and records `main=sample.Sample` in the module's
`module.properties`. That single field is what both the `Execute` launcher and
the `package` step key off to treat the module as runnable. (A POM project has
no `module-info.java`; its equivalent is a `<mainClass>` POM property, shown in
`../java-pom-executable`. Both layouts converge on the same `module.properties`
`main` field.)

Layout
------

    demo/java-modular-executable
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Run.java       stages with jpackage enabled, then runs the image
    `-- sources/
        |-- module-info.java     module demo.modular.executable (@jenesis.main, requires org.slf4j, pinned)
        `-- sample/Sample.java   main(String[] args); uses org.slf4j via `import module org.slf4j;`

With a `module-info.java` and no `pom.xml`, Jenesis auto-detects the
MODULAR_TO_MAVEN layout, exactly as `../java-modular` does. Because `org.slf4j`
is a real module on the module path, `Sample.java` pulls it in with a single
module import - `import module org.slf4j;` - the same style the project's own
test sources use (`import module org.junit.jupiter.api;`). The produced image
bundles `slf4j-api.jar` next to the application jar: jpackage packages the whole
runtime closure, not just your own code.

How packaging fits the build
----------------------------

Packaging is opt-in through a single system property, `-Djenesis.java.package`.
When it is set, `JavaMultiProjectAssembler` wires a per-module `package` step that
runs `jpackage` for every module declaring a main class (modules without one are
skipped). The property's value is the `jpackage --type`; a bare flag defaults to
`app-image`, a self-contained launcher plus bundled runtime that needs no
platform-native tooling. `--name` / `--main-jar` / `--main-class` are derived
automatically (the name from the module's coordinate, here `demo.modular.executable`).

Each produced image is then collected by the `STAGE` module's `packages` step
into `stage/packages/`, the staging analogue of `stage/maven` and `stage/modular`:

    target/stage/
    |-- modular/output/...                          the published modular jar
    `-- packages/output/demo.modular.executable/
        |-- bin/demo.modular.executable             the launcher
        `-- lib/                                     app jars + bundled runtime

Run it
------

From this directory, pass the arguments you want the packaged application to
receive on its command line:

    java build/Run.java Ada Lovelace

`Run.java` configures packaging directly on the assembler -
`new JavaMultiProjectAssembler(false, null, "app-image")`, the in-code equivalent of
`-Djenesis.java.package=app-image` with no system property - builds the `stage` goal,
then reads the image folder from the `stage/packages` entry of the map that
`build("stage")` returns (a fixed build target) and launches the produced platform
launcher with your arguments. The packaged app prints:

    Hello, Ada Lovelace, from a packaged Java module built by Jenesis!

(Because no SLF4J backend is bundled, SLF4J prints a one-time "no providers" notice
and uses a no-op logger - the `slf4j-api` jar is still bundled and on the app's
classpath.) With no arguments it greets `world`. Building the plain
`java build/jenesis/Project.java` (no `package` property) compiles and jars the
module exactly as `../java-modular` does, without producing an image.

Pure modular layout
-------------------

Like `../java-modular`, this demo auto-detects **MODULAR_TO_MAVEN** (a modular jar
plus a generated `pom.xml`, dependencies resolved via Maven-coordinate translation).
To build and package under the pure **MODULAR** layout instead - dependencies resolved
purely by Java module name, and a modular jar with no `pom.xml` - pass the layout
override on the same `Run.java` invocation, since `Run.java` calls `resolveProperties()`
which honors it:

    java -Djenesis.project.layout=modular build/Run.java Ada Lovelace

The packaged application image is identical either way; only the resolve and whether a
`pom.xml` is emitted differ.
