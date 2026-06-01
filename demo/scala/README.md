Scala demo
==========

A MODULAR_TO_MAVEN project (a `module-info.java`, no `pom.xml`) that mixes Java
and Scala 3 in one module of the Java module system. It shows Jenesis detecting
the `.scala` sources and driving the Scala compiler through the default
`JavaMultiProjectAssembler`, with `javac` participating for `module-info.java`
and the companion `.java` file. The module exports both a mixed package and a
package that holds only Scala.

Layout
------

    demo/scala
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    `-- sources
        |-- module-info.java       requires scala.library; exports sample; exports sample.pure
        `-- sample
            |-- Greeter.java       a Java type in the exported 'sample' package
            |-- Sample.scala       Scala that calls Greeter
            `-- pure
                `-- Pure.scala     a pure-Scala package, exported with no Java type

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis auto-detects the MODULAR_TO_MAVEN layout (a `module-info.java`, no
`pom.xml`), resolves the Scala compiler, compiles the module, and emits a
modular jar alongside a generated POM. The `requires scala.library` directive
resolves to `org.scala-lang:scala-library`, the jar that carries the Scala
standard library.

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
plain module with no `pom.xml`.

Pinning
-------

The module's closure is pinned with `@jenesis.pin` tags on the module
declaration: `scala.library` (the module's own dependency, by module name) and
the Scala compiler toolchain on its independent `@scala` trail (each
`maven@scala/...` coordinate with a version and SHA-256 checksum). The latest
Scala `<release>` on Maven Central is often a release candidate, so pinning is
what keeps an otherwise floating build from drifting onto a fresh `-RC`. Run
`java build/jenesis/Project.java pin` to record or refresh the tags; re-running
leaves the declaration unchanged.
