Java (modular) demo
===================

A purely **modular** Java project: its only build descriptor is
`sources/module-info.java` - there is no `pom.xml`. Jenesis auto-detects the
MODULAR layout, resolves the declared module dependency through the Jenesis
module repository, and emits a modular jar. Its POM-based counterpart is
`../java-pom`.

Layout
------

    demo/java-modular
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    `-- sources/
        |-- module-info.java     module demo.modular { requires org.slf4j; exports sample; }
        `-- sample/Sample.java    uses org.slf4j.Logger

With a `module-info.java` and no `pom.xml`, Jenesis selects the MODULAR layout
(`Project.Layout.of`). The build resolves `org.slf4j` as a *named module* (served
from the `https://repo.jenesis.build/module/` overlay), compiles the module
against it, and produces a modular jar under `target/build/modules/...` rather
than the `target/build/maven/...` tree of the POM-based demos.

`org.slf4j` is a genuine named Java module (`module-info` with a declared
version), which is what lets the module overlay resolve it by module name -
unlike many popular libraries that ship only as *automatic* modules.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Pinned dependency
-----------------

This demo ships **already pinned**. In the modular layout a dependency is pinned
with an `@jenesis.pin <module> <version>` Javadoc tag on the module declaration:

    /**
     * @jenesis.pin org.slf4j 2.0.16
     */
    module demo.modular {
        requires org.slf4j;
        ...
    }

The version is required to resolve the module (the overlay serves
`org.slf4j/<version>/org.slf4j.jar`). Running `java build/jenesis/Project.java
pin` rewrites the resolved version back into the tag and is idempotent. The tag
may also carry a content checksum (`@jenesis.pin org.slf4j 2.0.16
SHA-256/<hex>`), which `Download` then verifies on every fetch.

A modular dependency pins under the plain `module/` prefix (no qualifier) - the
`@<qualifier>` form is reserved for independent tool trails, as shown in the
`kotlin` / `scala` and `internal-module` / `external-module` demos.
