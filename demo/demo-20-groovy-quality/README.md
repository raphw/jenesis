Groovy code-quality demo
========================

The Groovy counterpart to `../demo-09-java-quality`, following the Groovy demo
that precedes it. A `codenarc.xml` activates CodeNarc, which lints the Groovy
sources. There is no inferred Groovy formatter, so unlike the Java, Kotlin, and
Scala demos this one is lint-only. As elsewhere, there is no build script: the
tool turns on because its configuration file is present at the project root.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis compiles the Groovy module (resolving the pinned Groovy compiler) and
runs CodeNarc against the sources. The first build downloads the compiler and
CodeNarc, so it takes a while.

Layout
------

    demo/demo-20-groovy-quality
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- codenarc.xml               activates CodeNarc
    `-- sources
        |-- module-info.java
        `-- sample
            |-- Greeter.java       a Java type in the exported package
            `-- Sample.groovy

How Jenesis infers the tool
---------------------------

    codenarc.xml    -> CodeNarc (lints the Groovy sources)

CodeNarc runs with `-rulesetfiles=file:codenarc.xml` over the `.groovy` sources
and writes an XML report under its step's output folder:

    target/build/.../assemble/check/codenarc/check/output/codenarc-report.xml

CodeNarc is report-only by default; it records findings without failing the
build. As in the Groovy demo, the exported package keeps a Java type
(`Greeter`), because `groovyc` does not read `.java` as source and so cannot
contribute a class to a package it does not also define (see `../demo-19-groovy`).

Pinning
-------

The Groovy compiler is pinned in its own `groovyc` group (see `../demo-19-groovy`).
CodeNarc resolves a floating `RELEASE` in its own `codenarc` group; run
`java build/jenesis/Project.java pin` to record it with checksums when you want a
fully reproducible tool chain.
