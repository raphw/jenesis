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

On the module path and the old split-package concern
-----------------------------------------------------

An earlier version of this demo stayed on a `pom.xml` to avoid a split package:
the Scala standard library used to be spread across `scala-library` and
`scala3-library_3`, both exporting `package scala`, which the Java module system
rejects on the module path. As of Scala 3.8.3 that no longer applies -
`scala3-library_3` is an empty aggregator and the standard library lives entirely
in `scala-library` - so a single `requires scala.library` pulls in a
conflict-free module and the project builds as a module with no `pom.xml`.

Pinning
-------

This demo is currently unpinned: with no version declared, the Scala compiler and
`scala-library` float to the latest release. Note that the latest Scala
`<release>` on Maven Central is often a release candidate, so an unpinned build
may float the compiler to an `-RC` version; recording the closure with
`@jenesis.pin` tags on the module declaration is still pending (see the
repository notes on the pin goal).
