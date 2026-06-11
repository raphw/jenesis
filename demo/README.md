Jenesis demos - a guided tour
=============================

These demos are meant to be read in order. Each one is self-contained and adds
one idea on top of the last, so the sequence doubles as a tutorial through
Jenesis' features: start with a single Maven project, turn it into a module,
scale to many modules, package a module into a runnable application image, build
a multi-release JAR, infer code-quality tools, bring in other JVM languages and
lint them too, customize or replace the build template itself, lock down the
supply chain, and finally assemble a release for Maven Central.

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

Two run-time switches apply to any demo, on the command line or in a `jenesis.properties`
file at the demo root (the file is loaded into system properties before the build; an
explicit `-D` wins over a file entry):

    java -Djenesis.project.watch=true build/jenesis/Project.java   # rebuild on every source change (Ctrl+C to stop)
    java -Djenesis.project.docker=true build/jenesis/Project.java  # run the whole build inside a throwaway container

Some demos ship their own launcher and are run with `java build/Demo.java`
instead: the ones that customize, replace, or drive the template directly
(`module-layout`, `custom-assembler`, `internal-module`, `external-module`,
`custom-maven`, `custom-modular`, `custom-build`, `custom-jmod`,
`supply-chain-security`, `publishing`) and the two executable demos (`java-pom-executable`,
`java-modular-executable`), which stage a
`jpackage` image and run it with the arguments you pass, and additionally ship a
`build/DemoNative.java` sibling that builds a native installer. Each demo writes
to a local `target/` directory; delete it to rebuild from scratch.

Quick index
-----------

