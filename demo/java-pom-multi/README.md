Java (multi-module POM) demo
============================

A multi-module **Maven-layout** project. A root aggregator `pom.xml` lists two
sub-modules, and one sub-module depends on the other plus a real external Maven
dependency, so the demo shows intra-project and external resolution side by
side. The `greeter` module also carries a JUnit test, so the demo doubles as a
MAVEN-layout testing example. Its single-module counterpart is `../java-pom`.

Layout
------

    demo/java-pom-multi
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- pom.xml              aggregator (packaging pom); lists the two modules
    |-- greeter/            the library module (with a JUnit test)
    |   |-- pom.xml          <sourceDirectory> + <testSourceDirectory>; a test-scoped JUnit dependency
    |   |-- sources/sample/greeter/Greeter.java
    |   `-- test/sample/greeter/GreeterTest.java
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

Tests
-----

The `greeter` module declares a test source folder and a test-scoped dependency
in its `pom.xml`:

    <build>
        <sourceDirectory>sources</sourceDirectory>
        <testSourceDirectory>test</testSourceDirectory>
    </build>
    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

A `<testSourceDirectory>` turns the module into a main artifact plus a `tests`
variant. Jenesis compiles `test/sample/greeter/GreeterTest.java` against the main
classes and the test-scoped dependencies, detects the test engine from the
resolved jars (JUnit 5 here), and runs it through the JUnit Platform console
launcher as part of the build. The launcher is added automatically; the only
reason `junit-platform-console` is pinned in `<dependencyManagement>` is to hold
it to the `1.11.x` line that matches JUnit 5.11.3 (an unpinned launcher floats to
a newer JUnit Platform that the 5.11 engine cannot satisfy).

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
the resolved version closure into each module's `<dependencyManagement>`; it is
idempotent. That closure is project-wide, so the pinned JUnit test artifacts land
in both modules' managed dependencies even though only `greeter` runs tests -
managed entries set versions, not actual dependencies, so this is the same shared
bill-of-materials Maven projects keep in a parent. Pinning records external
coordinates (`commons-lang3`, the JUnit closure) but skips the intra-project
`greeter` dependency, since coordinates produced within the project are never
pinned into dependency management.
