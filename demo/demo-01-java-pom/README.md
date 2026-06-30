Java (POM-based) demo
=====================

The simplest way to build a Maven-style Java project with Jenesis: a `pom.xml`
and your sources. You point Jenesis at the project and it resolves the declared
dependency, compiles against it, and produces a jar - there is no build script to
write. It also has a modular counterpart that builds the same kind of project
from a `module-info.java` instead of a `pom.xml`.

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
`javac` through the default `InferredMultiProjectAssembler`.

Printing the dependency graph
-----------------------------

To see what the build resolves, run the `dependencies` selector. It prints each
module's resolved dependency graph, annotated with the resolved module name and
the declared license:

    java build/jenesis/Project.java dependencies

    main/compile (module)
    maven/org.apache.commons/commons-lang3 3.14.0 [compile] (module org.apache.commons.lang3) {Apache-2.0}

Each node shows the property-file key, the requested version (with the negotiated
version inline when it differs), the Maven scope, the resolved module name, and
the declared license.

Opening it in your IDE
----------------------

If you want to run or debug this project from an IDE, the `ide` selector generates
the project metadata for you (run a sub-step like `ide/idea` for just one editor):

    java build/jenesis/Project.java ide

It writes IntelliJ IDEA, VS Code, and Eclipse files at the project root from the
resolved sources and dependencies - see *Generating IDE metadata* in the root
README for details.

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
*resolved* compiler, hence no separate compiler scope. So its pins are
ordinary project dependencies in `<dependencyManagement>`, unlike the
Kotlin/Scala demos whose compilers pin under the `kotlin` / `scala` scope.
