Jenesis demos - a guided tour
=============================

These demos are meant to be read in order. Each one is self-contained and adds
one idea on top of the last, so the sequence doubles as a tutorial through
Jenesis' features: start with a single Maven project, turn it into a module,
scale to many modules, package a module into a runnable application image, build
a multi-release JAR, infer code-quality tools, bring in other JVM languages and
lint them too, customize or replace the build template itself, lock down the
supply chain, assemble a release for Maven Central, compile a module ahead of
time into a GraalVM native binary, and finally share build outputs through a
content-addressed cache.

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
(`custom-assembler`, `internal-module`, `external-module`,
`custom-maven`, `custom-modular`, `custom-build`, `custom-jmod`,
`supply-chain-security`, `publishing`) and the two executable demos (`java-pom-executable`,
`java-modular-executable`), which stage a
`jpackage` image and run it with the arguments you pass, and additionally ship a
`build/DemoNative.java` sibling that builds a native installer and a
`build/DemoLauncher.java` sibling that builds a single `java -jar`-able executable jar
with the `build.jenesis.launcher` and runs it. Each demo writes
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
| 6  | [`java-modular-executable`](demo-06-java-modular-executable/README.md) | The same as a Java module: entry point via `@jenesis.main` + dependency, packaged with `jpackage` (and a plain `.jmod` + `jlink` runtime, and a `bundle` zip) | `java build/Demo.java`              |
| 7  | [`java-multi-release`](demo-07-java-multi-release/README.md) | A modular multi-release JAR: Java 21 baseline plus a Java 25 override of one utility, selected by the runtime | `java build/jenesis/Execute.java`  |
| 8  | [`annotations`](demo-08-annotations/README.md)              | Run a Java annotation processor declared with `@jenesis.plugin`; the same jar on the module path stays dormant unless declared | `java build/jenesis/Project.java`  |
| 9  | [`java-quality`](demo-09-java-quality/README.md)             | Inferred code quality for Java: Checkstyle, PMD, SpotBugs, and a verifying `google-java-format`, each turned on by its config file | `java build/jenesis/Project.java`  |
| 10 | [`sbom`](demo-10-sbom/README.md)                             | Emit a CycloneDX SBOM (embedded in the jar, staged as a report, and attached to the Maven repo for publication) with `-Djenesis.sbom.cyclonedx=json` | `java build/jenesis/Project.java`  |
| 11 | [`profiles`](demo-11-profiles/README.md)                     | Build profiles: a `release` profile selected with `-Djenesis.project.properties=release` turns on source jars and chains to a `supply-chain` profile that adds an SBOM | `java build/jenesis/Project.java`  |
| 12 | [`kotlin`](demo-12-kotlin/README.md)                         | Java + Kotlin in one module; exports a pure-Kotlin package            | `java build/jenesis/Project.java`  |
| 13 | [`kotlin-quality`](demo-13-kotlin-quality/README.md)         | Inferred code quality for Kotlin: detekt and ktlint, with ktlint also verifying formatting | `java build/jenesis/Project.java`  |
| 14 | [`kotlin-plugin`](demo-14-kotlin-plugin/README.md)           | Run a Kotlin compiler plugin (kotlinx.serialization) declared with `@jenesis.plugin kotlinc <repo>/<coord>`, passed to the compiler as `-Xplugin=` | `java build/jenesis/Project.java`  |
| 15 | [`scala`](demo-15-scala/README.md)                           | Java + Scala 3 in one module; exports a pure-Scala package            | `java build/jenesis/Project.java`  |
| 16 | [`scala-quality`](demo-16-scala-quality/README.md)           | Inferred code quality for Scala: Scalastyle and scalafmt, with scalafmt also verifying formatting | `java build/jenesis/Project.java`  |
| 17 | [`groovy`](demo-17-groovy/README.md)                         | Java + Groovy in one module; why a Groovy-only package cannot be exported | `java build/jenesis/Project.java`  |
| 18 | [`groovy-quality`](demo-18-groovy-quality/README.md)         | Inferred code quality for Groovy: CodeNarc lints the sources          | `java build/jenesis/Project.java`  |
| 19 | [`code-coverage`](demo-19-code-coverage/README.md)          | Inferred test observation: JaCoCo records coverage during the test run and renders an HTML/XML report, enabled with `-Djenesis.observe.jacoco=true` | `java build/jenesis/Project.java`  |
| 20 | [`test-selection`](demo-20-test-selection/README.md)         | Re-run only the tests a change can affect (`-Djenesis.test.incremental`), a watch-mode development-loop optimisation | `java build/Demo.java`             |
| 21 | [`pitest`](demo-21-pitest/README.md)                         | Mutation testing: a `pitest.properties` config file makes the build run PIT, which seeds faults into the code and checks the tests catch them | `java build/jenesis/Project.java`  |
| 22 | [`maven-exclusions`](demo-22-maven-exclusions/README.md)     | Maven only: a dependency with an `<exclusions>` block; a test asserts the excluded transitive is absent | `java build/jenesis/Project.java`  |
| 23 | [`module-layout`](demo-23-module-layout/README.md)           | Explicitly select the pure MODULAR layout (via `jenesis.properties`): resolve by module name, emit a modular jar with no `pom.xml` | `java build/jenesis/Project.java`  |
| 24 | [`module-classifier`](demo-24-module-classifier/README.md)   | Pin a classified variant of a module (`:jdk-flow:0.4.3`): the build fetches the classifier artifact, validated by checksum and asserted at runtime | `java build/jenesis/Execute.java`  |
| 25 | [`platform-guard`](demo-25-platform-guard/README.md)         | Select a dependency variant per platform: guarded pin lines (`[windows]`) matched against the `-Djenesis.platform.<token>=true` flags, with an unguarded fallback | `java build/jenesis/Execute.java`  |
| 26 | [`platform-guard-pom`](demo-26-platform-guard-pom/README.md)  | The same guards in a `pom.xml`'s `<!--jenesis.pin-->` block: switch a transitive's pinned version per platform, each variant checksummed | `java build/jenesis/Execute.java`  |
| 27 | [`custom-assembler`](demo-27-custom-assembler/README.md)     | Wrap the assembler to preprocess sources before the regular flow      | `java build/Demo.java`             |
| 28 | [`custom-jmod`](demo-28-custom-jmod/README.md)               | Wrap the assembler to pack extra content into a `.jmod`, `jlink` it into a runtime, and `jpackage` that into a runnable app | `java build/Demo.java`             |
| 29 | [`internal-module`](demo-29-internal-module/README.md)       | Move that preprocessing into a build module loaded from local source | `java build/Demo.java`             |
| 30 | [`external-module`](demo-30-external-module/README.md)       | Resolve the same build module as a published coordinate | `java build/Demo.java`             |
| 31 | [`custom-maven`](demo-31-custom-maven/README.md)             | Drive a multi-module Maven build via `MavenProject.make(root, assembler)`, no `Project` | `java build/Demo.java`             |
| 32 | [`custom-modular`](demo-32-custom-modular/README.md)         | The same via `ModularProject.make(root, assembler)` for modules       | `java build/Demo.java`             |
| 33 | [`custom-build`](demo-33-custom-build/README.md)             | No `Project` at all: wire a `BuildExecutor` by hand                   | `java build/Demo.java`             |
| 34 | [`docker-isolation`](demo-34-docker-isolation/README.md)     | A standard build whose test and artifact `main` both grab host secrets, and how Docker confines them | `java build/jenesis/Project.java`  |
| 35 | [`supply-chain-security`](demo-35-supply-chain-security/README.md) | Two modules that must *not* build: an unpinned dependency rejected by strict pinning, and a wrong checksum rejected always | `java build/Demo.java`             |
| 36 | [`publishing`](demo-36-publishing/README.md)                 | Assemble a Maven Central ready bundle (POM metadata + sources/javadoc jars) and resolve it back | `java build/Demo.java`             |
| 37 | [`native-image`](demo-37-native-image/README.md)             | Compile a modular app ahead of time into a standalone GraalVM native binary with `-Djenesis.java.native=true` (needs GraalVM `native-image`; local-only) | `java build/jenesis/Project.java`  |
| 38 | [`build-cache`](demo-38-build-cache/README.md)               | A shared content-addressed build cache (`-Djenesis.executor.cache=<folder>`) that serves step outputs across builds; shown by bootstrapping it then serving a full `-Djenesis.executor.rebuild=true` from it | `java build/jenesis/Project.java`  |

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

