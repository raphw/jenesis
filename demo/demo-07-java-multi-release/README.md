Multi-release JAR demo
======================

A **modular** project (MODULAR_TO_MAVEN layout) that builds a single
multi-release JAR: the module compiles at Java 21, and one utility class is
overridden for Java 25. At runtime the JVM picks the implementation matching its
own feature version, so the same JAR prints a different line on Java 21 than on
Java 25. Its non-versioned counterpart is `../demo-02-java-modular`.

Layout
------

    demo/demo-07-java-multi-release
    |-- build/jenesis                                  symlink to ../../../sources/build/jenesis
    `-- sources
        |-- module-info.java                           @jenesis.release 21, @jenesis.main sample.Sample
        |-- sample/Sample.java                         entry point; prints the runtime version and the greeting
        |-- sample/Platform.java                       the Java 21 baseline utility
        `-- META-INF/versions/25/sample/Platform.java  the Java 25 override of that utility

With a `module-info.java` and no `pom.xml`, Jenesis auto-detects the
MODULAR_TO_MAVEN layout and emits a modular jar alongside a generated POM. The
`@jenesis.release 21` tag pins the main compile to Java 21. Any source under
`sources/META-INF/versions/<N>/` is a multi-release overlay: Jenesis compiles it
in a separate pass with `--release <N>` and writes the classes to
`META-INF/versions/<N>/` inside the JAR. When an overlay is produced, the build
marks the JAR's manifest with `Multi-Release: true`, which is what tells the JVM
to consult the versioned directory.

Build it
--------

From this directory:

    java build/jenesis/Project.java

The `javac` step compiles `module-info.java`, `Sample.java`, and the baseline
`Platform.java` at release 21, then runs a second pass over
`META-INF/versions/25/sample/Platform.java` at release 25. The produced
`classes.jar` carries both copies of `sample.Platform` and a `Multi-Release: true`
manifest.

Run it
------

`build/jenesis/Execute.java` builds the project, finds the module that declares an
`@jenesis.main`, and launches it:

    java build/jenesis/Execute.java

On a Java 25 runtime this prints:

    Running on Java 25.
    Hello from the Java 25 versioned implementation.

Run the same JAR on a Java 21 runtime and the baseline class is loaded instead:

    Running on Java 21.
    Hello from the Java 21 baseline implementation.

That difference is the whole point: one artifact, two implementations of
`sample.Platform`, selected by the JVM at launch from the version directory.

No dependencies
---------------

This module declares no `requires` beyond the implicit `java.base`, so there is
nothing to pin; the demo focuses solely on the multi-release packaging. For
module dependency resolution and pinning, see `../demo-02-java-modular`.
