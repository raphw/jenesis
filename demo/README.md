Jenesis demos - a guided tour
=============================

These demos are meant to be read in order. Each one is self-contained and adds
one idea on top of the last, so the sequence doubles as a tutorial through
Jenesis' features: start with a single Maven project, turn it into a module,
scale to many modules, package a module into a runnable application image, bring
in other JVM languages, and finally customize or replace the build template
itself.

Every demo has its own `build/jenesis` symlink into this repository's
`sources/build/jenesis`, so each runs in isolation from inside its own directory
with no installation step. There is no wrapper script, no fetched plugin tree,
and no daemon - the build is just Java source you launch with the JDK.

How to run a demo
-----------------

Most demos are driven by the shipped entry point:

    java build/jenesis/Project.java

That runs the default `build` goal. You pass other goals as arguments:

    java build/jenesis/Project.java pin      # rewrite the source to pin resolved versions
    java build/jenesis/Project.java stage    # lay the artifacts out as local repositories
    java build/jenesis/Project.java export   # publish them into the local repositories
    java build/jenesis/Project.java help     # usage; `skill` prints an agent-oriented briefing

The six demos that customize or replace the template (`custom-assembler`,
`internal-module`, `external-module`, `custom-maven`, `custom-modular`,
`custom-build`) ship their own launcher and are run with `java build/Demo.java`
instead. The two executable demos (`java-pom-executable`, `java-modular-executable`)
likewise ship a launcher, `java build/Run.java`, which stages a `jpackage` image and
then runs it with the arguments you pass. Each demo writes to a local `target/`
directory; delete it to rebuild from scratch.

Quick index
-----------

| Demo                                                 | Shows                                                                 | Run from the demo dir              |
| ---------------------------------------------------- | -------------------------------------------------------------------- | ---------------------------------- |
| [`java-pom`](java-pom/README.md)                     | A single Maven (`pom.xml`) project: `javac` + one real dependency, pinned | `java build/jenesis/Project.java`  |
| [`java-modular`](java-modular/README.md)             | The same project as a Java module (`module-info.java`, no `pom.xml`)  | `java build/jenesis/Project.java`  |
| [`java-pom-multi`](java-pom-multi/README.md)         | Many Maven modules: a library + a consumer that depends on it and an external artifact | `java build/jenesis/Project.java`  |
| [`java-modular-multi`](java-modular-multi/README.md) | The multi-module project as Java modules                              | `java build/jenesis/Project.java`  |
| [`java-pom-executable`](java-pom-executable/README.md)       | A runnable Maven project: a `<mainClass>` entry point + dependency, packaged into a native app image with `jpackage` | `java build/Run.java`              |
| [`java-modular-executable`](java-modular-executable/README.md) | The same as a Java module: entry point via `@jenesis.main` + dependency, packaged with `jpackage`        | `java build/Run.java`              |
| [`kotlin`](kotlin/README.md)                         | Java + Kotlin in one module; exports a pure-Kotlin package            | `java build/jenesis/Project.java`  |
| [`scala`](scala/README.md)                           | Java + Scala 3 in one module; exports a pure-Scala package            | `java build/jenesis/Project.java`  |
| [`groovy`](groovy/README.md)                         | Java + Groovy in one module; why a Groovy-only package cannot be exported | `java build/jenesis/Project.java`  |
| [`custom-assembler`](custom-assembler/README.md)     | Wrap the assembler to preprocess sources before the regular flow      | `java build/Demo.java`             |
| [`custom-jmod`](custom-jmod/README.md)               | Wrap the assembler to pack extra content into a `.jmod` and `jlink` it into a runtime image | `java build/Demo.java`             |
| [`internal-module`](internal-module/README.md)       | Move that preprocessing into a build module loaded from local source  | `java build/Demo.java`             |
| [`external-module`](external-module/README.md)       | Resolve the same build module as a published coordinate               | `java build/Demo.java`             |
| [`custom-maven`](custom-maven/README.md)             | Drive a multi-module Maven build via `MavenProject.make(root, assembler)`, no `Project` | `java build/Demo.java`             |
| [`custom-modular`](custom-modular/README.md)         | The same via `ModularProject.make(root, assembler)` for modules       | `java build/Demo.java`             |
| [`custom-build`](custom-build/README.md)             | No `Project` at all: wire a `BuildExecutor` by hand                   | `java build/Demo.java`             |

1. A single Maven project - `java-pom`
---------------------------------------

Start here. `java-pom` is the smallest real build: a `pom.xml` and one Java
source that uses `org.apache.commons.lang3.StringUtils`. The presence of a
`pom.xml` makes Jenesis auto-detect the **MAVEN** layout. Running it resolves
`commons-lang3` from Maven Central (or your `~/.m2`), downloads it, and compiles
against it.

