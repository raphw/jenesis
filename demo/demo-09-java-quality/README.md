Java code-quality demo
======================

Wire the inferred code-quality tools into a single modular Java project. There
is no build script and no plugin to configure: a tool turns itself on when its
configuration file is present at the project root. This demo ships four of them,
so Checkstyle and PMD lint the sources, SpotBugs analyses the compiled classes,
and `google-java-format` verifies the source layout. Each tool resolves in its
own group, independently of the module's own dependencies (here, none).

This is the code-quality counterpart to the plain Java demos that precede it;
the language-specific tools follow each language demo (`../demo-15-kotlin-quality`,
`../demo-18-scala-quality`, `../demo-20-groovy-quality`).

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis auto-detects the MODULAR_TO_MAVEN layout (a `module-info.java`, no
`pom.xml`), compiles the module, and runs every tool whose configuration file it
finds. The first build downloads the tools, so it takes a while; later builds
reuse the cache.

Layout
------

The configuration files sit at the project root - the *configuration directory*,
which defaults to the root and can be moved with
`-Djenesis.project.configuration=<dir>`:

    demo/demo-09-java-quality
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- checkstyle.xml             activates Checkstyle (lints sources)
    |-- pmd.xml                    activates PMD (lints sources)
    |-- spotbugs-exclude.xml       activates SpotBugs (analyses classes)
    |-- jenesis.properties         selects the Java formatter (google)
    `-- sources
        |-- module-info.java
        `-- sample
            `-- Sample.java

How Jenesis infers the tools
----------------------------

Each tool is inferred from the presence of its configuration file, never from a
build script:

    checkstyle.xml          -> Checkstyle
    pmd.xml                 -> PMD
    spotbugs-exclude.xml    -> SpotBugs
    scalastyle-config.xml   -> Scalastyle, and so on for the other languages

Jenesis binds the matched file from the configuration directory into the build
graph so the tool finds it. That binding is what lets a root-level filter reach
SpotBugs, which runs *after* `javac` and so only ever sees compiled classes, not
the project root. The Java formatter has no configuration file of its own, so it
is selected explicitly instead, here through `jenesis.properties`:

    jenesis.format.java=google

`palantir` selects the Palantir formatter instead; omitting the key runs no Java
formatter at all.

A discovered tool can be switched off without deleting its configuration file by
setting its property to `false`. By default every property is `true`, so file
discovery alone decides; the property is an opt-out:

    jenesis.source.<tool>        Checkstyle, PMD, Detekt, Ktlint, Scalastyle, Scalafmt, CodeNarc
    jenesis.validator.spotbugs   SpotBugs
    jenesis.format.<tool>        the Ktlint / Scalafmt formatters

For example `-Djenesis.source.checkstyle=false` keeps `checkstyle.xml` in place
but skips Checkstyle, while PMD and SpotBugs still run.

Where the tools run and what they produce
-----------------------------------------

Checkstyle and PMD run on the sources, in parallel with compilation. SpotBugs is
a *validator* in the Java toolchain: it runs once the classes exist. Each tool
writes an XML report into a `reports/<tool>/` subfolder of its step's output, for
example:

    target/build/.../assemble/check/checkstyle/check/output/reports/checkstyle/checkstyle-report.xml
    target/build/.../assemble/check/pmd/check/output/reports/pmd/pmd-report.xml
    target/build/.../assemble/binary/validate/spotbugs/check/output/reports/spotbugs/spotbugs-report.xml

A `stage` build collects every report from every module into one place, each kind
in its own subfolder: `target/stage/reports/<kind>/<module>/`, for example
`target/stage/reports/checkstyle/sources/checkstyle-report.xml`. Each module's
`inventory.properties` also records a `<module>.report.<kind>` entry pointing at
the report, so other tools can find it.

By default the linters are report-only: they record findings but do not fail the
build. Pass `.strict(true)` when wiring a tool yourself (see
`../demo-29-custom-assembler`) to turn a finding into a build failure.

Formatting: verify, and how to reformat
---------------------------------------

The Java formatter is wired in *verify* mode. It never rewrites your sources
during a normal build; instead it fails the build when a file is not already
formatted, which makes it a continuous-integration gate. Try it by indenting
`Sample.java` with spare spaces and rebuilding: the `format` step fails.

To apply the formatter and rewrite the sources in place, run the build with the
rewrite switch:

    java -Djenesis.format.rewrite=true build/jenesis/Project.java

The same switch drives `ktlint` and `scalafmt` in the language demos. After a
rewrite, a plain `java build/jenesis/Project.java` passes the verify gate again.

Pinning
-------

Each tool resolves a floating `RELEASE` in its own group (`checkstyle`, `pmd`,
`spotbugs`, `google-java-format`), kept separate from the module's `main`-group
dependencies. Running `java build/jenesis/Project.java pin` records every
resolved tool jar with its `SHA-256` into `@jenesis.pin` tags, exactly as the
other demos pin their compilers. These closures are large (PMD's CLI bundle
alone pulls in well over a hundred artifacts), so this demo leaves them floating
to keep the descriptor readable; pin them when you want a reproducible,
checksum-verified tool chain.