| #  | Demo                                                 | Shows                                                                 | Run from the demo dir              |
| -- | ---------------------------------------------------- | -------------------------------------------------------------------- | ---------------------------------- |
| 1  | [`java-pom`](demo-01-java-pom/README.md)                     | A single Maven (`pom.xml`) project: `javac` + one real dependency, pinned | `java build/jenesis/Project.java`  |
| 2  | [`java-modular`](demo-02-java-modular/README.md)             | The same project as a Java module (`module-info.java`, no `pom.xml`)  | `java build/jenesis/Project.java`  |
| 3  | [`java-pom-multi`](demo-03-java-pom-multi/README.md)         | Many Maven modules: a library + a consumer that depends on it and an external artifact | `java build/jenesis/Project.java`  |
| 4  | [`java-modular-multi`](demo-04-java-modular-multi/README.md) | The multi-module project as Java modules                              | `java build/jenesis/Project.java`  |
| 5  | [`java-pom-executable`](demo-05-java-pom-executable/README.md)       | A runnable Maven project: a `<mainClass>` entry point + dependency, packaged into a native app image with `jpackage` | `java build/Demo.java`              |
| 6  | [`java-modular-executable`](demo-06-java-modular-executable/README.md) | The same as a Java module: entry point via `@jenesis.main` + dependency, packaged with `jpackage`        | `java build/Demo.java`              |
| 7  | [`java-multi-release`](demo-07-java-multi-release/README.md) | A modular multi-release JAR: Java 21 baseline plus a Java 25 override of one utility, selected by the runtime | `java build/jenesis/Execute.java`  |
| 8  | [`annotations`](demo-08-annotations/README.md)              | Run a Java annotation processor declared with `@jenesis.plugin`; the same jar on the module path stays dormant unless declared | `java build/jenesis/Project.java`  |
| 9  | [`java-quality`](demo-09-java-quality/README.md)             | Inferred code quality for Java: Checkstyle, PMD, SpotBugs, and a verifying `google-java-format`, each turned on by its config file | `java build/jenesis/Project.java`  |
| 10 | [`kotlin`](demo-10-kotlin/README.md)                         | Java + Kotlin in one module; exports a pure-Kotlin package            | `java build/jenesis/Project.java`  |
| 11 | [`kotlin-quality`](demo-11-kotlin-quality/README.md)         | Inferred code quality for Kotlin: detekt and ktlint, with ktlint also verifying formatting | `java build/jenesis/Project.java`  |
| 12 | [`scala`](demo-12-scala/README.md)                           | Java + Scala 3 in one module; exports a pure-Scala package            | `java build/jenesis/Project.java`  |
| 13 | [`scala-quality`](demo-13-scala-quality/README.md)           | Inferred code quality for Scala: Scalastyle and scalafmt, with scalafmt also verifying formatting | `java build/jenesis/Project.java`  |
| 14 | [`groovy`](demo-14-groovy/README.md)                         | Java + Groovy in one module; why a Groovy-only package cannot be exported | `java build/jenesis/Project.java`  |
| 15 | [`groovy-quality`](demo-15-groovy-quality/README.md)         | Inferred code quality for Groovy: CodeNarc lints the sources          | `java build/jenesis/Project.java`  |
| 16 | [`code-coverage`](demo-16-code-coverage/README.md)          | Inferred test observation: JaCoCo records coverage during the test run and renders an HTML/XML report, enabled with `-Djenesis.observe.jacoco=true` | `java build/jenesis/Project.java`  |
| 17 | [`maven-exclusions`](demo-17-maven-exclusions/README.md)     | Maven only: a dependency with an `<exclusions>` block; a test asserts the excluded transitive is absent | `java build/jenesis/Project.java`  |
| 18 | [`module-layout`](demo-18-module-layout/README.md)           | Explicitly select the pure MODULAR layout: resolve by module name, emit a modular jar with no `pom.xml` | `java build/Demo.java`             |
| 19 | [`custom-assembler`](demo-19-custom-assembler/README.md)     | Wrap the assembler to preprocess sources before the regular flow      | `java build/Demo.java`             |
| 20 | [`custom-jmod`](demo-20-custom-jmod/README.md)               | Wrap the assembler to pack extra content into a `.jmod`, `jlink` it into a runtime, and `jpackage` that into a runnable app | `java build/Demo.java`             |
| 21 | [`internal-module`](demo-21-internal-module/README.md)       | Move that preprocessing into a build module loaded from local source  | `java build/Demo.java`             |
| 22 | [`external-module`](demo-22-external-module/README.md)       | Resolve the same build module as a published coordinate               | `java build/Demo.java`             |
| 23 | [`custom-maven`](demo-23-custom-maven/README.md)             | Drive a multi-module Maven build via `MavenProject.make(root, assembler)`, no `Project` | `java build/Demo.java`             |
| 24 | [`custom-modular`](demo-24-custom-modular/README.md)         | The same via `ModularProject.make(root, assembler)` for modules       | `java build/Demo.java`             |
| 25 | [`custom-build`](demo-25-custom-build/README.md)             | No `Project` at all: wire a `BuildExecutor` by hand                   | `java build/Demo.java`             |
| 26 | [`docker-isolation`](demo-26-docker-isolation/README.md)     | A standard build whose test and artifact `main` both grab host secrets, and how Docker confines them | `java build/jenesis/Project.java`  |
| 27 | [`supply-chain-security`](demo-27-supply-chain-security/README.md) | Two modules that must *not* build: an unpinned dependency rejected by strict pinning, and a wrong checksum rejected always | `java build/Demo.java`             |
| 28 | [`publishing`](demo-28-publishing/README.md)                 | Assemble a Maven Central ready bundle (POM metadata + sources/javadoc jars) and resolve it back | `java build/Demo.java`             |
| 29 | [`kotlin-plugin`](demo-29-kotlin-plugin/README.md)           | Run a Kotlin compiler plugin (kotlinx.serialization) declared with `@jenesis.plugin kotlin <repo>/<coord>`, passed to the compiler as `-Xplugin=` | `java build/jenesis/Project.java`  |
| 30 | [`sbom`](demo-30-sbom/README.md)                             | Emit a CycloneDX SBOM (embedded in the jar, staged as a report, and attached to the Maven repo for publication) with `-Djenesis.sbom.cyclonedx=json` | `java build/jenesis/Project.java`  |
| 31 | [`profiles`](demo-31-profiles/README.md)                     | Build profiles: a `release` profile selected with `-Djenesis.project.properties=release` turns on source jars and chains to a `supply-chain` profile that adds an SBOM | `java build/jenesis/Project.java`  |

