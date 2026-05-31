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

Why a POM and not a `module-info.java`?
---------------------------------------

It is not that the Groovy runtime cannot be addressed as a module: the
`org.apache.groovy:groovy` jar carries an `Automatic-Module-Name:
org.apache.groovy` manifest entry, so a `requires org.apache.groovy` resolves as
an automatic module and Jenesis downloads it without complaint. The blocker is
`groovyc` itself.

The inferred chain compiles in the order `javac` then `groovyc`, and `groovyc`
performs *joint compilation*: it is handed every `.java` source in the module -
including `module-info.java` - and runs its own internal `javac` on a plain class
path with no module path. A `module-info.java` therefore fails to compile under
`groovyc` with `module not found: org.apache.groovy` (the jar is on the class
path, not the module path) and `file should be on source path, or on patch path
for module`. There is a second obstacle even before that: a Groovy-only package
is empty when the chain's leading `javac` validates `exports`, because the only
type in the package is produced later by `groovyc`, so the module would also need
a companion Java type just to get that far - and `groovyc` still rejects the
`module-info.java` afterwards.

In short, `groovyc` has no Java module system support, so the inferred chain
cannot compile a `module-info.java` Groovy module. A Maven-layout (POM) build
sidesteps this entirely: the Groovy runtime is consumed from the class path,
where no `module-info.java` is compiled at all. (The `scala` demo also stays on a
POM, but for a different reason - its standard library is split across jars that
share packages; see that demo's `README.md`.)

Pinned toolchain
----------------

This demo is committed **already pinned**: the `pom.xml` carries a
`<!--jenesis.pin-->` comment block recording the resolved Groovy compiler on its
own qualified trail (`groovy`), separate from the project's own dependencies:

    <!--jenesis.pin
    @groovy/org.apache.groovy/groovy 5.0.6
    -->

Re-running `java build/jenesis/Project.java pin` is idempotent.
