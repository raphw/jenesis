Scala demo
==========

A minimal Maven-layout project that mixes Java and Scala 3 sources. It shows
Jenesis detecting the `.scala` sources and driving the Scala compiler through
the default `JavaMultiProjectAssembler` - no Scala-specific configuration in the
`pom.xml` beyond the source directory - while `javac` also participates for the
companion `.java` file.

Layout
------

    demo/scala
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              Maven coordinates + <sourceDirectory>; ships pinned (see below)
    |-- sources/sample/Greeter.java   a plain Java class
    `-- sources/sample/Sample.scala   Scala that calls Greeter

`Sample.scala` calls `Greeter().prefix()`, so the build compiles the Java source
first (with `javac`) and the Scala source against it - one project, two
compilers in the inferred chain.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis scans the sources, runs `javac` for `Greeter.java`, resolves the Scala
compiler (no version is declared, so it floats to the latest release), and
compiles `Sample.scala` against the Java output.

Why a POM and not a `module-info.java`?
---------------------------------------

The `kotlin` demo next door is a pure module (a `module-info.java`, no `pom.xml`)
because `kotlin.stdlib` ships as a single named module that a `requires` clause
can resolve cleanly. Scala cannot be expressed that way. The Scala 3 standard
library is split across several jars - `scala3-library_3` and the Scala 2.13
`scala-library` it builds on - and they all export the same `package scala` (plus
`scala.runtime`, `scala.util`, and so on). The Java module system forbids two
modules on the module path from exporting the same package, so a
`module-info.java` that `requires` the Scala stdlib fails to compile with
"reads package scala from both ..." split-package errors. There is no module
name that pulls in a single, conflict-free Scala stdlib.

A Maven-layout (POM) build sidesteps this entirely: the dependencies land on the
class path rather than the module path, where the unnamed module merges packages
across jars and the split never arises. That is why this demo keeps a `pom.xml`
while the Kotlin one does not.

Pinned toolchain
----------------

This demo is committed **already pinned**: the `pom.xml` carries a
`<!--jenesis.pin-->` comment block recording the resolved Scala compiler
closure on its own qualified trail (`scala`), separate from the project's own
dependencies:

    <!--jenesis.pin
    @scala/org.scala-lang/scala3-compiler_3 3.8.3
    @scala/org.scala-lang/scala3-library_3 3.8.3
    ...
    -->

Re-running `java build/jenesis/Project.java pin` is idempotent.

The Scala line publishes `scala-library` and the `scala3-*` artifacts in lockstep,
and the latest `<release>` on Maven Central is often a release candidate, so an
unpinned build floats the compiler to an `-RC` version. Pinning
`scala3-compiler_3` to a stable version (here `3.8.3`) holds the whole resolved
closure on a non-RC release. The pin records versions only; the compiler trail's
downloaded jars are not exposed downstream, so `pin` cannot yet attach content
checksums to these entries the way it does for a module's own dependencies.