## 1. A single Maven project - [`java-pom`](demo-01-java-pom/README.md)

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

See [`java-pom/README.md`](demo-01-java-pom/README.md) for the pinned POM in full.

## 2. The same project as a module - [`java-modular`](demo-02-java-modular/README.md)

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
  a dependency is pinned with
  `@jenesis.pin org.slf4j 2.0.16` and
  `@jenesis.pin org.slf4j/slf4j-api 2.0.16 SHA-256/...`
  Javadoc tags on the module declaration. A pin key is GROUP-first: a bare token
  such as `org.slf4j` is a Java module name (short for `main/module/org.slf4j`),
  a single-slash token such as `org.slf4j/slf4j-api` is a Maven
  `<groupId>/<artifactId>` (short for `main/maven/...`), and the fully explicit
  form is `<group>/<repository>/<coordinate>`. An ordinary application dependency
  lives in the default `main` group.
- **Staging.** `stage` lays the output out as both a module repository and a
  Maven repository, and `export` publishes them locally.

Compare `java-pom` and `java-modular` side by side: the same one-class project,
expressed two ways, and Jenesis adapts the whole pipeline to the descriptor it
finds.

## 3. Many modules at once - [`java-pom-multi`](demo-03-java-pom-multi/README.md) and [`java-modular-multi`](demo-04-java-modular-multi/README.md)

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

## 4. Packaging a runnable application - [`java-pom-executable`](demo-05-java-pom-executable/README.md), [`java-modular-executable`](demo-06-java-modular-executable/README.md)

A module that declares an entry point can be packaged into a **native, self-contained
application image** by the JDK's `jpackage`. These two demos are the runnable
counterparts of `java-pom` and `java-modular`: each adds a `main` method and one real
dependency, and ships a `build/Demo.java` launcher that stages the image and runs it.

The entry point is declared the same way the launcher already reads it: an
`@jenesis.main sample.Sample` Javadoc tag on `module-info.java` (modular), or a
`<mainClass>` property in the POM (Maven). Both record `main=sample.Sample` in the
module's `module.properties`, and that single field is what marks the module as
packageable - modules without it are skipped.

Packaging is opt-in through one property, `-Djenesis.java.jpackage` (its value is the
`jpackage --type`; a bare flag means `app-image`). When set, the assembler wires a
per-module `package` step that runs `jpackage` over the produced jar plus its runtime
dependency jars, so the image bundles the whole closure - `commons-lang3` in the Maven
demo, `slf4j-api` in the modular one. Each image is then collected by the `STAGE`
module's `packages` step into `stage/packages/`, the staging analogue of `stage/maven`
and `stage/modular`.

Rather than set that property on the command line, `build/Demo.java` configures it
**explicitly on the assembler** - `new InferredMultiProjectAssembler().packaging("app-image")`,
no `System.setProperty` - then builds the fixed `stage` target and launches the produced
image, forwarding its own arguments to the packaged app's `main`:

    java build/Demo.java ada lovelace

Each demo also ships `build/DemoNative.java`, the same launcher but packaging the
platform's **fully bundled native installer** (`deb`/`rpm`, `exe`/`msi`, `dmg`/`pkg`)
instead of an app-image - the single artifact you hand to a user to install rather than
a directory you launch in place. It reports the produced package instead of running it,
and needs the platform's packaging tooling on the PATH, so it is a local exercise rather
than a CI one.

The new idea is that **the build produces a runnable artifact, not just a jar**, and
that one entry-point declaration (`@jenesis.main` / `<mainClass>`) drives both launching
and packaging through the same `module.properties` field.

