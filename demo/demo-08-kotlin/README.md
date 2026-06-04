Kotlin demo
===========

Build a project that mixes Java and Kotlin in one module of the Java module
system, and export a package that holds only Kotlin. You point Jenesis at the
project, it compiles the `.java` and `.kt` sources together, and emits a modular
jar alongside a generated POM - there is no build script to write.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis auto-detects the MODULAR_TO_MAVEN layout (a `module-info.java`, no
`pom.xml`), resolves the Kotlin compiler, compiles the module, and emits a
modular jar alongside a generated POM.

Layout
------

The module declares its dependency in `module-info.java` and lays the mixed
sources out under one package:

    demo/demo-08-kotlin
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    `-- sources
        |-- module-info.java       requires kotlin.stdlib; exports sample; exports sample.pure
        `-- sample
            |-- Greeter.java       a Java type in the exported 'sample' package
            |-- Sample.kt          Kotlin that calls Greeter
            `-- pure
                `-- Pure.kt        a pure-Kotlin package, exported with no Java type

How Jenesis builds it
---------------------

Jenesis detects the `.kt` sources and drives the Kotlin compiler through the
default `JavaMultiProjectAssembler`, with `javac` participating for
`module-info.java` and the companion `.java` file. The module exports both a
mixed package and a package that holds only Kotlin.

Compile order and exporting a Kotlin package
--------------------------------------------

The inferred chain compiles Kotlin first, then `javac`. `kotlinc` reads the
`.java` sources for symbol resolution, so `Sample.kt` can call `Greeter`, but it
emits only Kotlin classes; `javac` then compiles `Greeter.java` and
`module-info.java`. When `javac` validates the `exports` directives, it sees the
already-compiled Kotlin classes through `--patch-module`, so a package that
holds only Kotlin classes can be exported.

That is what `sample.pure` shows: it contains a single Kotlin class and no Java
type, yet `module-info.java` exports it. The order is what makes this work -
`javac` validates `exports <package>` against the classes already in the module,
so the Kotlin classes must exist first. `kotlinc` and `scalac` can read `.java`
as source, so they run ahead of `javac`; `groovyc` cannot, which is why the
`groovy` demo's exported package still needs a Java type.

Pinning
-------

The module's closure is pinned with `@jenesis.pin` tags on the module
declaration: `kotlin.stdlib` (the module's own dependency, by module name) and
the Kotlin compiler toolchain on its independent `@kotlin` trail (each
`maven@kotlin/...` coordinate with a version and SHA-256 checksum). Because the
two trails are separate, the running compiler is locked independently of the
`kotlin.stdlib` the module ships against. Run `java build/jenesis/Project.java
pin` to record or refresh the tags; re-running leaves the declaration unchanged.