Two more outputs round out the packaging menu, both opt-in flags shown on
`java-modular-executable`. `-Djenesis.java.jmod=true -Djenesis.java.jlink=true` builds
the lower-level pieces `jpackage` uses internally: a `.jmod` staged beside the modular
jar, and a `jlink` runtime image trimmed to the module graph under `stage/runtime`,
runnable from its own `bin/java -m` - the foundation `custom-jmod` (section 16) later
builds on. And two no-runtime forms: `-Djenesis.java.bundle=true` writes a `bundle.zip` of just the
jars plus an `application.properties` to unzip onto an off-the-shelf JRE base (also used
to ship the app as a container image in `docker-isolation`), while
`-Djenesis.java.launcher=true` shades the published `build.jenesis.launcher` into a
**single executable jar** you run with `java -jar foo.jar` - dependencies exploded into
per-dependency subfolders so the module graph is reconstructed at run time rather than
flattened into a fat jar.

The new idea is that **the build produces a runnable artifact, not just a jar**, and
that one entry-point declaration (`@jenesis.main` / `<mainClass>`) drives launching,
packaging, and runtime-image linking through the same `module.properties` field.

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

## 8. Software bill of materials - [`sbom`](demo-10-sbom/README.md)

`sbom` turns on a CycloneDX software bill of materials with a single property,
`-Djenesis.sbom.cyclonedx=json` (or `xml`). The default Java assembler runs an
`Sbom` step before the jar is sealed, reading the module's resolved dependency
graph, content hashes, and captured licenses, and emits the document in-process
(no external tool). It lands three ways: embedded in the jar at `META-INF/sbom/`,
collected on `stage` into `target/stage/reports/sbom/`, and - when a Maven
repository is staged - attached as `<artifact>-<version>-cyclonedx.json` next to
the pom so `export` publishes it to Maven Central.

