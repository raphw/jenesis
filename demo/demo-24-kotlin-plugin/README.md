Kotlin compiler plugin demo
===========================

Run a Kotlin compiler plugin over a modular Kotlin project, declared the same way
as a Java annotation processor - with `@jenesis.plugin`, but qualified for the
Kotlin compiler.

This module uses [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization):
`Point` is a `@Serializable` data class, and the serialization compiler plugin
generates its `Point.serializer()`, which `Use` then references.

Build it
--------

From this directory:

    java build/jenesis/Project.java

Jenesis auto-detects the MODULAR_TO_MAVEN layout, resolves the Kotlin compiler,
resolves the plugin, and compiles the module - emitting the generated
`Point$$serializer` class alongside `Point`.

How the plugin is wired
-----------------------

The plugin is named by Maven coordinate on the Kotlin resolution trail, with the
`@kotlin` qualifier selecting the Kotlin compiler:

    @jenesis.plugin maven@kotlin/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin

Jenesis records it under the single `plugin` scope, resolves it on the same
`@kotlin` trail as the compiler (so the plugin version is pinned to match), and
the Kotlin compiler picks the `@kotlin`-qualified plugin jar and hands it over as
`-Xplugin=<jar>`. The compiler self-loads it
through its `CompilerPluginRegistrar` service - no entry point is named. The
`requires kotlinx.serialization.core` directive provides the `@Serializable`
annotation and the runtime types the generated serializer references.

No implicit discovery
---------------------

Delete the `@jenesis.plugin` line and rebuild: the plugin is no longer passed to
the compiler, `Point.serializer()` is never generated, and the build fails to
compile `Use` (`unresolved reference 'serializer'`). The plugin runs only because
it is declared.