## 5. A multi-release JAR - [`java-multi-release`](demo-07-java-multi-release/README.md)

`java-multi-release` is a modular project (a `module-info.java`, no `pom.xml`, so
the MODULAR_TO_MAVEN layout) that produces a single JAR carrying two
implementations of one class and lets the runtime pick between them. The module
compiles at Java 21 (`@jenesis.release 21`), and one utility, `sample.Platform`,
has a Java 25 override placed under `sources/META-INF/versions/25/`. Jenesis
compiles the overlay in a second `javac` pass with `--release 25`, writes it to
`META-INF/versions/25/` in the JAR, and marks the manifest `Multi-Release: true`.

The new idea is that **one artifact can hold version-specific code**: the JVM
loads the highest versioned class that does not exceed its own feature version, so
the same JAR run on Java 21 prints the baseline line and on Java 25 prints the
override. Run it with the shipped launcher, which builds then executes the
module's `@jenesis.main`:

    java build/jenesis/Execute.java

There are no module dependencies here - the demo is only about the multi-release
packaging - so unlike `java-modular` it has nothing to pin.

## 6. Annotation processors - [`annotations`](demo-08-annotations/README.md)

`annotations` runs a Java annotation processor (JSR-269) over a modular project.
The processor is named with a single Javadoc tag on the module declaration:

    @jenesis.plugin org.immutables.value

The processor is named by module name, just as `requires` names a dependency.
The tag is generic - `@jenesis.plugin <repository>/<coordinate>` (or a bare module
name) resolves to the `plugin` scope, a Java annotation processor; naming a
compiler first (`@jenesis.plugin kotlin <repo>/<coord>`) routes to the
`plugin:<compiler>` scope instead.
Jenesis resolves the `plugin`-scope entry alongside the
module's other dependencies, and the Java compiler picks the `plugin`-scope
jars and hands them to `javac` as an explicit `--processor-module-path`. The Immutables processor then turns the abstract
`@Value.Immutable` `Animal` into a generated `ImmutableAnimal` builder, which `Zoo`
uses.

The new idea is that **processors are declared, never discovered**. `javac` only
runs processors found on the processor path, never the class or module path, so a
dependency that happens to bundle a processor stays inert until you name it. The
demo shows this directly: `org.immutables.value` is also a regular module
dependency (`requires static`), so the very same jar sits on the compile module
path - yet delete the `@jenesis.plugin` line and the processor no longer
runs, `ImmutableAnimal` is never generated, and the build fails to compile `Zoo`.
The version is pinned the usual way, with the `pin` step writing back a
`@jenesis.pin org.immutables.value 2.12.2` line (the bare Java module name) and a
`@jenesis.pin org.immutables/value 2.12.2 SHA-256/...` line (the Maven
`<groupId>/<artifactId>`), both in the default `main` group.

## 7. Code quality for Java - [`java-quality`](demo-09-java-quality/README.md)

`java-quality` turns on a set of code-quality tools without a build script: each
tool runs because its configuration file is present at the project root. A
`checkstyle.xml` and a `pmd.xml` lint the sources, a `spotbugs-exclude.xml` brings
in SpotBugs over the compiled classes, and `jenesis.format.java=google` selects
`google-java-format`.

The new idea is **inference from configuration**. Jenesis binds the matched file
from the configuration directory (the project root by default) into the build,
which is how a root-level filter reaches SpotBugs even though SpotBugs runs after
`javac` and sees only classes. Each tool resolves in its own group, so its closure
never collides with the project's dependencies. The linters are report-only by
default; the formatter runs in *verify* mode, failing the build when a source is
not already formatted, and a single `-Djenesis.format.rewrite=true` flips it to
rewrite the sources in place.

## 8. Other JVM languages - [`kotlin`](demo-10-kotlin/README.md), [`scala`](demo-12-scala/README.md), [`groovy`](demo-14-groovy/README.md)

