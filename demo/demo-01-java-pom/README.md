Java (POM-based) demo
=====================

The simplest way to build a Maven-style Java project with Jenesis: a `pom.xml`
and your sources. You point Jenesis at the project and it resolves the declared
dependency, compiles against it, and produces a jar - there is no build script to
write. Its modular counterpart is `../demo-02-java-modular`.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis auto-detects the MAVEN layout from the `pom.xml`, resolves and downloads
`commons-lang3` from Maven Central (or `~/.m2`), and compiles `Sample.java`
against it.

Layout
------

    demo/demo-01-java-pom
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              Maven coordinates, <sourceDirectory>, one <dependency>; ships pinned
    `-- sources/sample/Sample.java

`Sample.java` uses `org.apache.commons.lang3.StringUtils`, which is what makes
the build resolve the real dependency. Under the hood Jenesis drives plain
`javac` through the default `JavaMultiProjectAssembler`.

Printing the dependency tree
----------------------------

To see what the build resolves, `-Djenesis.project.tree=true` prints the
dependency tree as the module resolves (a verbose toggle, not a build step):

    java -Djenesis.project.tree=true build/jenesis/Project.java

    Dependency tree:
    maven/org.apache.commons/commons-lang3 3.14.0 [compile]

Each node shows the property-file key, the requested version (with the negotiated
version inline when it differs), and the Maven scope; transitive dependencies nest
underneath and duplicates are dimmed and marked `(*)`.

Pinned dependency
-----------------

This demo ships **already pinned**. `java build/jenesis/Project.java pin`
records the resolved dependency (with its content checksum) in the POM's
`<dependencyManagement>` block:

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.apache.commons</groupId>
                <artifactId>commons-lang3</artifactId>
                <version>3.14.0</version>
                <!--Checksum/SHA-256/...-->
            </dependency>
        </dependencies>
    </dependencyManagement>

A POM-based pure-Java project is compiled by the JDK's `javac` - there is no
*resolved* compiler, hence no qualified resolution trail. So its pins are
ordinary project dependencies in `<dependencyManagement>`, unlike the
Kotlin/Scala demos whose compilers pin under a `@kotlin` / `@scala` qualifier.