## 9. Build profiles - [`profiles`](demo-11-profiles/README.md)

`profiles` shows how a named set of properties switches features on together. A
profile is a `*.properties` file at the project root; `jenesis.project.properties`
selects one (or a comma-separated list, the `.properties` suffix optional), loaded
before the build is configured. Profiles compose by chaining - a loaded file may
set `jenesis.project.properties` itself to pull in more. The demo's `release`
profile turns on source jars and chains to a `supply-chain` profile that adds the
SBOM from section 8, so `-Djenesis.project.properties=release` produces a
publication build in one switch. Every property a profile sets is a default, so the
command line always wins. The always-loaded base is `jenesis.properties` (optional).

## 10. Kotlin - [`kotlin`](demo-12-kotlin/README.md), [`kotlin-quality`](demo-13-kotlin-quality/README.md), [`kotlin-plugin`](demo-14-kotlin-plugin/README.md)

Jenesis drives non-Java compilers through the same inferred compiler chain, with
no language-specific configuration beyond the sources. `kotlin` is a
MODULAR_TO_MAVEN module (a `module-info.java`, no `pom.xml`) that mixes a `.java`
source with Kotlin. The chain compiles Kotlin *before* `javac` (the Kotlin
compiler reads `.java` as source for resolution but emits only Kotlin classes), and
`javac` then sees those classes through `--patch-module`; that ordering lets the
module export a package holding *only* Kotlin, which the demo does. The resolved
Kotlin compiler is pinned in its own **group**, `kotlinc`, so its closure never
collides with the project's `main`-group dependencies.

`kotlin-quality` carries the same configuration-file inference shown for Java
(section 7) to Kotlin: detekt (`detekt.yml`) and ktlint (`.editorconfig`) lint the
sources, and ktlint doubles as a verifying formatter sharing the
`-Djenesis.format.rewrite=true` switch. Each tool floats its own `RELEASE` while the
compiler stays pinned in the `kotlinc` group.

