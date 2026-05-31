Java (multi-module POM) demo
============================

A multi-module **Maven-layout** project. A root aggregator `pom.xml` lists two
sub-modules, and one sub-module depends on the other plus a real external Maven
dependency, so the demo shows intra-project and external resolution side by
side. Its single-module counterpart is `../java-pom`.

Layout
------

    demo/java-pom-multi
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              aggregator (packaging pom); lists the two modules
    |-- greeter/            the library module
    |   |-- pom.xml
    |   `-- sources/sample/greeter/Greeter.java
    `-- app/                the consumer module
        |-- pom.xml          depends on greeter + commons-lang3
        `-- sources/sample/app/App.java   uses Greeter + StringUtils

The root `pom.xml` carries `<packaging>pom</packaging>`, so Jenesis treats it as
an aggregator and never builds it as a jar; its presence is what selects the
MAVEN layout (`Project.Layout.of`). Jenesis then walks the tree, discovers every
`pom.xml`, and builds one module per descriptor.

The `app` module declares two dependencies: `build.jenesis.demo:greeter` (the
sibling library) and `org.apache.commons:commons-lang3` (an external artifact).
Jenesis builds `greeter` first, exposes its output through the sibling's `assign`
step, and prepends that as a repository when resolving `app`, so the sibling
coordinate resolves from within the build while `commons-lang3` is fetched from
Maven Central.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Pinned dependencies
-------------------

This demo ships **already pinned**. External dependencies are pinned in
`<dependencyManagement>` with a content checksum:

    <dependency>
        <groupId>org.apache.commons</groupId>
        <artifactId>commons-lang3</artifactId>
        <version>3.14.0</version>
        <!--Checksum/SHA-256/...-->
    </dependency>

Running `java build/jenesis/Project.java pin` walks the build outputs and writes
the resolved version closure into each module's `pom.xml`; it is idempotent.
Pinning records the external `commons-lang3` coordinate but skips the intra-
project `greeter` dependency, since coordinates produced within the project are
never pinned into dependency management.
