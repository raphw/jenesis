ExternalModule demo
===================

Drive a source preprocessing pass from a build module that ships as a published
artifact, resolved from a repository coordinate rather than compiled from local
source. The plugin performs the same `${greeting}` substitution over the
project's Java sources (driven by the `org.json` dependency) before the regular
compile, jar, and test flow runs. This is the published-artifact counterpart of
`../demo-32-internal-module`: same plugin, same outcome, only the plugin is
obtained as a coordinate with `ExternalModule` instead of from source with
`InternalModule`.

Run it
------

From this directory:

    java build/Demo.java

which builds the project and then launches the built module, printing the
greeting the plugin substituted in:

    Hello from a source preprocessed by an internal build module, using the org.json dependency!

Built without the plugin the literal `${greeting}` would print instead.

Layout
------

    demo/demo-33-external-module
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

Like `internal-module`, the build module's `build.jenesis` dependency resolves
from the default Jenesis repository as the published `0.6.0` artifact (pinned via
the `@jenesis.pin build.jenesis 0.6.0 ...` tag in
`sources/module-info.java`), whose API matches
the local sources the host runs against, so the class-loader bridge loads it
without complaint.

How it works
------------

Because `ExternalModule` consumes a *published* artifact rather than local
source, `main` first stages the build module to stand in for that artifact:

1. **Stage.** A `Project` rooted at `plugin/` builds the module at a fixed
   version into `target/plugin` - a target folder separate from this build's own
   `target/`. The jar is read straight from the build's structured result:
   `stage/modular` lays it out in the Jenesis module repository's
   `<module>/<version>/<module>.jar` shape, so the path is fully determined and
   nothing is located by scanning the filesystem.
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
`descriptor.sources("preprocess/substitute")`, exactly as in
`internal-module`. Unlike `InternalModule`, `ExternalModule` does not compile the
plugin (it is already staged), so the project's sources are simply forwarded to
the resolved module at run time.

Isolating the build module's Jenesis
------------------------------------

Just as in `../demo-32-internal-module`, the resolved build module is loaded into
its **own `ModuleLayer` with its own class loader** carrying its own
`build.jenesis`, bridged to the host across class loaders so the two Jenesis
copies never clash and the module may pin a different version. The build module
itself must be a named (explicit) module, since it is discovered in that layer as
a `BuildExecutorModule` service provider; its *dependencies* are not so
restricted - a module layer admits automatic modules too, so dependencies
resolved by Maven coordinate (rather than by module name, as this demo does) can
include automatic or otherwise non-modular jars. A module built against a *newer*
Jenesis that calls a `BuildExecutor` method this version lacks fails with an
`UnsupportedOperationException` advising you to upgrade Jenesis. See that demo's
"Isolating the build module's Jenesis" section for the full explanation.

Pinning
-------

Because this launcher stages the build module and serves it through a repository
and resolver of its own, the plugin's dependency closure is resolved on a trail
the standard `pin` step never walks, so its Maven-coordinate checksums are not
recorded and the build cannot run under strict pinning
(`-Djenesis.dependency.pin=strict`); a project built entirely through the regular
modular flow, by contrast, pins and verifies strictly as usual.

The `demo.plugin` coordinate is pinned by version only
(`@jenesis.pin tool/module/demo.plugin 1`), without a checksum: `main` stages it
by building `plugin/` against an unpinned `requires build.jenesis`, which resolves
to the latest published release, and `javac` records that resolved version into
the plugin's `module-info.class`. The staged jar's bytes therefore change whenever
`build.jenesis` is released, even on the same JVM (the `jar` tool's `Created-By`
manifest entry is a further, JVM-specific source of drift), so a published checksum
would not survive the next release. The staged jar reproduces byte-for-byte only
for a fixed JVM and a fixed resolved `build.jenesis`.