`kotlin-plugin` adds a compiler plugin. The same `@jenesis.plugin` tag that wires a
Java annotation processor (section 6) wires compiler plugins for the other
languages - naming a compiler before the coordinate routes the plugin to that
compiler:

    @jenesis.plugin kotlinc maven/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin

Jenesis resolves the plugin in the `kotlinc` group, version-coordinated to the
compiler, and the Kotlin compiler passes it as `-Xplugin=<jar>`. The
kotlinx.serialization plugin then generates `Point.serializer()` for the
`@Serializable` data class, which `Use` references - delete the `@jenesis.plugin`
line and the build fails, exactly like the annotation processor demo.

## 11. Scala - [`scala`](demo-15-scala/README.md), [`scala-quality`](demo-16-scala-quality/README.md)

Scala works exactly like Kotlin. `scala` is a MODULAR_TO_MAVEN module that mixes a
`.java` source with Scala; the chain compiles Scala before `javac`, which sees the
Scala classes through `--patch-module`, so the module exports a pure-Scala package.
The Scala compiler is pinned in its own `scalac` group.

`scala-quality` infers Scalastyle (`scalastyle-config.xml`) and scalafmt
(`.scalafmt.conf`) from their configuration files, with scalafmt doubling as a
verifying formatter. A compiler plugin would route to the `scalac` group and pass
as `-Xplugin:`, the same shape as Kotlin's plugin.

## 12. Groovy - [`groovy`](demo-17-groovy/README.md), [`groovy-quality`](demo-18-groovy-quality/README.md)

Groovy follows the same pattern with one difference: `groovyc` resolves Java only
from the compiled class path, so it must run *after* `javac` and cannot populate a
package before the export is validated. An exported package therefore needs at
least one Java type; the demo's `README.md` explains why this is permanent for
Groovy but not for Kotlin or Scala. The Groovy compiler is pinned in its own
`groovyc` group.

`groovy-quality` lints with CodeNarc (`codenarc.xml`); there is no inferred Groovy
formatter, so it is lint-only.

## 13. Test coverage, selection, and mutation - [`code-coverage`](demo-19-code-coverage/README.md), [`test-selection`](demo-20-test-selection/README.md), [`pitest`](demo-21-pitest/README.md)

`code-coverage` adds the first *test observation* engine: JaCoCo. Pass
`-Djenesis.observe.jacoco=true` and the test run is wrapped so the JaCoCo agent
records which lines the tests exercise; a report step then renders an HTML and
XML coverage report from that data, the compiled classes, and the sources.

The new idea is **inferred test observation**. An `InferredTestObservationModule`
sits where the plain test module used to, and an observation engine wraps the test
run by attaching a JVM agent. With coverage off (the default) it is a plain test
run; with it on, the agent instruments the run with no change to the sources, and
JaCoCo resolves its own tooling in a `jacoco` group, separate from the project's
dependencies.

A second observation engine plugs into the same slot: `-Djenesis.observe.native=true`
attaches the **GraalVM native-image tracing agent**, which records the reflection,
JNI, resource and proxy use the tests exercise and stages it as reachability
metadata under `nativeimage/` (a build-internal capture, deliberately not a `reports/`
report). The ahead-of-time `native-image` build (section 25) picks that capture up
automatically - routed from the test module to the image it tests through the build's
inventory - so it resolves dynamic access its closed-world analysis cannot see on its
own, with no committed file in between. It needs a GraalVM JDK (the agent ships in the
GraalVM runtime), so like `native-image` it is a local exercise.

`test-selection` is the testing demo's other half: a feedback-loop optimisation
rather than an observation. With `-Djenesis.test.incremental` (or a named digest), most useful under
`-Djenesis.project.watch=true`, a module that changed re-runs only the test classes
a change can reach: the test step diffs a per-class bytecode snapshot against the
previous run and walks a class-to-test dependency graph, leaving every unaffected
test cached. It is a development convenience, not a correctness gate, so continuous
integration keeps running the whole suite with selection off.

