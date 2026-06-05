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

    @jenesis.plugin org.immutables.value

The processor is named the same way `requires` names a dependency - by module
name. The tag is generic: a leading qualifier routes the plugin to a specific
compiler (`@jenesis.plugin @kotlin/...` for a Kotlin compiler plugin); an
unqualified token routes to the Java compiler. Jenesis records it under the
single `plugin` scope in the module's `scopes.properties`, resolves it alongside
the module's other dependencies, and the Java compiler picks the unqualified
plugin jars and passes them to `javac` as `--processor-module-path`.
The version is pinned the usual way - the `pin` step writes back a
`@jenesis.pin org.immutables.value ...` line automatically.

No implicit discovery
---------------------

Notice that `org.immutables.value` is also a regular module dependency
(`requires static org.immutables.value`), so the exact same jar sits on the
compile `--module-path`. It still does not run as a processor on its own:
`javac` only runs processors found on the processor path, never the class or
module path. The explicit `@jenesis.plugin` declaration is what places the
jar on the processor path.

You can see this directly. Delete the `@jenesis.plugin` line from
`module-info.java` and rebuild: the jar is still on the module path through
`requires`, but the processor no longer runs, `ImmutableAnimal` is never
generated, and the build fails to compile `Zoo`. Processors are taken only from
what you declare - never picked up from a dependency that happens to bundle one.