Jenesis drives non-Java compilers through the same inferred compiler chain, with
no language-specific configuration beyond the sources. A *resolved* compiler
(Kotlin, Scala, Groovy) is pinned in its own **group** - `kotlin/...`,
`scala/...`, `groovy/...` - so the compiler's own closure never
collides with the project's dependencies (which live in the `main` group).

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

These three are quick reads; each `README.md` has the detail. (Each pins its
library dependency in the `main` group and its compiler toolchain in the
language group; see "Pinning" below.)

## 9. Code quality for other languages - [`kotlin-quality`](demo-11-kotlin-quality/README.md), [`scala-quality`](demo-13-scala-quality/README.md), [`groovy-quality`](demo-15-groovy-quality/README.md)

The same inference carries to the other languages, each tool again selected by its
configuration file:

- `kotlin-quality` lints with detekt (`detekt.yml`) and ktlint (`.editorconfig`),
  and ktlint doubles as a verifying formatter.
- `scala-quality` lints with Scalastyle (`scalastyle-config.xml`) and scalafmt
  (`.scalafmt.conf`), and scalafmt doubles as a verifying formatter.
- `groovy-quality` lints with CodeNarc (`codenarc.xml`); there is no inferred
  Groovy formatter, so it is lint-only.

Each compiles through the same language chain as its plain-language counterpart, so
the compiler stays pinned in its language group while the quality tools float their
own `RELEASE`. The verifying formatters share the `-Djenesis.format.rewrite=true`
switch shown for Java.

## 10. Test coverage - [`code-coverage`](demo-16-code-coverage/README.md)

`code-coverage` adds the first *test observation* engine: JaCoCo. Pass
`-Djenesis.observe.jacoco=true` and the test run is wrapped so the JaCoCo agent
records which lines the tests exercise; a report step then renders an HTML and
XML coverage report from that data, the compiled classes, and the sources.

The new idea is **inferred test observation**. An `InferredTestObservationModule`
sits where the plain test module used to, bundling JaCoCo today and leaving room
for more observation engines. With coverage off (the default) it is a plain test
run; with it on, the agent instruments the run with no change to the sources, and
JaCoCo resolves its own tooling in a `jacoco` group, separate from the project's
dependencies.

## 11. Excluding a transitive dependency - [`maven-exclusions`](demo-17-maven-exclusions/README.md)
----------------------------------------------------

A Maven dependency drags in a transitive subtree, and `<exclusions>` prunes part
of it. `maven-exclusions` declares Apache Commons Text but excludes its Apache
Commons Lang transitive, and a test confirms the result: Commons Text is on the
class path, Commons Lang is not.

The new idea is **exclusions, and why they are a Maven-only concern**. A modular
project has no equivalent: a module only sees what its `module-info.java`
`requires`, so an unwanted transitive cannot silently reach the module path and
there is nothing to exclude. So the idea is shown here, and only here, on a Maven
project. The test looks the classes up as resources rather than with
`Class.forName(...)` (loading a Commons Text class would link it against the
missing Commons Lang), and - exactly as in Maven, where test scope extends
compile scope - the exclusion applies to the test class path too, not just the
main one.

## 12. Choosing the pure modular layout - [`module-layout`](demo-18-module-layout/README.md)
----------------------------------------------------

`module-layout` is the same shape of project as `demo-02-java-modular` - a
`module-info.java` requiring a named module - but `build/Demo.java` selects the
layout in code: `new Project().layout(Project.Layout.MODULAR)`.

The new idea is the **layout choice**. A `module-info.java` with no `pom.xml`
auto-detects MODULAR_TO_MAVEN, which translates each `requires` into a Maven
coordinate, resolves the closure through Maven, and emits the modular jar *plus* a
generated `pom.xml`. The pure **MODULAR** layout instead resolves dependencies by
Java module name against the Jenesis module repository and emits **only the
modular jar - no `pom.xml`** - so the build never touches Maven coordinates at all;
`stage` then produces `target/stage/modular` with no `target/stage/maven`. Because
every dependency is resolved as a named module the closure is provably
module-path-consumable, which is also why MODULAR is opt-in (it cannot resolve a
dependency that ships only as an automatic module, so `AUTO` never picks it).
Reach for it when artifacts are consumed only as Java modules and you want no POMs
in the pipeline; keep the default when you also need a `pom.xml`.

