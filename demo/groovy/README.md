Groovy demo
===========

A Maven-layout project whose single module carries a `module-info.java` and mixes
a Java type with a Groovy type. It shows Jenesis driving the Groovy compiler
through the default `JavaMultiProjectAssembler`, with the module participating in
the Java module system.

Layout
------

    demo/groovy
    |-- build/jenesis              symlink to ../../../sources/build/jenesis
    |-- pom.xml                    Maven coordinates, <sourceDirectory>, the Groovy dependency
    `-- sources
        |-- module-info.java       requires org.apache.groovy; exports sample
        `-- sample
            |-- Greeter.java       a Java type in the exported package
            `-- Sample.groovy      a Groovy type that calls Greeter

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis scans the sources, resolves the Groovy compiler, compiles
`module-info.java` and `Greeter.java` with `javac`, then compiles `Sample.groovy`
with `groovyc`, and packages a modular jar.

How Groovy fits the inferred compiler chain
-------------------------------------------

The inferred chain compiles `javac` first, then `groovyc`. `javac` compiles
`module-info.java` and every `.java` source; `groovyc` then compiles only the
`.groovy` sources and resolves the Java types it references from the classes
`javac` already produced, which are on its class path.

Groovy does not take part in joint compilation here. The `groovyc` joint mode
(`-j`) runs an internal `javac` that emits class files for the Java sources,
which would collide with the leading `javac` step. Jenesis therefore hands
`groovyc` only the `.groovy` files, and Groovy code reaches Java types through the
compiled class path. This is the mirror image of how `kotlinc` and `scalac` work:
those compilers read `.java` as source for symbol resolution but never emit class
files for it, so the leading `javac` owns the Java output in every case.

Exporting a package needs a Java type
--------------------------------------

A `module-info.java` can `requires org.apache.groovy` (it resolves as the
automatic module the Groovy jar declares through its `Automatic-Module-Name`
manifest entry) and can `exports` a package. There is one rule to respect:

A package named in an `exports` directive must contain at least one Java type.

`javac` validates `exports sample` by checking that the package is populated in
its output, and it runs before `groovyc`. At that point the package's Groovy
classes do not exist yet, so only Java types count. That is why `sample` here
contains `Greeter.java`: it makes the exported package non-empty for `javac`. The
Groovy class `Sample` joins the same package when `groovyc` runs and ships in the
export alongside `Greeter`. A package that holds only Groovy classes cannot be
exported; keep it internal (declare `requires` without `exports`) or add a Java
type to it.

For Groovy this is a permanent restriction. `groovyc` resolves Java only from the
compiled class path, so it has to run after `javac`, and its classes can never be
present for the export check. The `kotlin` and `scala` demos do not share it:
because `kotlinc` and `scalac` read `.java` as source, the inferred chain compiles
them ahead of `javac`, so their classes are already in the module when `javac`
validates the exports (`javac` sees them through `--patch-module`). Both of those
demos export a package that holds only Kotlin or only Scala. `groovyc` cannot run
before `javac`, so a Groovy-only package stays unexportable.

Groovy dependency
-----------------

Because the module declares `requires org.apache.groovy`, the Groovy runtime is a
real dependency of the module and is declared in the `pom.xml` so that `javac`
finds it on the module path when it compiles `module-info.java`. The compiler
toolchain that runs `groovyc` is resolved separately on its own qualified trail
(`groovy`). Pinning the declared dependency with checksums is still pending; see
the repository notes on the pin goal.
