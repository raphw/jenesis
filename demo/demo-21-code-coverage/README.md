Code-coverage demo
===================

Measure test coverage with JaCoCo, inferred as a *test observation* engine. A
small Maven project has one class and a test that exercises part of it; the
presence of a `jacoco.properties` file enables the engine, wrapping the test run
so JaCoCo records what the tests touch and renders an HTML and XML report. No
build script and no JaCoCo configuration are needed.

This is the test-observation counterpart to the code-quality demos that precede
it: where those inspect sources and classes, this one observes the tests as they
run.

Build it
--------

From this directory:

    java build/jenesis/Project.java

The project ships a `jacoco.properties` marker file, so a plain build collects
coverage. Jenesis compiles the project, runs the test
under the JaCoCo agent, and renders the report. The first build downloads JUnit
and the JaCoCo tooling, so it takes a while.

Layout
------

    demo/demo-21-code-coverage
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    pins JUnit; sources in sources/, tests in test/
    |-- jacoco.properties          marker file; presence enables JaCoCo
    |-- sources
    |   `-- coverage
    |       `-- Calculator.java    add(...) and subtract(...)
    `-- test
        `-- coverage
            `-- CalculatorTest.java tests add(...) only

How coverage is inferred
------------------------

The default Java assembler runs tests through an `InferredTestObservationModule`,
which bundles the observation engines that are switched on. Today that is JaCoCo,
enabled by the presence of a `jacoco.properties` file (the `jenesis.observe.jacoco`
system property defaults to `true` and can be set to `false` to suppress it):

- With no engine enabled (the default for most projects), it is a plain test run.
- With coverage on, the test step is launched with the JaCoCo agent prepended as
  a `-javaagent`, writing its execution data (`jacoco.exec`) into the test step's
  own output. A downstream report step then runs the JaCoCo CLI over that data,
  the compiled classes, and the sources.

The agent instruments the run without touching your sources, and JaCoCo resolves
its agent and CLI in their own `jacoco` group, kept separate from the project's
dependencies.

Where the report lands
----------------------

    target/build/.../assemble/observed/test/executed/output/jacoco.exec
    target/build/.../assemble/observed/jacoco/report/output/reports/jacoco/index.html
    target/build/.../assemble/observed/jacoco/report/output/reports/jacoco/jacoco.xml

A `stage` build collects the report, like every other report kind, under
`target/stage/reports/jacoco/<module>/`, and each module's `inventory.properties`
records a `<module>.report.jacoco` entry pointing at it.

Open `index.html` to browse coverage. Because `CalculatorTest` exercises
`add(...)` but not `subtract(...)`, the report shows the project as partially
covered - coverage is reported, not enforced, so the build stays green.

Pinning
-------

JUnit is pinned in the POM the usual way. JaCoCo's agent and CLI resolve a
floating `RELEASE` in the `jacoco` group; run `java build/jenesis/Project.java
pin` to record them with checksums when you want a reproducible tool chain.