## 13. Customizing the build - [`custom-assembler`](demo-19-custom-assembler/README.md)

The remaining demos open up the template. `custom-assembler` keeps the standard
MODULAR_TO_MAVEN flow but **wraps** the stock `InferredMultiProjectAssembler` so each
module's sources pass through a preprocessing step (a `${greeting}` substitution)
before compile, jar, and test run unchanged:

    new Project()
            .assembler(new PreprocessingAssembler(new InferredMultiProjectAssembler()))

The trick is small and reusable: the wrapper adds a `preprocess` step that
rewrites the sources, then redirects the descriptor at it with
`descriptor.sources("preprocess")` and hands the unchanged stock assembler
the redirected descriptor. The Java toolchain is never reimplemented - a source
transformation is simply interposed in front of it. Any step that produces a
`sources/` tree (template expansion, code generation, license headers) fits the
same shape. This demo is launched with `java build/Demo.java`.

[`custom-jmod`](demo-20-custom-jmod/README.md) is a sibling example of the same wrapping technique, applied to a
different extension point. It enables the stock `jmod`, `jlink`, and `jpackage`
steps (`new InferredMultiProjectAssembler().jmod(true).jlink(true).packaging("app-image")`)
and only *contributes an extra input*: a `config` step that emits a `jmodconfig/`
directory, declared as the module's `content` with `descriptor.content("config")`.
The stock `jmod` step depends on every step named in `content`, routes
`jmodconfig/`/`jmodlibs/`/`jmodcmds/` to `jmod --config`/`--libs`/`--cmds`; `jlink`
links the resulting `.jmod` into a runtime image; and `jpackage`, seeing that runtime
among its inputs, wraps it with `--runtime-image` instead of linking its own - no
jmod/jlink/jpackage wiring is duplicated in the wrapper. Because the config rides in
the jmod's config section, it travels `jmod -> jlink runtime -> jpackage image`, and
the launched app reads it back from its own `<java.home>/conf/` - content a jar cannot
carry into a packaged runtime. Also `java build/Demo.java`.

## 14. Preprocessing in a reusable build module - [`internal-module`](demo-21-internal-module/README.md), [`external-module`](demo-22-external-module/README.md)

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
build-module bridge dependencies (`build.jenesis`, `org.json`) are pinned by bare
Java module name in the default `main` group.

Together these show that build logic itself is just another module - it can be
authored inline, loaded from source, or consumed as a versioned artifact.

> Status: both `internal-module` and `external-module` currently fail at the
> build-module load step, because the plugin's `build.jenesis` dependency
> resolves to a published version that lags the local sources the host runs
> against. They will work once a matching `build.jenesis` is released; see their
> `README.md`s.

## 15. Driving the build without `Project` - [`custom-maven`](demo-23-custom-maven/README.md), [`custom-modular`](demo-24-custom-modular/README.md)

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
a root and an assembler. The assembler is the stock `InferredMultiProjectAssembler`,
adapted with a one-line wrapper so each discovered descriptor becomes a
`ProjectModuleDescriptor`. Reach for the full `make(...)` overload (the one
`Project` itself uses) when you need a custom repository, strict pinning, or a
specific digest.

## 16. Dropping the template entirely - [`custom-build`](demo-25-custom-build/README.md)

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

## 17. Confining the build with Docker - [`docker-isolation`](demo-26-docker-isolation/README.md)

