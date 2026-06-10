Scala demo
==========

Build a project that mixes Java and Scala 3 in one module of the Java module
system, and export a package that holds only Scala. You point Jenesis at the
project, it compiles the `.java` and `.scala` sources together, and emits a
modular jar alongside a generated POM - there is no build script to write.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis auto-detects the MODULAR_TO_MAVEN layout (a `module-info.java`, no
`pom.xml`), resolves the Scala compiler, compiles the module, and emits a
modular jar alongside a generated POM. The `requires scala.library` directive
resolves to `org.scala-lang:scala-library`, the jar that carries the Scala
standard library.

Layout
------

The module declares its dependency in `module-info.java` and lays the mixed
sources out under one package:

    demo/demo-12-scala
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    `-- sources
        |-- module-info.java       requires scala.library; exports sample; exports sample.pure
        `-- sample
            |-- Greeter.java       a Java type in the exported 'sample' package
            |-- Sample.scala       Scala that calls Greeter
            `-- pure
                `-- Pure.scala     a pure-Scala package, exported with no Java type

How Jenesis builds it
---------------------

Jenesis detects the `.scala` sources and drives the Scala compiler through the
default `InferredMultiProjectAssembler`, with `javac` participating for
`module-info.java` and the companion `.java` file. The module exports both a
mixed package and a package that holds only Scala.

Compile order and exporting a Scala package
-------------------------------------------

The inferred chain compiles Scala first, then `javac`. `scalac` reads the `.java`
sources for symbol resolution, so `Sample.scala` can call `Greeter`, but it emits
only Scala classes; `javac` then compiles `Greeter.java` and `module-info.java`.
When `javac` validates the `exports` directives, it sees the already-compiled
Scala classes through `--patch-module`, so a package that holds only Scala can be
exported - that is what `sample.pure` shows: a single Scala class, no Java type,
yet exported.

One Scala-specific detail: `scalac` is not handed `module-info.java`. Its Java
source parser cannot read a module declaration (it fails with "';' expected but
'.' found" on the dotted module name), so Jenesis withholds `module-info.java`
from `scalac` and lets `javac` own it; `scalac` still receives the other `.java`
sources for resolution.

The Scala standard library on the module path
---------------------------------------------

A single `requires scala.library` pulls in the whole Scala standard library as one
module: the library lives entirely in `scala-library`, and `scala3-library_3` is an
empty aggregator, so nothing splits `package scala` across two modules and the
Java module system accepts it on the module path. That is why this demo builds as a
plain module on the module path with no hand-written build script.

Pinning
-------

The module's closure is pinned with `@jenesis.pin` tags on the module
declaration: the module's own dependency in the default `main` group
(the bare `scala.library` Java module name, plus its `org.scala-lang/scala-library`
Maven coordinate) and
the Scala compiler toolchain in its own `scala` group (each
`scala/maven/...` coordinate with a version and SHA-256 checksum). The latest
Scala `<release>` on Maven Central is often a release candidate, so pinning is
what keeps an otherwise floating build from drifting onto a fresh `-RC`. Run
`java build/jenesis/Project.java pin` to record or refresh the tags; re-running
leaves the declaration unchanged.
