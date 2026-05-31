Kotlin demo
===========

A minimal Maven-layout project that mixes Java and Kotlin sources. It shows
Jenesis detecting the `.kt` sources and driving the Kotlin compiler through the
default `JavaMultiProjectAssembler` - no Kotlin-specific configuration in the
`pom.xml` beyond the source directory - while `javac` also participates for the
companion `.java` file.

Layout
------

    demo/kotlin
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              Maven coordinates + <sourceDirectory>; ships pinned (see below)
    |-- sources/sample/Greeter.java   a plain Java class
    `-- sources/sample/Sample.kt      Kotlin that calls Greeter

`Sample.kt` calls `Greeter().prefix()`, so the build compiles the Java source
first (with `javac`) and the Kotlin source against it - one project, two
compilers in the inferred chain.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis scans the sources, runs `javac` for `Greeter.java`, resolves the Kotlin
compiler (no version is declared, so it floats to the latest release), and
compiles `Sample.kt` against the Java output.

Pinned toolchain
----------------

This demo is committed **already pinned**: the `pom.xml` carries a
`<!--jenesis.pin-->` comment block recording the resolved Kotlin compiler
closure. Because the compiler resolves on its own qualified trail (`kotlin`),
its closure is pinned in this Maven-ignored comment rather than in
`<dependencyManagement>`, keeping it separate from the project's own
dependencies:

    <!--jenesis.pin
    @kotlin/org.jetbrains.kotlin/kotlin-compiler-embeddable 2.4.0-RC2
    @kotlin/org.jetbrains.kotlin/kotlin-stdlib 2.4.0-RC2
    ...
    -->

The leading `@` marks the `kotlin` qualifier under the POM's default `maven`
prefix. Re-running `java build/jenesis/Project.java pin` is idempotent.