`pitest` turns the question around: instead of measuring how much code the tests
touch, it measures how much they actually *check*. It is discovered like the lint
tools, from a `pitest.properties` config file in the module: when present, the
build resolves PIT and its JUnit 5 plugin, seeds faults into the code under test
(a `+` becomes a `-`, a return value is replaced), re-runs the tests against each
mutant, and reports which survived. A surviving mutant is a behaviour no test
pins down. The version of PIT's JUnit plugin is taken from the project's own
resolved `junit-platform`, so it always matches the test framework in use.

## 14. Excluding a transitive dependency - [`maven-exclusions`](demo-22-maven-exclusions/README.md)

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

## 15. Choosing the pure modular layout - [`module-layout`](demo-23-module-layout/README.md)

`module-layout` is the same shape of project as `demo-02-java-modular` - a
`module-info.java` requiring a named module - but a `jenesis.properties` at its
root selects the layout: `jenesis.project.layout=modular`. The launcher loads that
file before the build, so the stock `java build/jenesis/Project.java` picks the
pure MODULAR layout with no custom launcher (the command-line
`-Djenesis.project.layout=modular` wins over it, and an in-code build can call
`new Project().layout(Project.Layout.MODULAR)`).

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

## 16. Selecting a classified module variant - [`module-classifier`](demo-24-module-classifier/README.md)

Some artifacts publish **classified variants** beside their default jar - the same
module name, different bytes, distinguished by what Maven calls a *classifier*.
`module-classifier` builds on the MODULAR layout from the previous demo and selects
such a variant through the pin value alone, with a leading-colon qualifier
`:<classifier>[:<version>]`:

    @jenesis.pin mutiny.zero :jdk-flow:0.4.3 SHA-256/0556f076191921250e5c9e21b9674d252bf2c4c515491e087fec93f383292b17

The pin stays keyed by the bare module name - the classifier is part of the value,
never the coordinate - so it applies wherever the module appears in the closure,
and only one variant of a module name can be selected, mirroring the module path's
own uniqueness rule. Per-machine selection composes on top as plain data: a pin
line ending in a bracketed guard (`[windows]`, `[linux,aarch64]`) applies only when
its tokens are all contained in the active platform - the detected OS and chipset
plus any token a `-Djenesis.platform.<token>=true` flag adds - with the unguarded
line as the fallback. Every variant stays committed in source, so the build remains
reproducible from the repository alone.

The demo pins the `jdk-flow` variant of `mutiny.zero`, a deliberately
OS-independent classifier: it exposes `java.util.concurrent.Flow` types where the
default jar exposes `org.reactivestreams` ones. That makes the selection observable
three times over, on any machine: the sample only compiles against the classified
API, the pinned checksum only matches the classified bytes, and the entry point
asserts at runtime that `mutiny.zero` requires nothing but `java.base` (the default
jar carries `requires transitive org.reactivestreams`). Run
`java build/jenesis/Execute.java` to build and launch it.

## 17. Selecting a variant per platform - [`platform-guard`](demo-25-platform-guard/README.md), [`platform-guard-pom`](demo-26-platform-guard-pom/README.md)

The previous demo committed one classified variant explicitly; `platform-guard`
declares several and lets the build pick. A `@jenesis.pin` line may end with a
bracketed **guard**, matched against the active platform: the detected OS and
chipset (e.g. `linux,x86_64`) plus any token a `-Djenesis.platform.<token>=true`
system property adds on top:

    @jenesis.pin mutiny.zero 1.1.1 SHA-256/2ba03737...
    @jenesis.pin mutiny.zero :jdk-flow:0.4.3 SHA-256/0556f076... [legacy]

