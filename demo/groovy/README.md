Groovy demo
===========

A minimal Maven-layout project with a Groovy source. It shows Jenesis detecting
the `.groovy` sources and driving the Groovy compiler through the default
`JavaMultiProjectAssembler` - no Groovy-specific configuration in the `pom.xml`
beyond the source directory.

Layout
------

    demo/groovy
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              Maven coordinates + <sourceDirectory>; ships pinned (see below)
    `-- sources/sample/Sample.groovy   a Groovy class

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis scans the sources, resolves the Groovy compiler (no version is declared,
so it floats to the latest release), and compiles `Sample.groovy`.

Unlike the `kotlin` and `scala` demos, this one does not mix in a companion
`.java` source. Groovy performs *joint compilation*: `groovyc` compiles any
`.java` files handed to it and emits their `.class` files itself, which collides
with the class files the inferred chain's `javac` step already produced for the
same sources. A single-language Groovy module sidesteps that; a Groovy class can
still call any Java type that arrives as a compiled dependency on the class path.

A Maven-layout (POM) project rather than a `module-info.java`: the Groovy
runtime, like the Scala standard library, is not published as a single named
Java module, so it is consumed from the class path that the Maven layout
provides rather than the module path a `module-info.java` would require.

Pinned toolchain
----------------

This demo is committed **already pinned**: the `pom.xml` carries a
`<!--jenesis.pin-->` comment block recording the resolved Groovy compiler on its
own qualified trail (`groovy`), separate from the project's own dependencies:

    <!--jenesis.pin
    @groovy/org.apache.groovy/groovy 5.0.1
    -->

Re-running `java build/jenesis/Project.java pin` is idempotent.
