ExternalModule demo
===================

The `ExternalModule` counterpart of `../internal-module`. It does exactly the
same thing - wraps the stock `JavaMultiProjectAssembler` so a build module
preprocesses the project's Java sources (a `${greeting}` substitution driven by
the `org.json` dependency) before the regular compile, jar, and test flow runs.
The only difference is how the build module is obtained: `internal-module`
compiles it from local source with `InternalModule`, while here it is resolved
as a published artifact from a repository **coordinate** with `ExternalModule`.

> **Status:** like `internal-module`, this demo currently fails at the
> `preprocess/substitute` step. The build module's `build.jenesis` dependency is
> resolved from the default Jenesis repository, whose published `build.jenesis`
> lags the local sources the host runs against, so the class-loader bridge
> rejects the version mismatch. It will work once a matching `build.jenesis` is
> released.

Layout
------

    demo/external-module
    |-- build/jenesis            symlink to ../../../sources/build/jenesis
    |-- build/Demo.java          the launcher (stage, then Project + assembler)
    |-- plugin/                  the build module (identical to internal-module)
    |   |-- .jenesis.skip       marks plugin/ as its own build root
    |   |-- module-info.java     module demo.plugin { requires build.jenesis;
    |   |                                requires org.json;
    |   |                                provides build.jenesis.BuildExecutorModule
    |   |                                with demo.plugin.SubstitutionModule; }
    |   `-- demo/plugin/SubstitutionModule.java
    `-- sources/                 the project being built (identical to internal-module)
        |-- module-info.java     module demo.internal { exports sample; }
        `-- sample/Sample.java    prints GREETING = "${greeting}"

`plugin/` and `sources/` are exact copies of the `internal-module` demo.

Run it
------

From this directory:

    java build/Demo.java

How it works
------------

Because `ExternalModule` consumes a *published* artifact rather than local
source, `main` first stages the build module to stand in for that artifact:

1. **Stage.** A nested `BuildExecutor` runs an `InternalModule` over `plugin/`,
   targeting `target/internal` - a target folder separate from this build's own
   `target/`. Selecting only the `plugin/java/artifacts` step compiles and jars
   the module *without running it*, leaving a modular jar in that folder.
2. **Wire a coordinate.** The staged jar is served under the custom coordinate
   `module/demo.plugin` by a small `Repository`, placed ahead of the default
   Jenesis repository (which resolves the module's `build.jenesis` and `org.json`
   dependencies).
3. **Resolve as external.** The custom `Project`'s `PreprocessingAssembler` wires
   that coordinate as an `ExternalModule`. `ExternalModule` writes the
   coordinate, resolves and downloads its dependency closure, loads the
   `BuildExecutorModule` service provider, and runs it - reading the project's
   sources (forwarded to it) and emitting the substituted copy that the regular
   flow then compiles, jars, and tests.

The assembler redirects the descriptor's sources to the module's output with
`descriptor.withSources("preprocess/substitute")`, exactly as in
`internal-module`. Unlike `InternalModule`, `ExternalModule` does not compile the
plugin (it is already staged), so the project's sources are simply forwarded to
the resolved module at run time.