This demo introduces the two ideas every later one builds on:

- **Layout auto-detection.** You declare *what* the project is (here, a Maven
  project) and Jenesis picks the build shape. Nothing about the toolchain is
  configured by hand.
- **Pinning.** `java build/jenesis/Project.java pin` records each resolved
  dependency, with its content `SHA-256`, into the POM's `<dependencyManagement>`
  block. Subsequent builds verify every download against that checksum, so the
  build is reproducible and resistant to supply-chain tampering. This demo ships
  already pinned.

See [`java-pom/README.md`](java-pom/README.md) for the pinned POM in full.

2. The same project as a module - `java-modular`
------------------------------------------------

`java-modular` is the modular counterpart of `java-pom`: the only descriptor is
`sources/module-info.java`, and there is no `pom.xml`. With a `module-info.java`
and no POM, Jenesis selects the **MODULAR_TO_MAVEN** layout (also what `auto`
resolves to for a modular project), compiles the module, and emits *both* a
modular jar and a generated `pom.xml`.

What is new here:

- **The module declaration is the build descriptor.** `requires org.slf4j`
  drives resolution; `org.slf4j` is fetched as a *named module* from the Jenesis
  module overlay (`https://repo.jenesis.build/module/`). `org.slf4j` works
  because it is a genuine named module; many libraries ship only as *automatic*
  modules and cannot be required by module name.
- **Pinning in source, the modular way.** Instead of `<dependencyManagement>`,
  a dependency is pinned with an `@jenesis.pin org.slf4j 2.0.16 [SHA-256/...]`
  Javadoc tag on the module declaration.
- **Staging.** `stage` lays the output out as both a module repository and a
  Maven repository, and `export` publishes them locally.

Compare `java-pom` and `java-modular` side by side: the same one-class project,
expressed two ways, and Jenesis adapts the whole pipeline to the descriptor it
finds.

3. Many modules at once - `java-pom-multi` and `java-modular-multi`
-------------------------------------------------------------------

Real projects have more than one module. `java-pom-multi` is a Maven aggregator
(`<packaging>pom</packaging>`) over two sub-modules: a `greeter` library and an
`app` that depends on it *and* on external `commons-lang3`. Jenesis walks the
tree, discovers every `pom.xml`, builds `greeter` first, and resolves the sibling
coordinate from within the build while the external artifact is fetched from
Maven Central. The same build thus resolves an intra-project sibling and an
external dependency side by side.

`java-modular-multi` is the modular twin: two `module-info.java` modules, no POM
anywhere, where `app` `requires demo.greeter` (the sibling) and `org.slf4j` (an
external named module). It builds in dependency order, and the published `app`
POM declares a dependency on the published `greeter` coordinate.

The new idea is **multi-project discovery and intra-project resolution**: you
point Jenesis at the root, it finds the module graph, orders it, and lets modules
depend on one another with no manual wiring. Pinning records the *external*
dependency only; an intra-project sibling has no stable downloaded version to pin
against.

Both demos also **demonstrate testing**. In `java-pom-multi`, the `greeter`
module adds a `<testSourceDirectory>` and a test-scoped JUnit dependency, which
turns it into a main artifact plus a `tests` variant. In `java-modular-multi`, a
separate `greeter-test` module is marked `@jenesis.test demo.greeter`. Either
way Jenesis detects the test engine from the resolved jars (JUnit 5), wires the
JUnit Platform console runner automatically, and runs the tests as part of the
build. Each demo's `README.md` covers the test wiring in full.

4. Packaging a runnable application - `java-pom-executable`, `java-modular-executable`
--------------------------------------------------------------------------------------

A module that declares an entry point can be packaged into a **native, self-contained
application image** by the JDK's `jpackage`. These two demos are the runnable
counterparts of `java-pom` and `java-modular`: each adds a `main` method and one real
dependency, and ships a `build/Run.java` launcher that stages the image and runs it.

The entry point is declared the same way the launcher already reads it: an
`@jenesis.main sample.Sample` Javadoc tag on `module-info.java` (modular), or a
`<mainClass>` property in the POM (Maven). Both record `main=sample.Sample` in the
module's `module.properties`, and that single field is what marks the module as
packageable - modules without it are skipped.

Packaging is opt-in through one property, `-Djenesis.java.package` (its value is the
`jpackage --type`; a bare flag means `app-image`). When set, the assembler wires a
per-module `package` step that runs `jpackage` over the produced jar plus its runtime
dependency jars, so the image bundles the whole closure - `commons-lang3` in the Maven
demo, `slf4j-api` in the modular one. Each image is then collected by the `STAGE`
module's `packages` step into `stage/packages/`, the staging analogue of `stage/maven`
and `stage/modular`.

