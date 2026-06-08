Java (multi-module modular) demo
================================

Build a multi-module project of Java modules where one module depends on another:
every build descriptor is a `module-info.java` and there is no `pom.xml` anywhere.
One module `requires` the sibling plus a real external named module, so you get
intra-project and external resolution side by side with no build script to write.
A third module is the test variant of the library, so the demo doubles as a
modular testing example. Its single-module counterpart is `../demo-02-java-modular`,
and its POM-based sibling is `../demo-03-java-pom-multi`.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Layout
------

The project is three module directories, each with its own `module-info.java`:

    demo/demo-04-java-modular-multi
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
     * @jenesis.pin org.junit.jupiter 5.11.3
     * @jenesis.pin org.junit.jupiter/junit-jupiter 5.11.3 SHA-256/...
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
exporting the same package. The JUnit closure is a test-only dependency, so it is
pinned on the plain module trail (a bare module name such as `org.junit.jupiter`
is short for `main/module/org.junit.jupiter`), and the JUnit Platform
console launcher that runs the tests is added
automatically, with its version defaulting to the one derived from the discovered
`org.junit.platform.engine` module (`1.11.3`) so it matches the JUnit Platform
line the tests compile against - though the `@jenesis.pin org.junit.platform.console`
tag overrides that default, so the pinned version always wins. Unlike the `pin` runs of the other demos, the JUnit closure is kept on
the test module alone rather than propagated project-wide, to keep `greeter` and
`app` focused on their own dependencies.

Selecting modules and filtering tests
-------------------------------------

The default build compiles, jars, and tests every module. Selectors scope a build
to part of the graph.

A `+<module>` selector builds just that module's subtree. `+greeter` builds the
`greeter` library alone - note it runs *no* tests, because in the modular layout
the tests live in a separate `@jenesis.test` module:

    java build/jenesis/Project.java +greeter

That test module is selected on its own as `+greeter-test`, which builds it and
runs the tests against `greeter`:

    java build/jenesis/Project.java +greeter-test

A module's own dependencies come along automatically: `+app` builds `app` and,
because it requires the sibling, `greeter` too - but no unrelated module.

Under the hood a selector is a slash-delimited path of graph steps, with two
wildcards: `:` matches a single path segment and `::` matches any depth. So
`::/jar` runs the `jar` step of every module wherever it sits in the tree:

    java build/jenesis/Project.java '::/jar'

Wildcards are lenient (non-matching branches are skipped), and once a module is
reached its own pipeline steps run - so a wildcard chooses *which steps* you ask
for across the tree, not how much of each matched module is rebuilt.

Test runs are narrowed with `-Djenesis.test.filter`, a comma-separated list of
`<class-regex>[#<method>]` patterns; the regex matches the fully-qualified class
name. `greeter-test` ships two tests, so this runs only one of them:

    java -Djenesis.test.filter='.*GreeterTest#prefix_is_a_greeting' build/jenesis/Project.java

The test step's summary then reports `1 tests successful` instead of the default
`2`. The `-D` flag must come **before** the source file - anything after it is
read as a selector.

Pure modular layout
-------------------

The layout auto-detected above is **MODULAR_TO_MAVEN**: every module's dependencies
(including the sibling `demo.greeter`) resolve by translating each Java module name
into a Maven coordinate, and each module emits a modular jar *plus* a generated
`pom.xml`, so the published `app` POM can declare a Maven dependency on the published
`greeter` coordinate.

Jenesis also ships a pure **MODULAR** layout that resolves dependencies purely by
Java module name against the Jenesis module repository and emits modular jars with
*no* `pom.xml` at all (`stage` then produces only `target/stage/modular`, keyed by
Java module name). Force it from the command line with the layout override:

    java -Djenesis.project.layout=modular build/jenesis/Project.java

(`jenesis.project.layout` accepts `auto`, `maven`, `modular`, `modular_to_maven`.)
Use MODULAR when the modules are only ever consumed as Java modules; keep the default
MODULAR_TO_MAVEN when you also want Maven-publishable coordinates.

Pinned dependency
-----------------

`app` pins its external `org.slf4j` dependency with an `@jenesis.pin
<group>/<repository>/<coordinate> <version> [<algorithm>/<hex>]` Javadoc tag on
the module declaration, exactly as
the single-module `../demo-02-java-modular` demo does. A token's slash count
selects a shorthand: no slash is a Java module name (`org.slf4j`, short for
`main/module/org.slf4j`), one slash is a Maven `<groupId>/<artifactId>`
(`org.slf4j/slf4j-api`, short for `main/maven/org.slf4j/slf4j-api`), and two or
more slashes spell out an explicit `<group>/<repository>/<coordinate>`. The
group is the top isolation axis (defaulting to `main` for a project's own
dependencies); compile and runtime are scopes within that group, not groups
themselves:

    /**
     * @jenesis.pin org.slf4j/slf4j-api 2.0.16 SHA-256/...
     */
    module demo.app {
        requires demo.greeter;
    }

Running `java build/jenesis/Project.java pin` records the resolved external
version closure back into the module declarations and is idempotent. The
intra-project `demo.greeter` dependency is never pinned, since coordinates
produced within the project are resolved from the build itself rather than
downloaded.

Printing the dependency tree
----------------------------

To see what each module resolves, `-Djenesis.print.dependencies=true` prints each
module's resolved tree as it resolves (a verbose toggle, not a build step), one
block per module and scope:

    java -Djenesis.print.dependencies=true build/jenesis/Project.java

    Dependency tree:
    maven/org.junit.jupiter/junit-jupiter 5.11.3 [compile]
    ├─ maven/org.junit.jupiter/junit-jupiter-api 5.11.3 [compile]
    │  └─ maven/org.junit.platform/junit-platform-commons 1.11.3 [compile]
    └─ maven/org.junit.jupiter/junit-jupiter-engine 5.11.3 [runtime]

Even though every descriptor here is a `module-info.java`, the default
MODULAR_TO_MAVEN layout resolves each `requires` through Maven, so the tree shows
Maven coordinates and their transitive Maven closure - the same shape as the
POM-based `../demo-03-java-pom-multi`. The pure MODULAR layout
(`../demo-13-module-layout`) shows the same dependencies as Java module names.
