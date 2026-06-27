Kotlin code-quality demo
========================

The Kotlin counterpart to `../demo-09-java-quality`, following the Kotlin demo
that precedes it. A small pure-Kotlin module is linted by detekt and ktlint, and
ktlint doubles as a formatter that verifies the source layout. As with the Java
demo, no build script is involved: each tool turns on because its configuration
file is present at the project root.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis compiles the Kotlin module (resolving the pinned Kotlin compiler) and
runs every tool whose configuration file it finds. The first build downloads the
compiler and the tools, so it takes a while.

Layout
------

    demo/demo-14-kotlin-quality
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- detekt.yml                 activates detekt
    |-- .editorconfig              activates ktlint (lint and format)
    `-- sources
        |-- module-info.java
        `-- sample
            `-- Sample.kt

How Jenesis infers the tools
----------------------------

The matched configuration files select the Kotlin tools the same way the Java
demo selects its own:

    detekt.yml       -> detekt (lints the Kotlin sources)
    .editorconfig    -> ktlint (lints the sources, and formats them)

detekt runs with `--config detekt.yml`; ktlint reads `.editorconfig` for its
rules. Both lint the `.kt` sources in parallel with compilation and write an XML
report under their step's output folder. By default they are report-only.

Formatting: verify, and how to reformat
---------------------------------------

The same `.editorconfig` also wires ktlint as a *formatter* in verify mode: a
normal build fails if a `.kt` file is not already ktlint-formatted, but never
rewrites it. To apply ktlint and rewrite the sources in place, run the build
with the rewrite switch:

    java -Djenesis.format.rewrite=true build/jenesis/Project.java

This is the Kotlin equivalent of the `google-java-format` rewrite shown in
`../demo-09-java-quality`. The `scalafmt` formatter in `../demo-17-scala-quality`
works the same way.

Pinning
-------

The Kotlin compiler is pinned in its own `kotlinc` group, locked independently of
the `kotlin.stdlib` the module ships against (see `../demo-13-kotlin`). detekt and
ktlint each resolve a floating `RELEASE` in their own group; run
`java build/jenesis/Project.java pin` to record them with checksums when you want
a fully reproducible tool chain.
