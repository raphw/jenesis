Java (POM-based) demo
=====================

A minimal **Maven-layout** project: a `pom.xml` plus a single Java source with
one real Maven dependency. It shows Jenesis driving plain `javac` through the
default `JavaMultiProjectAssembler`, resolving and downloading the declared
dependency, and pinning it. Its modular counterpart is `../demo-02-java-modular`.

Layout
------

    demo/demo-01-java-pom
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              Maven coordinates, <sourceDirectory>, one <dependency>; ships pinned
    `-- sources/sample/Sample.java

The presence of `pom.xml` makes Jenesis auto-detect the MAVEN layout.
`Sample.java` uses `org.apache.commons.lang3.StringUtils`, so the build resolves
a real dependency from Maven Central (or `~/.m2`).

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis resolves `commons-lang3`, downloads it, and compiles `Sample.java`
against it.

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