A build executes untrusted code even when nothing about it is customised: the
stock pipeline runs your tests (and whatever your test dependencies pull in), and
the artifact it produces has a `main` that runs later - all with the rights of
whoever started the build. `docker-isolation` is a plain `Project`-based build
(deliberately not customised - a custom launcher's own `main` would only add
another place for a vulnerability to hide) whose **test** and artifact **`main`**
each read a secret file (`~/.demo-credentials`) and a secret environment variable
(`DEMO_SECRET`), then overwrite the file. Run on the host with the stock launchers
(`java build/jenesis/Project.java`, then `java build/jenesis/Execute.java`), both
actors reach the secrets.

The new idea is that **Jenesis can run the build and the launched program inside a
throwaway container** - `-Djenesis.execute.docker=true` sandboxes the program's
`main`, `-Djenesis.project.docker=true` sandboxes the build (the test included) -
where the host home and environment are absent, so neither secret is in reach. The
demo also shows the consequence of the local Maven/Jenesis repositories being
mounted **read-only**: dependencies must be pre-cached, and `export` fails inside
the container. It needs a Docker daemon, so it is a local exercise rather than a
CI one. There are no assertions; each actor just reports what it managed to do.

## 18. Supply-chain security - [`supply-chain-security`](demo-27-supply-chain-security/README.md)
----------------------------------------------------

Pinning has two halves, and this demo shows both by getting them wrong on purpose.
`supply-chain-security` has two modules and a `build/Demo.java` that asserts each
fails to build: an **`unpinned`** module whose dependency carries a version but no
checksum, and a **`tampered`** module whose dependency is pinned to a wrong
`SHA-256`. The unpinned one builds by default but is rejected under
`pinning(Pinning.STRICT)` (a hardened environment can refuse any unverified
dependency); the tampered one fails **regardless** of strict pinning, because the
`Dependencies` step checks every fetched artifact against its pin and rejects a mismatch -
exactly what would catch a swapped or compromised artifact.

The new idea is **strict pinning vs. checksum verification**: the former decides
*whether* an unverified dependency may be used at all, the latter proves a pinned
dependency is the exact artifact you vetted. Unlike every other demo, this one is
a project that must *not* build.

## 19. Publishing to Maven Central - [`publishing`](demo-28-publishing/README.md)

The final demo closes the loop from sources to a release. Publishing to Central is
two jobs - **produce a correct bundle** and **upload it** - and Jenesis owns the
first while deferring the second. `publishing` is a MODULAR_TO_MAVEN library whose
`module-info.java` plus a `project.properties` supply the descriptive metadata
Central demands (`name`, `description`, `url`, `<licenses>`, `<developers>`,
`<scm>`); `build/Demo.java` enables source and javadoc jars on the `Project`
builder and runs `stage`, materializing the full upload-ready bundle under
`target/stage/maven/output/` - the main jar, the generated POM, `-sources.jar`,
and `-javadoc.jar`. It then resolves that coordinate straight back out of the
staged tree (which is itself a Maven repository) to prove the bundle is complete
and consumable, all offline.

