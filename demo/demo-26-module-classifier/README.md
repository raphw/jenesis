Module classifier demo
======================

Selects a **classified variant** of a module dependency. Some artifacts publish
several jars under one Maven coordinate, distinguished by a *classifier*; in the
module world that means: the same module name, different bytes. This demo pins
such a variant explicitly and validates, on any operating system, that the
classified artifact - not the default jar - is what the build resolves.

Build and run it
----------------

From this directory:

    java build/jenesis/Execute.java

This builds the module and launches its entry point, which asserts at runtime
that the classified variant is on the module path.

The classifier pin
------------------

A classifier is selected through the pin value alone, with a leading-colon
qualifier `:<classifier>[:<version>]`:

    /**
     * @jenesis.pin mutiny.zero :jdk-flow:0.4.3 SHA-256/0556f076191921250e5c9e21b9674d252bf2c4c515491e087fec93f383292b17
     */
    module demo.classifier {
        requires mutiny.zero;
    }

The pin stays keyed by the bare module name - the classifier is part of the
value, never the coordinate - so it applies wherever the module appears in the
closure, directly or transitively, and only one variant of a module name can be
selected, mirroring the module path's own uniqueness rule. The leading colon
cannot collide with a real version: a legal Maven version never contains `:`,
and a parseable `module-info.class` version must start with a digit. Re-pinning
(`java build/jenesis/Project.java pin`) round-trips the qualifier.

The module repository serves the variant under the fused filename
`mutiny.zero/0.4.3/mutiny.zero-jdk-flow.jar` (a dash cannot occur in a Java
module name, so the suffix is unambiguous), redirecting to the Maven Central
artifact `mutiny-zero-0.4.3-jdk-flow.jar`.

The pure MODULAR layout is selected through `jenesis.properties`
(`jenesis.project.layout=modular`), as in `../demo-25-module-layout`: classified
variants resolve by Java module name, while the default MODULAR_TO_MAVEN layout
translates modules into Maven coordinates and rejects classifier pins (a
classified artifact shares its coordinate's POM, so there is no per-classifier
POM to translate through).

Why `mutiny.zero` and `jdk-flow`
--------------------------------

Classifiers usually distinguish OS-specific natives, which would make a demo
machine-dependent. `mutiny-zero` 0.4.3 instead published a *semantic* variant:
the default jar exposes `org.reactivestreams` types (and carries
`requires transitive org.reactivestreams`), while the `jdk-flow` classifier
exposes `java.util.concurrent.Flow` types and requires nothing but `java.base`.
The right variant is therefore observable anywhere, with no native code and a
37 KB download.

How selection is validated
--------------------------

Three independent checks, each failing the build or the run if the default jar
were resolved instead:

1. **Checksum.** The pin carries the SHA-256 of the classified jar; the
   `Dependencies` step verifies every fetch against it, so the wrong bytes fail
   the build.
2. **Compilation.** `sample/Sample.java` calls `ZeroPublisher.fromItems(...)`
   and `toCompletionStage(...)` with `java.util.concurrent.Flow.Publisher`
   types, which only exist in the `jdk-flow` variant's API.
3. **Runtime.** The entry point reads `mutiny.zero`'s resolved
   `ModuleDescriptor` and fails if it requires `org.reactivestreams`, then
   publishes a value through a `Flow.Publisher` and prints the requires set:

       Published through java.util.concurrent.Flow: the jdk-flow variant of mutiny.zero
       Requires of mutiny.zero: [mandated java.base]
