Scala code-quality demo
=======================

The Scala counterpart to `../demo-09-java-quality`, following the Scala demo that
precedes it. A small Scala module is linted by Scalastyle and scalafmt, and
scalafmt doubles as a formatter that verifies the source layout. As elsewhere,
there is no build script: a tool turns on when its configuration file is present
at the project root.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis compiles the Scala module (resolving the pinned Scala compiler) and runs
every tool whose configuration file it finds. The first build downloads the
compiler and the tools, so it takes a while.

Layout
------

    demo/demo-16-scala-quality
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- scalastyle-config.xml      activates Scalastyle
    |-- .scalafmt.conf             activates scalafmt (lint and format)
    `-- sources
        |-- module-info.java
        `-- sample
            `-- Sample.scala

How Jenesis infers the tools
----------------------------

    scalastyle-config.xml    -> Scalastyle (lints the Scala sources)
    .scalafmt.conf           -> scalafmt (lints the sources, and formats them)

Scalastyle runs with `-c scalastyle-config.xml`; scalafmt runs against
`.scalafmt.conf`. Both inspect the `.scala` sources in parallel with
compilation and write a report under their step's output folder. The
`.scalafmt.conf` pins the formatter's own `version`, so the formatting is
reproducible no matter which scalafmt release resolves. Both linters are
report-only by default.

Formatting: verify, and how to reformat
---------------------------------------

The same `.scalafmt.conf` wires scalafmt as a *formatter* in verify mode: a
normal build fails if a `.scala` file is not already formatted, but never
rewrites it. To apply scalafmt and rewrite the sources in place, run the build
with the rewrite switch:

    java -Djenesis.format.rewrite=true build/jenesis/Project.java

This mirrors the `google-java-format` and `ktlint` rewrites in
`../demo-09-java-quality` and `../demo-13-kotlin-quality`.

Pinning
-------

The Scala compiler is pinned in its own `scalac` group (see `../demo-15-scala`).
Scalastyle and scalafmt each resolve a floating `RELEASE` in their own group;
run `java build/jenesis/Project.java pin` to record them with checksums when you
want a fully reproducible tool chain.