Rather than set that property on the command line, `build/Run.java` configures it
**explicitly on the assembler** - `new JavaMultiProjectAssembler(false, null, "app-image")`,
no `System.setProperty` - then builds the fixed `stage` target and launches the produced
image, forwarding its own arguments to the packaged app's `main`:

    java build/Run.java ada lovelace

The new idea is that **the build produces a runnable artifact, not just a jar**, and
that one entry-point declaration (`@jenesis.main` / `<mainClass>`) drives both launching
and packaging through the same `module.properties` field.

5. Other JVM languages - `kotlin`, `scala`, `groovy`
----------------------------------------------------

Jenesis drives non-Java compilers through the same inferred compiler chain, with
no language-specific configuration beyond the sources. A *resolved* compiler
(Kotlin, Scala, Groovy) is pinned on its own **qualified trail** - keyed
`@kotlin/...`, `@scala/...`, `@groovy/...` - so the compiler's own closure never
collides with the project's dependencies.

- `kotlin` and `scala` are MODULAR_TO_MAVEN modules (a `module-info.java`, no
  `pom.xml`) that each mix a `.java` source with their language source. The chain
  compiles Kotlin/Scala *before* `javac` (their compilers read `.java` as source
  for resolution but emit only their own classes), and `javac` then sees those
  classes through `--patch-module`. That ordering lets the module export a
  package that holds *only* Kotlin or *only* Scala - each demo exports such a
  package.
- `groovy` is also a MODULAR_TO_MAVEN module (a `module-info.java`, no `pom.xml`).
  `groovyc` resolves Java only from the compiled class path, so it must run
  *after* `javac` and cannot populate a package before the export is validated.
  An exported package therefore needs at least one Java type; the demo's
  `README.md` explains why this is permanent for Groovy but not for Kotlin/Scala.

These three are quick reads; each `README.md` has the detail. (Each pins both its
library dependency and its compiler toolchain on a qualified trail; see "Pinning"
below.)

6. Customizing the build - `custom-assembler`
---------------------------------------------

The remaining demos open up the template. `custom-assembler` keeps the standard
MODULAR_TO_MAVEN flow but **wraps** the stock `JavaMultiProjectAssembler` so each
module's sources pass through a preprocessing step (a `${greeting}` substitution)
before compile, jar, and test run unchanged:

    new Project()
            .assembler(new PreprocessingAssembler(new JavaMultiProjectAssembler()))

The trick is small and reusable: the wrapper adds a `preprocess` step that
rewrites the sources, then redirects the descriptor at it with
`descriptor.withSources("preprocess")` and hands the unchanged stock assembler
the redirected descriptor. The Java toolchain is never reimplemented - a source
transformation is simply interposed in front of it. Any step that produces a
`sources/` tree (template expansion, code generation, license headers) fits the
same shape. This demo is launched with `java build/Demo.java`.

`custom-jmod` is a sibling example of the same wrapping technique, but instead of
transforming sources it *appends* steps: a `config` step that produces extra
content, a `jmod` step that packs the module's classes plus that `config/` into a
`.jmod` (the `JMod` step routes `config/`/`libs/`/`cmds/` to `jmod --config`/`--libs`/`--cmds`),
and a `jlink` step that links the `.jmod` into a runtime image. Because the config
rides in the jmod's config section, `jlink` lays it into the runtime's `conf/` -
content a jar cannot carry into a runtime. Also `java build/Demo.java`.

7. Preprocessing in a reusable build module - `internal-module`, `external-module`
----------------------------------------------------------------------------------

`internal-module` does the same preprocessing as `custom-assembler`, but moves it
out of an inline step and into a **build module** - a `BuildExecutorModule`
service provider in its own `plugin/` project that even pulls its own external
dependency (`org.json`). `InternalModule` compiles that plugin from local source,
resolves its dependencies, loads the service, and runs it. A `.jenesis.skip`
marker in `plugin/` keeps the host project's module discovery from mistaking it
for a second project module.

`external-module` is identical except for *where the build module comes from*:
instead of compiling it from source, it stages the plugin to a jar and resolves
it as a published repository **coordinate** through `ExternalModule`. The
plugin's closure rides its own `"tool"` qualifier trail.

Together these show that build logic itself is just another module - it can be
authored inline, loaded from source, or consumed as a versioned artifact.

