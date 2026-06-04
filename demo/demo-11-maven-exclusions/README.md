Maven dependency exclusions demo
================================

Exclude an unwanted transitive dependency so it never reaches the class path.
You declare one dependency with an `<exclusions>` block in a Maven-layout
`pom.xml`, point Jenesis at the project, and a bundled test proves the excluded
transitive is gone.

Build it
--------

    java build/jenesis/Project.java

The build compiles the sources and runs `ExclusionTest`, which checks the class
path directly:

- `commons-text`'s `StringSubstitutor.class` **is** present (the dependency
  resolved);
- `commons-lang3`'s `StringUtils.class` is **not** present (the exclusion took
  effect).

The test looks the classes up as **resources** rather than with
`Class.forName(...)`: loading a Commons Text class would link it against the
now-missing Commons Lang and fail with `NoClassDefFoundError` - which is itself a
good illustration of why excluding a hard transitive is usually something you do
to *replace* it, not to drop it outright. Resource lookup just asks whether each
`.class` is on the class path, which is exactly what the exclusion changes.

Like Maven, the exclusion applies to the whole module, test class path included:
test scope extends compile scope, so the pruned transitive is absent when the
tests run, not just when the main code does.

Layout
------

A single Maven-layout project: a `pom.xml`, the sources, and the test that proves
the exclusion took effect.

    demo-11-maven-exclusions
    |-- build/jenesis            symlink to ../../../sources/build/jenesis
    |-- pom.xml                  depends on commons-text, excluding its commons-lang3 transitive; ships pinned
    |-- sources/sample/Sample.java
    `-- test/sample/ExclusionTest.java   asserts commons-text is present and commons-lang3 is not

The dependency
--------------

Apache Commons Text normally pulls in Apache Commons Lang as a transitive
dependency. The POM keeps Commons Text but drops Lang:

    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-text</artifactId>
        <version>1.12.0</version>
        <exclusions>
            <exclusion>
                <groupId>org.apache.commons</groupId>
                <artifactId>commons-lang3</artifactId>
            </exclusion>
        </exclusions>
    </dependency>

Why only Maven?
---------------

Exclusions are a Maven-graph concept: a dependency drags in a transitive subtree,
and `<exclusions>` prunes part of it. A **modular** project does not have the same
problem to solve - a module only sees what its `module-info.java` `requires`, so
an unwanted transitive cannot silently appear on the module path, and there is no
`<exclusions>` equivalent to add. So this idea is shown here, and only here, on a
Maven project.

Pinned dependencies
-------------------

This demo ships **already pinned**: `java build/jenesis/Project.java pin` records
the resolved closure into `<dependencyManagement>` with `SHA-256` checksums.
Because `commons-lang3` is excluded, it is absent from that closure - the pinned
set contains `commons-text` and the JUnit test dependencies, but no Lang.