A guard matches when all of its tokens are contained in the active set; the most
specific match wins, the unguarded line is the fallback, and equally specific
distinct matches fail the build. Tokens are free-form - a real project guards
with `[windows]` or `[macos,aarch64]`, while this demo uses the neutral token
`legacy` so the differential selection is observable on any machine: the default
build resolves `mutiny.zero` 1.1.1, and
`java -Djenesis.platform.legacy=true build/jenesis/Execute.java` adds the `legacy`
token so the guarded `jdk-flow` variant of 0.4.3 is resolved instead, with the
entry point printing which variant is on the module path. The platform is a field
of the manifests step, so adding a flag invalidates exactly the affected selection
- no `target/` cleanup needed - and every variant stays committed with its own
checksum, so the build remains reproducible from the repository alone on every
platform.

`platform-guard-pom` shows the same guards in a Maven project: pin lines in the
`pom.xml`'s `<!--jenesis.pin ... -->` comment block take the identical bracketed
guard, here switching which `commons-lang3` **transitive** the declared
`commons-text` resolves against. Versions.properties acts as a bill of materials
at any closure depth, but two Maven boundaries apply: a directly declared
`<version>` wins over the bill of materials (guard transitives, or do not declare
the version directly), and a guard can never force in a classifier, since Maven
dependency management matches classifiers as part of the coordinate key rather
than contributing them. Re-pinning obeys the guard on both layouts: only the line
that matched the local platform is refreshed, every other line is preserved
byte-for-byte, and a guarded key stays in the comment block rather than migrating
into `<dependencyManagement>`.

## 18. Customizing the build - [`custom-assembler`](demo-27-custom-assembler/README.md)

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

[`custom-jmod`](demo-28-custom-jmod/README.md) is a sibling example of the same wrapping technique, applied to a
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

## 19. Preprocessing in a reusable build module - [`internal-module`](demo-29-internal-module/README.md), [`external-module`](demo-30-external-module/README.md)

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

## 20. Driving the build without `Project` - [`custom-maven`](demo-31-custom-maven/README.md), [`custom-modular`](demo-32-custom-modular/README.md)

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

## 21. Dropping the template entirely - [`custom-build`](demo-33-custom-build/README.md)

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

## 22. Confining the build with Docker - [`docker-isolation`](demo-34-docker-isolation/README.md)

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

## 23. Supply-chain security - [`supply-chain-security`](demo-35-supply-chain-security/README.md)

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

## 24. Publishing to Maven Central - [`publishing`](demo-36-publishing/README.md)

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

## 25. Ahead-of-time native image - [`native-image`](demo-37-native-image/README.md)

`native-image` revisits the runnable-artifact idea from section 4 from the other
end. There, `jpackage` bundled your bytecode with a `jlink`-trimmed JVM; here
GraalVM `native-image` compiles the program *and* the runtime it touches into a
single standalone machine-code binary - no JVM, near-instant startup, a few
megabytes. The project is the same minimal modular shape as `java-modular`, and the
entry point is the same `@jenesis.main` field jpackage keyed off; only the back end
differs.

It is opt-in through one boolean, `-Djenesis.java.native=true`, which wires a
per-module `native-image` step (skipping modules with no main class). The step
reuses the launcher coordinates the build already derived - the produced module
path and `--module <module>/<main-class>` - and runs `native-image --no-fallback`
over them. `native-image` is located like every external tool: `GRAALVM_HOME`, then
the running JDK's `bin/`, then `PATH`, so the build runs on a GraalVM JDK or with
`GRAALVM_HOME` pointed at one.

The new idea is **closed-world ahead-of-time compilation**, and its catch:
`native-image` only sees code reached statically, so anything dynamic (reflection,
JNI, resources, proxies) needs reachability metadata. The demo walks the whole loop:
`Sample` loads its greeter by a name assembled at run time, so the binary needs
metadata; `-Djenesis.observe.native=true` runs the tracing agent during the test
(the second observation engine from section 13) and stages what it records under
`nativeimage/`. Because packaging runs in a cross-module *package* phase after every
module builds, the `native-image` step picks that capture up automatically - routed
from the test module to the image it tests - so one build produces the working binary
with no committed `META-INF/native-image/`. The `stage` goal then collects the
executable into `stage/native/output/`, the native-image analogue of `stage/packages`
and `stage/runtime`. Build without `-Djenesis.observe.native=true` and nothing is
captured, so the binary fails at run time with `ClassNotFoundException`; committing the
metadata into `sources/META-INF/native-image/` stays the way to vet exactly what a
published artifact bakes in. native-image is an *alternative* to jpackage, not a
successor: jpackage for a faithful bundle of the JVM you tested against, native-image
when startup latency and footprint dominate. Like `docker-isolation`, it needs tooling
the CI runners lack (GraalVM), so it is a local exercise.

