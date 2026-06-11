Kotlin compiler plugin demo
===========================

Run a Kotlin compiler plugin over a modular Kotlin project, declared the same way
as a Java annotation processor - with `@jenesis.plugin`, but naming the Kotlin
compiler so the plugin resolves in that compiler's own `kotlinc` group.

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

The plugin is declared with the Kotlin compiler named first, then the plugin's
`<repository>/<coordinate>`:

    @jenesis.plugin kotlinc maven/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin

Naming a compiler resolves the plugin in that compiler's group (`kotlinc`) under a
`plugin` scope, version coordinated to the compiler (the `pin` step writes back a
`kotlinc/maven/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin ...`
line), and
the Kotlin compiler picks the resolved jar and hands it over as
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