> Status: both `internal-module` and `external-module` currently fail at the
> build-module load step, because the plugin's `build.jenesis` dependency
> resolves to a published version that lags the local sources the host runs
> against. They will work once a matching `build.jenesis` is released; see their
> `README.md`s.

8. Driving the build without `Project` - `custom-maven`, `custom-modular`
-------------------------------------------------------------------------

The previous customizations still went through `Project`. These two go a step
further: they drive a multi-module build directly from a hand-written
`build/Demo.java` that calls the convenience `make(root, assembler)` overload and
runs it on a `BuildExecutor`. There is no layout and no goal machinery, but the
whole standard toolchain is reused through one call:

    BuildExecutor root = BuildExecutor.of(Path.of("target"));
    root.addModule("maven", MavenProject.make(Path.of("."), assembler));
    root.execute(args);

`custom-maven` does this for a multi-module Maven project (the same shape as
`java-pom-multi`); `custom-modular` does it for `module-info.java` modules (like
`java-modular-multi`). The two-argument `make` discovers the modules under the
root and supplies sane defaults for the repositories, resolvers, and digest - the
values a normal build would configure - so a quick trial build needs nothing but
a root and an assembler. The assembler is the stock `JavaMultiProjectAssembler`,
adapted with a one-line wrapper so each discovered descriptor becomes a
`ProjectModuleDescriptor`. Reach for the full `make(...)` overload (the one
`Project` itself uses) when you need a custom repository, strict pinning, or a
specific digest.

9. Dropping the template entirely - `custom-build`
--------------------------------------------------

The last demo removes `Project`, the layout, and the assembler altogether and
wires a `BuildExecutor` **by hand** in one `main` method. There is no `pom.xml`
or `module-info.java` - just the steps you add. It includes a `generate` step
that synthesizes a Java source on the fly, which the compiler then picks up next
to the hand-written sources:

    sources ----\
                 +--> compile --> jar
    generate ---/

This is the escape hatch: when a build needs something the templates do not model
(code generation, a bespoke packaging step, an unusual dependency wiring), you
step down to the `BuildExecutor` primitives and build exactly the graph you want.
Run it with `java build/Demo.java`, then `java -cp target/jar/output/artifacts/classes.jar
sample.Sample`.

Cross-cutting concepts
----------------------

A few ideas recur across the tour and are worth collecting in one place.

**Layouts.** The descriptor at the project root selects the build shape, with
`AUTO` (the default) detecting it: a `pom.xml` selects `MAVEN` (classic jar plus
the POM); otherwise a `module-info.java` under the root selects
`MODULAR_TO_MAVEN` (a modular jar plus a generated POM). A subtree rooted at a
`.jenesis.skip` marker is skipped during discovery, which is how the
`internal-module` / `external-module` plugins live beside the project without
being built as part of it.

**Goals.** `build` (default) compiles, jars, and tests; `pin` rewrites the source
descriptors to record resolved versions and checksums; `stage` lays artifacts out
as local Maven and module repositories; `export` publishes them. A module that
declares an entry point (`@jenesis.main` in `module-info.java`, or `<mainClass>`
in `pom.xml`) can also be launched from its built artifacts, or packaged into a
native application image with `-Djenesis.java.package` (the value is the `jpackage
--type`); the image is collected under `stage/packages/` next to `stage/maven` and
`stage/modular`. See demo 4.

**Pinning, checksums, and qualifiers.** Pins live in source: a POM's
`<dependencyManagement>` (with `<!--Checksum/...-->` comments) or a
`module-info.java`'s `@jenesis.pin <module> <version> [<algorithm>/<hex>]`
Javadoc tags. `Download` verifies every fetch against a pinned checksum, and
strict pinning (`-Djenesis.project.strictPinning=true`) fails the build on any
unpinned coordinate. A *resolved compiler* pins on an independent qualified trail
(`@kotlin`, `@scala`, `@groovy`, or an explicit `"tool"` qualifier) so it never
mixes with the project's own dependencies.

Current pin state of the demos: every demo is committed pinned with checksums.
`java-pom`, `java-pom-multi`, `java-modular`, and `java-modular-multi` pin their
dependency closures (per module, so a dependency of one module never lands in a
sibling's dependency management). The MODULAR_TO_MAVEN language demos - `kotlin`,
`scala`, and `groovy` - pin both their library dependency (by module name) and
their compiler toolchain on its own qualified trail (`@kotlin`, `@scala`,
`@groovy`), each `@jenesis.pin` tag carrying a version and SHA-256 checksum.
`groovy` is pinned to the stable `5.0.6`; `kotlin` and `scala` track whatever
their compilers resolve (for `scala` that is often a release candidate).
