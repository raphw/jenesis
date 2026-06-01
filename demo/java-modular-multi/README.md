Java (multi-module modular) demo
================================

A multi-module **modular** project: every build descriptor is a
`module-info.java` and there is no `pom.xml` anywhere. One module depends on the
other plus a real external named module, so the demo shows intra-project and
external resolution side by side. A third module is the test variant of the
library, so the demo doubles as a MODULAR_TO_MAVEN testing example. Its
single-module counterpart is `../java-modular`, and its POM-based sibling is
`../java-pom-multi`.

Layout
------

    demo/java-modular-multi
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- greeter/             the library module
    |   |-- module-info.java     module demo.greeter { exports sample.greeter; }
    |   `-- sample/greeter/Greeter.java
    |-- greeter-test/        the test variant of demo.greeter
    |   |-- module-info.java     open module demo.greeter.test (@jenesis.test demo.greeter)
    |   `-- greetertest/GreeterTest.java
    `-- app/                 the consumer module
        |-- module-info.java     requires demo.greeter + org.slf4j
        `-- sample/app/App.java   uses Greeter + org.slf4j.Logger

With `module-info.java` files and no `pom.xml`, Jenesis selects the
MODULAR_TO_MAVEN layout (`Project.Layout.of`; it is what `auto` resolves to for a
modular project). Jenesis walks the tree, discovers every `module-info.java`, and
builds one module per descriptor in dependency order.

The `app` module requires two modules: `demo.greeter` (the sibling library built
in this same project) and `org.slf4j` (an external named module). Jenesis builds
`greeter` first, exposes its produced jar and generated POM through the sibling's
`assign` step, and prepends that as a repository when resolving `app`, so the
sibling module resolves from within the build while `org.slf4j` is fetched from
the Jenesis module overlay (`https://repo.jenesis.build/module/`).

MODULAR_TO_MAVEN emits a modular jar and a generated `pom.xml` for each module.
`java build/jenesis/Project.java stage` lays the artifacts out as both a module
repository (`target/stage/modular/output`, keyed by Java module name) and a Maven
repository (`target/stage/maven/output`, keyed by the generated coordinate), and
`export` publishes each. The sibling resolves as the Maven coordinate read from
`greeter`'s generated POM, so the published `app` POM declares a dependency on
the published `greeter` artifact.

Tests
-----

The `greeter-test/` directory is a separate module marked as the test variant of
`demo.greeter`:

    /**
     * @jenesis.test demo.greeter
     * @jenesis.pin org.junit.jupiter 5.11.3 SHA-256/...
     * ... (the rest of the JUnit closure)
     */
    open module demo.greeter.test {
        requires demo.greeter;
        requires org.junit.jupiter;
    }

The `@jenesis.test demo.greeter` tag marks the module as a test variant, so it is
compiled and run but never staged as a published artifact. It is `open` so JUnit
can reflect over the test classes, and its test lives in its own package
(`greetertest`) rather than `sample.greeter`: a test module cannot share a package
with the module it tests, since the Java module system forbids two modules from
exporting the same package. The JUnit closure is pinned on the plain `module`
trail, and the JUnit Platform console launcher that runs the tests is added
automatically, with its version defaulting to the one derived from the discovered
`org.junit.platform.engine` module (`1.11.3`) so it matches the JUnit Platform
line the tests compile against - though the `@jenesis.pin org.junit.platform.console`
tag overrides that default, so the pinned version always wins. Unlike the `pin` runs of the other demos, the JUnit closure is kept on
the test module alone rather than propagated project-wide, to keep `greeter` and
`app` focused on their own dependencies.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Pinned dependency
-----------------

`app` pins its external `org.slf4j` dependency with an `@jenesis.pin <module>
<version> [<algorithm>/<hex>]` Javadoc tag on the module declaration, exactly as
the single-module `../java-modular` demo does:

    /**
     * @jenesis.pin org.slf4j 2.0.16 SHA-256/...
     */
    module demo.app {
        requires demo.greeter;
        requires org.slf4j;
    }

Running `java build/jenesis/Project.java pin` records the resolved external
version closure back into the module declarations and is idempotent. The
intra-project `demo.greeter` dependency is never pinned, since coordinates
produced within the project are resolved from the build itself rather than
downloaded.