The new idea is the **division of labour**: Jenesis guarantees *what* you publish
is correct, and the demo shows the last mile is someone else's job. `export`
performs a real publish into the local Maven repository, while the remote upload
and GPG signing are deferred to a dedicated release tool - the README recommends
**[JReleaser](https://jreleaser.org/)** pointed at `target/stage/maven/output/`
(exactly how Jenesis itself releases). So the demo never needs credentials, a
signing key, or the network, yet shows the complete, validated artifact set a
release would carry.

## 20. Compiler plugins for other languages - [`kotlin-plugin`](demo-29-kotlin-plugin/README.md)

The same `@jenesis.plugin` tag that wires a Java annotation processor (demo 6)
also wires compiler plugins for the other languages - naming a compiler before the
coordinate routes the plugin to that compiler. `kotlin-plugin` declares the
kotlinx.serialization compiler plugin on the Kotlin compiler:

    @jenesis.plugin kotlin maven/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin

Naming the compiler routes the plugin to the `plugin:kotlin` scope; Jenesis
resolves it version coordinated to the compiler, and
the Kotlin compiler picks the `plugin:kotlin`-scope jar and passes it as
`-Xplugin=<jar>`. The plugin then generates `Point.serializer()` for the
`@Serializable` data class, which `Use` references - delete the `@jenesis.plugin`
line and the build fails, exactly like the annotation processor demo. The
resolution path is identical for every language (a `plugin:<compiler>` scope per
compiler); only the compiler flag differs (`--processor-path` for
`javac`, `-Xplugin=` for Kotlin, `-Xplugin:` for Scala).

## 21. Software bill of materials - [`sbom`](demo-30-sbom/README.md)

`sbom` turns on a CycloneDX software bill of materials with a single property,
`-Djenesis.sbom.cyclonedx=json` (or `xml`). The default Java assembler runs an
`Sbom` step before the jar is sealed, reading the module's resolved dependency
graph, content hashes, and captured licenses, and emits the document in-process
(no external tool). It lands three ways: embedded in the jar at `META-INF/sbom/`,
collected on `stage` into `target/stage/reports/sbom/`, and - when a Maven
repository is staged - attached as `<artifact>-<version>-cyclonedx.json` next to
the pom so `export` publishes it to Maven Central.

## 22. Build profiles - [`profiles`](demo-31-profiles/README.md)

`profiles` shows how a named set of properties switches features on together. A
profile is a `*.properties` file at the project root; `jenesis.project.properties`
selects one (or a comma-separated list, the `.properties` suffix optional), loaded
before the build is configured. Profiles compose by chaining - a loaded file may
set `jenesis.project.properties` itself to pull in more. The demo's `release`
profile turns on source jars and chains to a `supply-chain` profile that adds the
SBOM, so `-Djenesis.project.properties=release` produces a publication build in one
switch. Every property a profile sets is a default, so the command line always
wins. The always-loaded base is `jenesis.properties` (optional).

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
native application image with `-Djenesis.java.jpackage` (the value is the `jpackage
--type`); the image is collected under `stage/packages/` next to `stage/maven` and
`stage/modular`. See demo 4.

**Pinning, checksums, and scopes.** Pins live in source: a POM's
`<dependencyManagement>` (with `<!--Checksum/...-->` comments) or a
`module-info.java`'s
`@jenesis.pin <token> <version> [<algorithm>/<hex>]`
Javadoc tags. The pin token is GROUP-first and reads by slash count: a bare token
is a Java module name (short for `main/module/<name>`), one slash is a Maven
`<groupId>/<artifactId>` (short for `main/maven/...`), and two or more slashes are
the fully explicit `<group>/<repository>/<coordinate>`. The `Dependencies` step
verifies every fetch against a pinned checksum, and strict pinning
(`-Djenesis.dependency.pin=strict`) fails the build on any unpinned coordinate.
The group is the top isolation axis: a project's own dependencies live in the
`main` group (where `compile` and `runtime` are scopes within it, runtime
inheriting compile), and a *resolved compiler* lives in its own group (`kotlin`,
`scala`, `groovy`), so it never mixes with the project's own dependencies.

Current pin state of the demos: every demo is committed pinned with checksums.
`java-pom`, `java-pom-multi`, `java-modular`, and `java-modular-multi` pin their
dependency closures (per module, so a dependency of one module never lands in a
sibling's dependency management). The MODULAR_TO_MAVEN language demos - `kotlin`,
`scala`, and `groovy` - pin both their library dependency (in the `main` group)
and
their compiler toolchain in its own group (`kotlin`, `scala`,
`groovy`), each `@jenesis.pin` tag carrying a version and SHA-256 checksum.
`groovy` pins its `org.apache.groovy` library to the stable `5.0.6` and its
`groovy`-group compiler to `6.0.0-alpha-1`; `kotlin` and `scala` track whatever
their compilers resolve (for `scala` that is often a release candidate).
