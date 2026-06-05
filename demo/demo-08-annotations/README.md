Annotation processor demo
=========================

Run a Java annotation processor (JSR-269) over a modular project. The processor
is declared explicitly in `module-info.java`, resolved onto its own processor
path, and handed to `javac` with `--processor-module-path` - never discovered
implicitly from the class or module path.

This module uses [Immutables](https://immutables.github.io/): `Animal` is an
abstract `@Value.Immutable` type, and the processor generates a concrete
`ImmutableAnimal` builder that `Zoo` then uses.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis auto-detects the MODULAR_TO_MAVEN layout (a `module-info.java`, no
`pom.xml`), resolves the processor, runs it, and emits a modular jar alongside a
generated POM.

How the processor is wired
--------------------------

A single Javadoc tag on the module declaration turns the processor on:

    @jenesis.annotations maven/org.immutables/value

The named coordinate is resolved on its own independent `@annotations`
resolution trail (separate from the module's own dependencies, so a processor
can pin different versions than the rest of the build), downloaded into a
dedicated set, and passed to `javac` as `--processor-module-path`. The version
is pinned the usual way - the `pin` step writes back a
`@jenesis.pin maven@annotations/org.immutables/value ...` line automatically.

No implicit discovery
---------------------

Notice that `org.immutables.value` is also a regular module dependency
(`requires static org.immutables.value`), so the exact same jar sits on the
compile `--module-path`. It still does not run as a processor on its own:
`javac` only runs processors found on the processor path, never the class or
module path. The explicit `@jenesis.annotations` declaration is what places the
jar on the processor path.

You can see this directly. Delete the `@jenesis.annotations` line from
`module-info.java` and rebuild: the jar is still on the module path through
`requires`, but the processor no longer runs, `ImmutableAnimal` is never
generated, and the build fails to compile `Zoo`. Processors are taken only from
what you declare - never picked up from a dependency that happens to bundle one.
