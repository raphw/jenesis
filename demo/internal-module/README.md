InternalModule demo
===================

This demo does the same thing as `../custom-assembler`: it wraps the stock
`JavaMultiProjectAssembler` so the project's Java sources are preprocessed (a
`${greeting}` substitution) before the regular compile, jar, and test flow runs.
The difference is *where the preprocessing lives*. Instead of an inline build
step, the substitution is performed by a **build module** (`BuildExecutorModule`
service provider) that `InternalModule` loads straight from local source - and
that build module uses an **external dependency** (`org.json`) to drive the
substitution.

The plugin's `build.jenesis` dependency resolves from the default Jenesis
repository as the published `0.3.0` artifact (pinned via the `@tool/build.jenesis`
tag in `sources/module-info.java`), whose API matches the local sources the host
runs against, so the class-loader bridge loads it without complaint.

Layout
------

    demo/internal-module
    |-- build/jenesis            symlink to ../../../sources/build/jenesis
    |-- build/Demo.java          the launcher (Project + wrapping assembler)
    |-- plugin/
    |   |-- .jenesis.skip       marks plugin/ as its own build root, so the
    |   |                        project's module discovery skips it
    |   |-- module-info.java     module demo.plugin { requires build.jenesis;
    |   |                                requires org.json;
    |   |                                provides build.jenesis.BuildExecutorModule
    |   |                                with demo.plugin.SubstitutionModule; }
    |   `-- demo/plugin/SubstitutionModule.java
    `-- sources/
        |-- module-info.java     module demo.internal { exports sample; }
        `-- sample/Sample.java    prints GREETING = "${greeting}"

The project under `sources/` is an ordinary modular project, exactly like
`custom-assembler`. The build module under `plugin/` is a separate modular
project; its `.jenesis.skip` marker keeps the host project's module discovery
from mistaking it for a second project module.

Run it
------

From this directory:

    java build/Demo.java

which builds the project and then launches the built module, printing the
greeting the plugin substituted in:

    Hello from a source preprocessed by an internal build module, using the org.json dependency!

Built without the plugin the literal `${greeting}` would print instead.

How it works
------------

`Demo.java` builds a `Project` whose assembler is a `PreprocessingAssembler`
wrapping the stock `JavaMultiProjectAssembler`, then hands that project to
`Execute`, which builds it and launches the produced module's `main` so the
substituted greeting is shown. `Execute` reads the build's inventory to find the
module and its runtime classpath, so nothing is located by hand. For each module
the wrapper:

1. Adds a `preprocess` node that is an `InternalModule` pointed at `plugin/`.
   `InternalModule` compiles the plugin from source, resolves its declared
   dependencies, loads the `BuildExecutorModule` service provider, and runs it.
   The three-argument constructor wires the **default Jenesis repository** with
   the local export (`~/.jenesis`) prepended, so the plugin's `build.jenesis` and
   `org.json` dependencies resolve from there - nothing is downloaded explicitly.
2. Redirects the descriptor's sources to the module's output with
   `descriptor.withSources("preprocess/substitute")`, so the stock assembler's
   regular flow compiles, jars, and tests the preprocessed sources.

The project's sources are passed to the module as inherited steps.
`InternalModule` compiles the plugin in isolation - against only its own sources
and resolved dependencies - and forwards the inherited steps to the plugin only
at run time, where the `substitute` step reads and rewrites them. The substituted
copy the plugin emits then stands in for the originals downstream.

Inside the plugin, `SubstitutionModule` parses a small substitution map with
`org.json` (the external dependency) and replaces each `${key}` placeholder in
every `.java` file. Built with the stock assembler the `${greeting}` literal
would survive verbatim; here it is rewritten before compilation, so the value
that ends up in the jar is the substituted one.

The plugin's `substitute` step is reachable in the build graph as
`preprocess/substitute` (the delegated module's steps surface directly under the
module name), which is exactly the key the assembler redirects the sources to.