## 26. A shared build cache - [`build-cache`](demo-38-build-cache/README.md)

Every build already caches incrementally under `target/`: each step is
content-hashed on its inputs and outputs, so a warm rebuild only re-runs what
changed. `build-cache` adds the second tier - a **shared cache** outside
`target/`, switched on with `-Djenesis.executor.cache=<folder>`, that hands a
step its finished output instead of running it. The folder is content-addressed
(`<folder>/<step-hash>/<inputs-hash>/`): the step hash identifies the step from
its serialized form, the inputs hash folds every input file's content hash, and a
hit materializes the cached output by hard link, so reads are near free while the
step body is skipped entirely.

The demo makes the second tier visible with `-Djenesis.executor.rebuild=true`,
which deletes `target/` before building so the *incremental* cache is gone and
every step is a forced miss. The shared cache lives outside `target/`, so a first
build bootstraps it and a second `rebuild` is served from it - the compile,
class, and jar steps return in ~0.00s, marked `[EXECUTED]` because the output was
produced, just not by `javac`. An optional `cache.properties` at the cache root
tunes the writes (`steps`/`versions` caps with `lru` eviction, `touch`,
`frozen` for read-only consumers, `compressed` for a packed zip layout, and
`disabled`), all defaulted so the file is optional. `BuildExecutorFileCache` is
one implementation of the pluggable `BuildExecutorCache` seam: a remote
HTTP/object-store backend is another implementation of the same `fetch`/`store`
interface, and the local folder is the on-disk analogue - point several checkouts
or a CI workspace and a laptop at one folder and a step compiled once is reused
wherever its inputs match.

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
in `pom.xml`) can also be launched from its built artifacts with the `Execute`
launcher (`java build/jenesis/Execute.java`, which builds then runs that entry
point; `-Djenesis.execute.module=<module>` / `-Djenesis.execute.mainClass=<fqcn>`
pick which module and main to launch in a multi-module build), packaged into a
native application image with `-Djenesis.java.jpackage` (the value is the `jpackage
--type`; the image is collected under `stage/packages/` next to `stage/maven` and
`stage/modular`; see section 4), packed into a `.jmod` and linked into a trimmed
`jlink` runtime image (`-Djenesis.java.jmod` / `-Djenesis.java.jlink`, staged under
`stage/runtime`), zipped jars-only for a JRE base (`-Djenesis.java.bundle`), shaded with
the `build.jenesis.launcher` into a single `java -jar`-able executable jar that keeps the
module graph (`-Djenesis.java.launcher`), or compiled ahead of time into a standalone
GraalVM native binary with `-Djenesis.java.native=true` (see section 23).

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
inheriting compile), and a *resolved compiler* lives in its own group (`kotlinc`,
`scalac`, `groovyc`), so it never mixes with the project's own dependencies.

Current pin state of the demos: every demo is committed pinned with checksums.
`java-pom`, `java-pom-multi`, `java-modular`, and `java-modular-multi` pin their
dependency closures (per module, so a dependency of one module never lands in a
sibling's dependency management). The MODULAR_TO_MAVEN language demos - `kotlin`,
`scala`, and `groovy` - pin both their library dependency (in the `main` group)
and
their compiler toolchain in its own group (`kotlinc`, `scalac`,
`groovyc`), each `@jenesis.pin` tag carrying a version and SHA-256 checksum.
`groovy` pins its `org.apache.groovy` library to the stable `5.0.6` and its
`groovyc`-group compiler to `6.0.0-alpha-1`; `kotlin` and `scala` track whatever
their compilers resolve (for `scala` that is often a release candidate).
