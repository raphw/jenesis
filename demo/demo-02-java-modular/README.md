Java (modular) demo
===================

The simplest way to build a single-module Java project that is itself a Java
module: your only build descriptor is `sources/module-info.java`, with no
`pom.xml` to write. You point Jenesis at the project and it resolves the module
you `requires`, compiles your module against it, and produces a modular jar. Its
POM-based counterpart is `../demo-01-java-pom`, and its multi-module counterpart is
`../demo-04-java-modular-multi`.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Layout
------

The whole project is a `module-info.java` plus a source file:

    demo/demo-02-java-modular
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    `-- sources/
        |-- module-info.java     module demo.modular { requires org.slf4j; exports sample; }
        `-- sample/Sample.java    uses org.slf4j.Logger

With a `module-info.java` and no `pom.xml`, Jenesis selects the MODULAR_TO_MAVEN
layout (`Project.Layout.of`; it is what `auto` resolves to for a modular
project). The build resolves `org.slf4j` as a *named module* (served from the
`https://repo.jenesis.build/module/` overlay), compiles the module against it,
and emits a modular jar plus a generated POM under `target/build/modules/...`;
`stage` then lays them out as both a module repository (`target/stage/modular`)
and a Maven repository (`target/stage/maven`).

`org.slf4j` is a genuine named Java module (`module-info` with a declared
version), which is what lets the module overlay resolve it by module name -
unlike many popular libraries that ship only as *automatic* modules.

Pure modular layout
-------------------

The layout auto-detected above is **MODULAR_TO_MAVEN**: dependencies resolve by
translating each Java module name into a Maven coordinate, and the build emits a
modular jar *plus* a generated `pom.xml` (so `stage` produces both
`target/stage/modular` and `target/stage/maven`), making the artifact consumable
from a Maven repository too.

Jenesis also ships a pure **MODULAR** layout that resolves dependencies purely by
Java module name against the Jenesis module repository and emits a modular jar with
*no* `pom.xml` at all (`stage` then produces only `target/stage/modular`). Force it
from the command line with the layout override:

    java -Djenesis.project.layout=modular build/jenesis/Project.java

(`jenesis.project.layout` accepts `auto`, `maven`, `modular`, `modular_to_maven`.)
Use MODULAR when the artifact is only ever consumed as a Java module and you do not
want a POM; keep the default MODULAR_TO_MAVEN when you also want a Maven-publishable
coordinate.

Pinned dependency
-----------------

This demo ships **already pinned**. In a module-info layout a dependency is pinned
with an `@jenesis.pin <scope>/<repository>/<coordinate> <version>` Javadoc tag on
the module declaration:

    /**
     * @jenesis.pin compile/module/org.slf4j 2.0.16
     */
    module demo.modular {
        requires org.slf4j;
        ...
    }

The version is required to resolve the module (the overlay serves
`org.slf4j/<version>/org.slf4j.jar`). Running `java build/jenesis/Project.java
pin` rewrites the resolved version back into the tag and is idempotent. The tag
may also carry a content checksum (`@jenesis.pin compile/module/org.slf4j 2.0.16
SHA-256/<hex>`), which `Download` then verifies on every fetch.

A scope-first key is always `<scope>/<repository>/<coordinate>`. An ordinary
application dependency lives in the `compile` scope and the `module` repository,
so it pins as `compile/module/org.slf4j`; runtime inherits the compile pin, so a
dependency that is both compile and runtime is pinned once under `compile/...`.
The compiler-specific scopes (`kotlin`, `scala`, `groovy`) are reserved for the
language toolchains, as shown in the `kotlin` / `scala` and
`internal-module` / `external-module` demos.

Printing the dependency tree
----------------------------

To see what the build resolves, `-Djenesis.print.dependencies=true` prints the resolved
dependency tree as the module resolves (a verbose toggle, not a build step):

    java -Djenesis.print.dependencies=true build/jenesis/Project.java

    Dependency tree:
    maven/org.slf4j/slf4j-api 2.0.16 [compile]

Because this is the default MODULAR_TO_MAVEN layout, `requires org.slf4j` is shown
as the Maven coordinate it resolves to, with a Maven scope. Under the pure MODULAR
layout the same dependency shows as a Java module name (`module/org.slf4j`) instead
- see `../demo-13-module-layout`, which contrasts the two.
