Kotlin demo
===========

A MODULAR_TO_MAVEN project (a `module-info.java`, no `pom.xml`) that mixes Java
and Kotlin in one module of the Java module system. It shows Jenesis detecting
the `.kt` sources and driving the Kotlin compiler through the default
`JavaMultiProjectAssembler`, with `javac` participating for `module-info.java`
and the companion `.java` file. The module exports both a mixed package and a
package that holds only Kotlin.

Layout
------

    demo/kotlin
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    `-- sources
        |-- module-info.java       requires kotlin.stdlib; exports sample; exports sample.pure
        `-- sample
            |-- Greeter.java       a Java type in the exported 'sample' package
            |-- Sample.kt          Kotlin that calls Greeter
            `-- pure
                `-- Pure.kt        a pure-Kotlin package, exported with no Java type

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis auto-detects the MODULAR_TO_MAVEN layout (a `module-info.java`, no
`pom.xml`), resolves the Kotlin compiler, compiles the module, and emits a
modular jar alongside a generated POM.

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

This demo is currently unpinned: with no version declared, the Kotlin compiler
and `kotlin.stdlib` float to the latest release. Recording a MODULAR_TO_MAVEN
module's closure with `@jenesis.pin` tags on the module declaration is still
pending; see the repository notes on the pin goal.
