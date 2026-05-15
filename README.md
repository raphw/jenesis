Jenesis
=======

Getting started
---------------

Jenesis is a build tool for Java projects, written and configured in Java itself. It needs no installation, wrapper
script, or precompiled binary: the tool's own sources sit next to your project's (typically as a Git submodule) and
run directly via the JVM's single-file launcher. A build is fully reproducible from a clone of the project plus a
JDK.

The fastest way to use it is the canonical entry point, `build.jenesis.Project`. From a project root that contains
a `module-info.java` (modular) or a `pom.xml` (Maven), run:

    java build/jenesis/Project.java

That is the whole invocation. `Project` auto-detects the project shape, wires the corresponding pipeline (compile,
package, optionally test), and runs the discovered multi-project graph. Jenesis itself is built this way: clone
this repository and run the command above from its root.

### Customizing the build

`Project` exposes a fluent `Builder` so the defaults can be overridden in code:

```java
Project.builder()
       .layout(Project.Layout.MODULAR_TO_MAVEN)
       .hashAlgorithm("SHA512")
       .tests(false)
       .build(args);
```

Most builder properties also have a corresponding system property (`jenesis.project.layout`,
`jenesis.project.hashAlgorithm`, `jenesis.project.skipTests`, `jenesis.project.stageTests`,
`jenesis.project.root` / `.target` / `.cache`), applied by `resolveProperties()` which `Project.main(...)`
calls before delegating to `build(args)`. See the [Configuration](#configuration) section for the complete table.

### Building other projects

To use Jenesis to build a project of your own, add this repository as a Git submodule and expose its
`build.jenesis` package under your project's `build/` folder so the launcher can find `Project.java`. The convention
this repository uses is a single symlink, so the same `java build/jenesis/Project.java` invocation works from any
project root:

    git submodule add https://github.com/raphw/jenesis.git .jenesis
    ln -s ../.jenesis/sources/build/jenesis build/jenesis

The pinned submodule commit gives reproducibility: a clone of your project plus `git submodule update --init` is
the entire setup, no Jenesis installation required. From there, the same auto-detection applies to your project's
own `module-info.java` or `pom.xml`:

    java build/jenesis/Project.java

On platforms without symlink support, replace the `ln -s` with a one-time copy
(`cp -r .jenesis/sources/build/jenesis build/jenesis`) and refresh it after each submodule update.

You can also build a project that lives somewhere else without setting up the submodule layout inside it. Use the
`jenesis.project.root` system property to point at the project root, while keeping the launcher resolved against
the Jenesis checkout itself:

    java -Djenesis.project.root=/path/to/other/project .jenesis/sources/build/jenesis/Project.java

The same applies to `jenesis.project.target` and `jenesis.project.cache` if you want their on-disk locations
separated from the project tree as well.

Other distribution methods (a published jar, a wrapper script, a CI-friendly bootstrap) will follow.

### Selectors

`Project.main(args)` and `Builder.build(args)` accept selectors as positional arguments. With no positional
arguments the builder's `defaultTarget` is used (default: `"build"`, which runs the discovered multi-project graph
but skips the downstream `collect` step). `Builder.defaultTarget(...)` changes what an argument-less invocation
runs; it has no corresponding system property.

Selectors starting with `+` are rewritten into per-project module paths via a name resolver supplied by the active
layout. The shipped layouts encode names as `module-<URLEncode(name)>` and place them under their per-project
aggregator:

- `+sources` resolves to `build/modules/compose/module/module-sources` under `MODULAR` and `MODULAR_TO_MAVEN`, or
  to `build/maven/compose/module/module-sources` under `MAVEN`.
- `+` alone resolves to `module-` (trailing empty segment), the identity Maven's scanner produces for the root
  POM (the "unnamed" project in a multi-module Maven layout). A pure modular project has no such root, so `+`
  alone won't resolve there.

The resulting path is a literal selector, which avoids the lenient `::/<name>` cascade across sibling modules. Run
`java build/jenesis/Project.java +sources` to build one module without dragging its siblings in. The full selector
syntax (`/`, `:`, `::`, literal paths) is described under [Selectors on the command line](#selectors-on-the-command-line).

### Layouts and assemblers

Two callbacks govern how the build is assembled, and they are pluggable independently:

- `Project.Layout` (set via `.layout(...)`) wires the top-level pipeline (the `download` step where applicable, the
  `build` multi-project module, the `collect` artifact relocation) and returns the `Function<String, String>` that
  expands `+`-prefixed selectors. The shipped constants `Layout.MAVEN`, `Layout.MODULAR`,
  `Layout.MODULAR_TO_MAVEN` mirror `build/Maven.java`, `build/Modular.java`, and `build/ModularToMaven.java`.
  `Layout.AUTO` (the default) calls `Layout.of(root)` and dispatches to one of the concrete layouts;
  `MODULAR_TO_MAVEN` is reachable only explicitly, because its on-disk signature (`module-info.java` +
  `pom.xml`) is indistinguishable from a pure modular project that keeps a `pom.xml` for IDE support.
- `Project.Assembler` (set via `.assembler(...)`) wires the per-project sub-graph: what each discovered module
  compiles, packages, and tests. An assembler receives a `Project.Context` (`tests`, `hashAlgorithm`, effective
  `repositories`, effective `resolvers`) and a `ModuleDescriptor` (with canonical sub-paths `sources()`,
  `manifests()`, `artifacts()`, `runtimeArtifacts()`, `checked()`, `runtimeChecked()`), and returns a
  `BuildExecutorModule` registered inside the per-project sub-graph. The default, `Assembler.ofJava()`, is
  layout-independent: it wires a single `JavaModule.testIfAvailable(...)` against all six descriptor paths,
  using whatever repositories and resolvers the layout has provided. The `MAVEN` and `MODULAR_TO_MAVEN`
  layouts wrap the user's assembler with a `PomAwareAssembler` that emits a per-project `pom` step seeded
  with project-wide metadata read once from `metadata.properties` (when configured); `MODULAR` does not.

Layouts always combine their built-in repositories and resolvers (e.g. a Maven default for `MAVEN`, the URI-derived
module repository for `MODULAR`) with any user-provided ones, and pass the merged maps through `Context`. User
entries with the same key override the layout default.

| Layout               | Pipeline                                                                                  | Mirrors                |
| -------------------- | ----------------------------------------------------------------------------------------- | ---------------------- |
| `Layout.MAVEN`       | **Input: `pom.xml`. Output: classic JAR + `pom.xml`.** `MavenProject` scan + per-project `JavaModule` + per-module `Pom` step + `Relocate` artifacts | `build/Maven.java`     |
| `Layout.MODULAR`     | **Input: `module-info.java`. Output: modular JAR (no `pom.xml`).** `DownloadModuleUris` + `ModularProject` over a URI-derived repository + per-project `JavaModule` | `build/Modular.java`   |
| `Layout.MODULAR_TO_MAVEN` | **Input: `module-info.java`. Output: modular JAR + `pom.xml`.** `DownloadModuleUris` + `ModularProject` against a `MavenDefaultRepository` (`MavenPomResolver` translated through `MavenUriParser`), with a per-module `Pom` step on top of the assembler | `build/ModularToMaven.java` |
| `Layout.AUTO` (default) | Detection: a root `pom.xml` → `MAVEN`; else any `module-info.java` under the root → `MODULAR`. Trees rooted at a nested `.jenesis.build` marker are skipped. Falling through throws. | - |

All three concrete layouts share the same `collect` step (groups each module's artifacts by module name)
and a `stage` step that materializes the staged tree under `target/stage/output/`. The stage placement
differs by layout:

- `MAVEN` and `MODULAR_TO_MAVEN` use `MavenRepositoryStage`, which produces `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>.<ext>`
  (suitable for upload to a Maven repository) and additionally merges any test-variant dependencies
  into the main POM with `<scope>test</scope>` while routing test JARs onto the main coordinate with a
  `-tests` classifier.
- `MODULAR` uses `ModularPlacement`: `<module>/<module>.jar` (plus `-sources.jar` / `-javadoc.jar` siblings
  when those flags are set). When `jenesis.buildVersion` is set, the version is inserted as one extra path
  segment: `<module>/<version>/<module>.jar`. There is no `pom.xml` to anchor a Maven coordinate.

Run `java build/jenesis/Project.java stage` to materialize that tree (it's the canonical entry point for
release publishing - see [The stage step](#the-stage-step) for the full release pipeline).

The example scripts (`Minimal`, `Manual`, `Maven`, `Modular`, `ModularToMaven`, `Modules`) under `build/`
illustrate the underlying primitives that `Project` composes; they are not part of the canonical surface.

### Running inside Docker

Set `-Djenesis.project.docker=true` to run the entire build inside a throwaway container instead of directly on
the host JVM:

    java -Djenesis.project.docker=true build/jenesis/Project.java

A minimal image is built on demand the first time and cached for subsequent runs. To target a different image,
add `-Djenesis.project.docker.image=<reference>`.

Architecture
------------

The lowest primitive is a `BuildStep`, a single unit of work that reads from a set of input folders and writes
into a fresh output folder. It is a functional interface:

```java
CompletionStage<BuildStepResult> apply(Executor executor,
                                       BuildStepContext context,
                                       SequencedMap<String, BuildStepArgument> arguments);
```

Each invocation is handed a `BuildStepContext` and a map of predecessor outputs. The context holds three folder
slots:

- `next`: the folder this invocation writes into. It is created fresh for every run; the step never modifies any
  other folder.
- `previous`: the same step's output folder from the prior run, or `null` on a first run. A step can read it to
  decide what to copy or hard-link instead of regenerating, but it must not write into it.
- `supplement`: scratch space tied to the step's lifetime, available for intermediate files the step doesn't want
  to publish in `next`.

The `arguments` map carries one `BuildStepArgument` per registered predecessor. Each argument exposes the folder to
read from (`argument.folder()`) and a per-file checksum status (`ADDED`, `ALTERED`, `REMOVED`, `RETAINED`) computed
against the previous run. The default `shouldRun(...)` re-runs the step when any input has changed; a step can
override it to express finer-grained dependencies (e.g. `Bind` only re-runs when files matching its bound paths
changed, `Relocate` only when files under its declared prefixes changed).

Steps are organised into a graph by `BuildExecutor`:

- `addSource(name, path)` registers an external folder as an input.
- `addStep(name, BuildStep, predecessors…)` adds a step whose `arguments` will be populated from the named
  predecessors. Predecessors are addressed by their registered names; cross-module references use the `../` prefix
  (`BuildExecutorModule.PREVIOUS`) to climb out of the current sub-graph.
- `execute(selectors…)` runs the graph on a virtual-thread executor, scheduling each node as soon as its
  predecessors have completed. With no selectors, the full graph runs. Otherwise each selector is a slash-delimited
  path of identities (`module/step`) that restricts execution to the named steps and their preliminaries; `:`
  matches any one path segment and `::` matches any depth (zero or more). Wildcards are lenient - branches without
  a match are silently skipped - while a literal path that doesn't resolve throws.

  Once a step is matched, its **transitive preliminary closure** runs unconditionally (no further selector filtering)
  so its inputs are real folders, not lenient-skipped placeholders. Modules along the path are different from steps
  here: a module's `accept(...)` always runs (modules aren't cached), and `accept` is allowed to read its predecessor
  folders to wire its sub-graph dynamically. So whenever a module is reached by any selector - including via lenient
  `::` propagation - its step preliminaries are pinned and run normally (cache-checked but not lenient-skipped),
  guaranteeing those folders exist when `accept` reads them. Sibling modules whose subtree contains no match still
  have their `accept` invoked and their declared step preliminaries run; the engine can't determine "no match here"
  without descending, since module substructure is registered by `accept` itself. In practice this is a hash check
  per preliminary on a warm cache. If you know the path you want, prefer literal selectors (`module/step`) over
  `::/leaf` to avoid that residual work on unrelated subtrees.

A `BuildExecutorModule` is a sub-graph factory, also a functional interface, with
`accept(BuildExecutor, inherited)` populating a nested `BuildExecutor` with its own steps and (transitively) its
own sub-modules. The `inherited` map exposes the predecessor folders the parent passed in, addressed under their
`../`-prefixed identifiers. Modules can rename their published outputs by overriding `resolve(...)`. Composing
steps into modules turns commonly-recurring patterns (compile + jar + test, resolve + checksum + download, scan a
multi-project tree, …) into reusable units that take only their inputs as configuration.

Unlike steps, modules are not cached: `accept(...)` runs on every build to (re-)register its sub-graph, and only
the registered steps are then content-hashed and considered for skipping. Logic that lives inside the module body
itself - file scans, classpath assembly, conditional step wiring - therefore executes unconditionally on every
run; wrap it in a step if you need it skipped on unchanged inputs.

Three properties of the model give incremental builds and reproducibility for free:

- **Each step's output folder is immutable once produced.** A step only ever writes into its own `next`; downstream
  steps see predecessor outputs as read-only inputs. There is no shared mutable state, so a step's result is a pure
  function of its inputs.
- **Inputs and outputs are content-hashed.** Every output folder is checksummed when the step finishes; on the next
  run, those checksums become the predecessors' input checksums. If they all match and `shouldRun(...)` returns
  `false`, the step's previous output is reused unchanged. Anywhere along the chain that the hashes diverge (a
  source edit, an upstream re-run, a different dependency), the affected step (and only the affected step) is
  re-executed into a fresh `next` folder, which transparently replaces its predecessor.
- **Each step's configuration is content-hashed too.** `BuildStep extends Serializable`, and a
  `BuildStepHashFunction` digests the step's serialized form alongside the output checksums (in
  `<step>/checksum/step`). When a step is reconstructed with different field values - a different `Jar.Sort`,
  a different `Resolver`, a different placement function - its hash changes and the step re-runs even if its
  inputs are unchanged. Configuration that should *not* count as part of the build's identity (a `Repository` that
  by contract returns the same artifact for the same coordinate, a JDK service factory, a `MavenPomEmitter`) is
  marked `transient` so it never reaches the digest. Lambdas held by step fields use intersection bounds
  (`<T extends Function<…> & Serializable>`) at the constructor so the compiler generates them serializable. The
  hash stream also installs a `replaceObject` hook that substitutes any `java.nio.file.Path` for its `toString()`,
  making `Path`-typed step fields a first-class part of the configuration hash by design - the JDK's `Path`
  interface is not declared `Serializable`, so without this substitution any step that held a `Path` would fail
  serialization. Steps that still hold genuinely non-serializable state throw `NotSerializableException` at hash
  time so the bug surfaces at the first run instead of silently breaking cache invalidation.

The executor places a `.jenesis.build` marker at the build root so source scanners (`MavenProject`,
`ModularProject`) can skip nested builds, stores all per-step state under `target/`, and uses `cache/` by
convention for cross-build caches such as downloaded module URIs.

### Best practice: communicate through file/folder conventions, not step names

A step or module should treat its `inherited` map as an opaque set of **input folders** and discover what to
read by looking for files and folders at well-known relative paths inside each input. It should not pattern-match
on the keys themselves to infer which predecessor an input came from. The same applies to its outputs: a step
writes file and folder layouts that downstream consumers look up by name, never expecting the consumer to know
how the step was wired.

Concretely:

- **Don't filter `inherited.sequencedKeySet()` by step-name patterns.** If a module needs to distinguish two
  categories of inputs (e.g. compile-side vs. runtime-side), let the caller wire each category to a distinct
  predecessor or pass an explicit predicate; don't have the module sniff `key.split("/").contains("runtime")` to
  guess.
- **Don't compose `inherited` keys with extra `BuildExecutorModule.PREVIOUS` (`../`) prefixes** to chase a
  predecessor that lives one level higher than the descriptor states. Instead, do the lookup at the level where
  the descriptor's path strings apply directly (typically the outer assembler lambda) and capture the result for
  any inner sub-module that needs it.
- **Define each step-name constant once, at the class that adds the step**, and have all consumers reference
  that constant. `MultiProjectModule.SOURCES` / `.MANIFESTS` belong on `MultiProjectModule` because that's the
  framework that wires those steps in the per-module sub-graph; `DependenciesModule.CHECKED` / `.ARTIFACTS`
  belong on `DependenciesModule` because that's where the steps are added; `MultiProjectModule.COMPILE` /
  `.RUNTIME` live on `MultiProjectModule` because that's where the per-scope sub-modules are wired. A class that
  wants to point at a predecessor's leaf step uses the owner's constant - no separate "same string" duplicate.
- **`*.properties` files exchanged between steps in different files should have a documented schema.** The
  conventional files (`identity.properties`, `module.properties`, `metadata.properties`, `requires.properties`,
  `versions.properties`, `scopes.properties`) are listed in the table below with their produced/consumed keys
  and value semantics. The filenames live as constants on `BuildStep`; each property key's contract belongs in
  the README rather than as a magic string scattered across writer and reader sites.
- **Schema-level vocabulary in those properties files is matched as literal strings, not via a shared constant.**
  The values written to `scopes.properties` (e.g. `compile`, `runtime`) are an open-ended token set documented in
  the table below; new steps and producers are free to introduce additional tokens without touching a shared enum
  or constant holder. Producers that happen to align their wiring with the same names (e.g. `MavenProject` writing
  `MultiProjectModule.COMPILE` because that is the sub-module name it is wiring under) do so as a coincidence of
  layout, not as the source of truth for the token. Consumers (`Pom`, `MultiProjectDependencies`) match against
  the documented schema strings directly so they remain decoupled from the wiring framework that produced the file.

The exception is **inline sub-modules of the same enclosing module**: a class that adds several sub-modules and
steps in its own `accept(...)` may reference its own sub-module/step names by their (private) constants, since
the wiring lives in one file and never crosses the module boundary. `ExternalModule`'s references to its inner
`EXTERNAL`, `DEPENDENCIES`, `DELEGATE` step names; `MavenProject`'s references to its private `MODULE`,
`DEPENDENCIES`, `PREPARE` constants; and `MultiProjectModule`'s references to its `IDENTIFIER`, `COMPOSE`,
`MODULE`, `GROUP` sub-module names are all of this shape.

Conventional folders and files
------------------------------

Every step writes its output into `context.next()`. The conventions below define the names a step uses for the
artifacts it produces and the names downstream steps look for. The canonical names are constants on `BuildStep`;
others are declared next to the step that emits them.

| Path                       | Constant                         | Purpose                                                                                         |
| -------------------------- | -------------------------------- | ----------------------------------------------------------------------------------------------- |
| `sources/`                 | `BuildStep.SOURCES`              | A directory tree of `.java` source files (mirroring their package structure) consumed by compilation and documentation tooling, and packaged as-is into source jars.                                                                                  |
| `resources/`               | `BuildStep.RESOURCES`            | A directory tree of non-source files (configuration, message bundles, static assets) that should appear on the classpath alongside compiled classes and be embedded into produced jars.                                                              |
| `classes/`                 | `BuildStep.CLASSES`              | A directory tree of compiled `.class` files in their package layout, plus any non-source companion files copied verbatim from `sources/`. Forms a class- or module-path entry for downstream compilation, packaging and execution.                  |
| `artifacts/`               | `BuildStep.ARTIFACTS`            | A flat directory of jar files, either freshly produced as a packaging output or downloaded from an external repository. Each file forms a class- or module-path entry for downstream compilation and execution.                                     |
| `javadoc/`                 | `Javadoc.JAVADOC`                | A generated Javadoc tree (HTML, CSS and supporting resources), ready to be archived into a documentation jar or served as static content.                                                                                                            |
| `groups/`                  | `Group.GROUPS`                   | One `<encoded-group-name>.properties` file per identified group, listing the other groups whose coordinates the group transitively depends on so cross-project wiring can be derived purely from on-disk state.                                      |
| `pom/`                     | `MavenProject.POM`               | A mirror of the directory layout of a Maven multi-module project, with each `pom.xml` hard-linked from its original location to give downstream tooling a stable, sandboxed snapshot of the project's POM tree.                                      |
| `maven/`                   | `MavenProject.MAVEN`             | One properties file per discovered Maven module (`module-<encoded-path>.properties` for the main artifact, `test-module-<encoded-path>.properties` for the test artifact), holding the parsed coordinate, source/resource directories, packaging and dependency list extracted from a single `pom.xml`. |
| `identity.properties`      | `BuildStep.IDENTITY`             | `<prefix>/<coordinate>` keys (e.g. `maven/groupId/artifactId/[type/[classifier/]]version` or `module/<java-module-name>`) mapped to either an empty value (artifact not yet built; identifies the project's own coordinate) or the absolute filesystem path of an already-built jar.                          |
| `requires.properties`      | `BuildStep.REQUIRES`             | Same `<prefix>/<coordinate>` keys as `identity.properties`, mapped to either an empty value (still to be resolved or hashed) or an `<algorithm>/<hex>` content checksum that downstream consumers verify against the downloaded artifact. After `Resolve` runs, module-style coordinates carry an optional trailing `/<version>` segment (`module/org.junit.jupiter/5.11.3`) reflecting the version a resolver chose for that module.                                                                                                                                                            |
| `versions.properties`      | `BuildStep.VERSIONS`             | `<prefix>/<version-less-coordinate>=<version>` entries that act as a *bill of materials* for the resolution that follows: every resolver receives this map alongside `requires.properties` and uses it to pin the version of any (declared or transitive) dependency that matches. For Maven the key is `groupId/artifactId[/type[/classifier]]`; for modules it is the bare Java module name. The file is written next to `requires.properties` by producers that have version data to contribute (`ModularProject` from Javadoc tags, `MavenProject` from `<dependencyManagement>`); only `Resolve` consumes it, so it does not need to be propagated through `Checksum`/`Download`. |
| `scopes.properties`        | `BuildStep.SCOPES`               | Sibling of `requires.properties` produced by the `Manifests` steps in `ModularProject` and `MavenProject`. Each key is a `<prefix>/<coordinate>` from `requires.properties`; the value is a comma-separated list of scope tokens describing in which scopes the dependency is visible. The token set is open-ended (matched as literal strings, not via a shared enum) so additional steps can introduce their own scope tokens later. The currently recognized tokens are `compile` and `runtime`: compile-only entries (Maven `provided`, Java `requires static`) carry just `compile`; runtime-only entries (Maven `runtime`) carry just `runtime`; entries visible in both carry `compile,runtime`. `MultiProjectDependencies` filters `requires.properties` against the scope it is bound to; `Pom` reads it to decide whether each dependency is emitted as `compile`, `provided`, or `runtime`. The strings written by today's producers happen to equal `MultiProjectModule.COMPILE` / `RUNTIME` because those are the sub-module names the per-scope wiring uses, but consumers match the documented tokens directly. |
| `module.properties`        | `BuildStep.MODULE`               | Carries derived classification keys about a built module that affect downstream build/staging decisions (as opposed to the descriptive `metadata.properties`). Currently the only key is `tests`, whose value is the `artifactId` of the main module the test variant covers (or the empty string for the deprecated bare `@tests`); the file is omitted entirely on main modules. Written by `ModularProject.Manifests` (from `@tests <name>` in `module-info.java`) and `MavenProject.Manifests` (when the resolved coordinate carries the `tests` classifier). Read by `Pom`, `Project.Assembler.ofJava`, `MavenRepositoryStage`, `MavenRepositoryPlacement`, and `ModularPlacement` to identify test variants. |
| `uris.properties`          | `DownloadModuleUris.URIS`        | `<prefix>/<java-module-name>` keys mapped to an absolute jar URL; populated from line-based `<module>=<url>` registries (default: sormuras/modules) and used during dependency resolution to translate a Java module name into a download URL. When a versioned coordinate is requested (e.g. `org.assertj.core/3.27.0`) and the bare name is mapped to a URL whose final path segments follow the Maven repository layout (`.../<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<ext>`), an opt-in version-resolver function (`MavenDefaultRepository.versionResolver()`) supplied by the caller rewrites the path's version segment and the filename's version segment to the pinned value, so a single-URL registry still satisfies version pins. Without that function, `Repository.ofUris` performs strict literal lookup only; URLs not matching the Maven layout always fall back to the bare-name URL. The `MODULAR` layout passes this resolver explicitly when wiring `Repository.ofProperties`, since the dominant Java module URL registries (sormuras/modules and most internal mirrors) point at Maven Central -- making the Maven layout assumption visible at the use site rather than baked into the generic `Repository` infrastructure. |
| `process/<command>.properties` | `ProcessBuildStep.PROCESS` (folder)  | Command-line fragments contributed to a downstream `ProcessBuildStep` whose tool name matches `<command>` (`java`, `javac`, `jar`, `javadoc`). Keys are flags (e.g. `--add-modules`); values are flag values, with literal `\n` inside a value emitting the same flag once per piece. Each input folder's file is processed independently and its entries are appended to the command line in folder order, so the same key in two folders becomes two flag instances. Values that name filesystem paths are written relative to the file's containing folder (paths are not resolved until the consumer step needs them), which keeps the on-disk content position-independent so build outputs can be relocated or shared between caches without rewriting.                                                                                          |
| `pom.xml`                  | `Pom.POM`                        | A generated Maven Project Object Model, ready to be packaged alongside a built jar so the artifact can be published to and consumed from any Maven-aware repository.                                                                                  |
| `target/`                  | (passed to `BuildExecutor.of`)   | The root folder under which every step's per-run output and the executor's incremental bookkeeping (output checksums and predecessor checksum snapshots used to decide whether a step needs to re-run) live. Safe to delete to force a clean build.   |
| `cache/`                   | by convention                    | A folder used for caches that outlive a single build, such as previously fetched Java module URI registries; cached entries are content-addressable and refreshed on demand by whatever produced them.                                                |
| `.jenesis.build`           | `BuildExecutor.BUILD_MARKER`     | An empty marker file placed at the root of an active build directory. Project-tree walkers honour it as a stop signal so nested builds aren't re-discovered as part of the parent build's project graph.                                              |

Build steps
-----------

The steps listed here are pre-implemented for convenience; the build tool itself does not depend on any of them, and a build is free to ignore them and supply its own `BuildStep` implementations.

| Step                       | What it does                                                                                                                                                                                   | Inputs (per predecessor folder)                                                                                                       | Outputs (under `context.next()`)                                                |
| -------------------------- |------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------| ------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `Bind`                     | Hard-links files from each predecessor into a target layout under `context.next()`, driven by a `Map<Path, Path>` that mirrors specific subtrees under canonical names (used by the static factories `asSources()`, `asResources()`, `asIdentity(...)`, `asRequires(...)`). | a source folder, a named properties file, or any other predecessor subtree named in the map                                          | `sources/`, `resources/`, `identity.properties`, `requires.properties`, or any layout produced by the configured map |
| `Relocate`                 | Walks every file under each predecessor and asks a `Function<Path, Optional<Path>>` where (if anywhere) to hard-link it under `context.next()`; can be restricted to a `Set<Path>` of subtree prefixes for path-aware `shouldRun`. | every file in the predecessors (or only those under the configured prefixes)                                                          | whatever the placement function returns                                          |
| `Javac`                    | Compiles each predecessor's `sources/` with the `javac` tool, using their `classes/` and `artifacts/` as class- or module-path entries; writes the resulting `.class` files to `classes/`.    | `sources/`, `classes/`, `artifacts/`                                                                                                  | `classes/`                                                                       |
| `Jar`                      | Packages the folders selected by the configured `Jar.Sort` into a single jar under `artifacts/`.                                                                                               | per `Jar.Sort`: `CLASSES` reads `classes/` + `resources/`; `SOURCES` reads `sources/` + `resources/`; `JAVADOC` reads `javadoc/`         | `artifacts/classes.jar`, `artifacts/sources.jar`, or `artifacts/javadoc.jar` (depending on `Jar.Sort`) |
| `Javadoc`                  | Invokes the `javadoc` tool over each predecessor's `sources/` and writes the generated documentation tree to `javadoc/`.                                                                       | `sources/`                                                                                                                            | `javadoc/`                                                                       |
| `Java`                     | Runs `java` with each predecessor's `classes/`, `resources/` and the jars in `artifacts/` assembled into a class- and module-path; the entry point and command line are supplied by subclasses or `Java.of(...)`. | `classes/`, `resources/`, `artifacts/`                                                                                                | runs `java`; no canonical output                                                 |
| `Resolve`                  | Reads `requires.properties` and (when present) `versions.properties`, asks each prefixed group's `Resolver` for the transitive closure with the version map as a pin set, and writes the resolved coordinates to a fresh `requires.properties` (module-style coordinates pick up a trailing `/<version>` segment when a version is known). | `requires.properties`, `versions.properties`                                                                                          | `requires.properties` (transitively resolved, per-prefix `Resolver`)             |
| `Checksum`                 | Reads `requires.properties`, fetches each unresolved coordinate from its `Repository`, and writes a new `requires.properties` where each empty value is replaced by `algorithm/<hex>`.        | `requires.properties`                                                                                                                 | `requires.properties` (with computed checksums)                                  |
| `Download`                 | Reads `requires.properties` and downloads each coordinate's artifact into `artifacts/`, validating against the recorded checksum where present and reusing a previous run's file when valid.  | `requires.properties`                                                                                                                 | `artifacts/<prefix>-<coordinate>.jar`, plus an empty `requires.properties`       |
| `Translate`                | Rewrites the keys of `requires.properties` (and `versions.properties` when present, with the same translator) through user-supplied per-prefix translator functions (e.g. Java module name → Maven coordinate).                                                  | `requires.properties`, `versions.properties`                                                                                          | `requires.properties`, `versions.properties` (keys remapped per-prefix)                                 |
| `Versions`                 | Walks each predecessor's `classes/`, hard-links every non-`module-info.class` file under `context.next()/classes/`, and rewrites every `module-info.class` so each `requires <X>` directive gets a `compiledVersion` set from the matching entry in the resolved `requires.properties` (module-style `<prefix>/<name>/<version>` coordinates). Uses the JDK's `java.lang.classfile` API; module flags (`OPEN`), the module's own version, `exports`, `opens`, `uses` and `provides` round-trip unchanged. | `classes/`, `requires.properties`                                                                                                     | `classes/` (non-`module-info` hard-linked, `module-info.class` rewritten in-place) |
| `Group`                    | Reads each predecessor's `identity.properties` and `requires.properties`; for each identified group, writes a `groups/<name>.properties` listing the other groups whose coordinates it depends on. | `identity.properties`, `requires.properties`                                                                                          | `groups/<encoded-name>.properties`                                               |
| `Assign`                   | Fills the empty values of `identity.properties` with paths to the jars in the predecessors' `artifacts/`, finalising the coordinate → file mapping.                                           | `identity.properties`, `artifacts/`                                                                                                   | `identity.properties` (empty values filled with artifact paths)                  |
| `DownloadModuleUris`       | Fetches the configured remote URL lists (default: the sormuras/modules registry) and concatenates them into a single `uris.properties`.                                                        | none (fetches the configured URLs)                                                                                                    | `uris.properties`                                                                |
| `MultiProjectDependencies` | Merges per-project `requires.properties` (and looks up sibling-project paths in their `identity.properties`) into one unified `requires.properties`, computing local-artifact checksums for any coordinates already built. | per-predecessor `identity.properties` or `requires.properties`, partitioned by predicate                                              | unified `requires.properties`, with checksums for resolved local artifacts       |
| `Pom`                      | Emits a Maven `pom.xml`, taking the project's own coordinate from the empty entry in `identity.properties` and its dependencies from `requires.properties` entries that share the same prefix. | `identity.properties` (self coordinate = empty value), `requires.properties`                                                          | `pom.xml`                                                                       |
| `Export`                   | Copies (and overwrites) files from each predecessor into an external target path through a `Function<Path, Optional<Path>>` placement, always re-runs (`shouldRun = true`); after copying it invokes an optional `Consumer<Path>` finalizer against the target. `MavenRepositoryPlacement.toLocalRepository()` / `toRepository(Path)` ship a Maven-layout placement that writes `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>.{jar,pom}` plus a finalizer that mirrors `mvn install` (writes `maven-metadata-local.xml` per artifact, `_remote.repositories` markers per version dir, and per-version snapshot metadata for `-SNAPSHOT` versions). | every file in the predecessors (only `classes.jar` / `pom.xml` for the Maven layout)                                                  | files copied under the configured target path; nothing is written under `context.next()` |

`ProcessBuildStep` and `Java` are abstract bases (used by `Javac`, `Jar`, `Javadoc`, and the inner `executed`
step that `TestModule` registers); `Java.of(...)` gives an ad-hoc command runner. `DependencyTransformingBuildStep` is the shared base for `Resolve`, `Checksum`,
`Download`, and `Translate`; they all parse `requires.properties` into `(prefix, coordinate)` groups, transform
them, and write `requires.properties` back.

Before launching its tool, every `ProcessBuildStep` walks each input folder for `process/<command>.properties`
(where `<command>` is the tool name supplied to the constructor - `java`, `javac`, `jar`, `javadoc`), loads each
folder's file into its own map keyed by argument, and passes the per-folder maps to `process(...)`. Whatever the
subclass leaves behind in those maps is then materialised as command-line tokens prepended to the command line
the subclass produced, in folder order: each entry becomes a `--key value` pair, and `\n` inside a value emits
the same flag once per piece (so a predecessor can write `--add-modules=foo\nbar` to repeat a flag, and the same
key contributed by two predecessors yields two flag instances).

Path-shaped values are stored as paths relative to the file's containing folder and never resolved by
`ProcessBuildStep` itself - keeping the on-disk content position-independent in the same way `requires.properties`
does for coordinates. Resolution is the consumer's job. `Java` does this in its own per-folder iteration: in
addition to scanning each predecessor's `classes/`/`resources/`/`artifacts/`, it pulls `--module-path` and
`--class-path` entries out of that folder's properties map, splits each value on `\n`, resolves each piece against
the same `argument.folder()`, and folds the result into the path lists it ultimately joins with the platform
path-separator into a single `--module-path` / `-classpath` argument. Removing the keys from the per-folder map as
it consumes them keeps `ProcessBuildStep` from also materialising them as repeated flag instances the JVM would
treat as last-wins overrides.

`Export` is the one step that intentionally breaks two of the conventions that every other step holds to. Its
job is to publish a build's outputs outside the `target/` tree (e.g. into the user's local Maven repository, a
shared distribution folder, or a release staging directory), so it cannot honour the "immutable, content-hashed
output folder" invariant that drives incremental builds:

- **Writes outside `context.next()`.** The destination is supplied as a `Path` to the constructor and lives
  wherever the user wants it - `~/.m2/repository`, a network share, an existing distribution layout. Files are
  copied (not hard-linked, since the target may be on a different filesystem) and `REPLACE_EXISTING` always
  overwrites whatever is at the destination. `context.next()` itself is left empty.
- **Always re-runs.** `shouldRun(...)` returns `true`, so even if all inputs are unchanged the export is performed
  again. The reason is that the destination is outside the executor's control - anything could have edited or
  removed those files between builds - so the only safe assumption is that the export needs redoing every time.
  The step's serialized form is still hashed (config-aware cache invalidation still applies), but `consistent`
  results just shorten the diff status the placement function sees, not whether it runs.

The placement is the same `Function<Path, Optional<Path>>` shape `Relocate` uses: each visited file is mapped to
an `Optional<Path>` relative to the configured target, or skipped. `MavenRepositoryPlacement.toLocalRepository()` and
`MavenRepositoryPlacement.toRepository(Path)` ship a placement that consumes the canonical per-module output produced
by `Relocate(MavenProject.artifactsByModule())` (i.e. each sub-module folder contains `classes.jar`, `sources.jar`,
`javadoc.jar`, `pom.xml`, `identity.properties`, `metadata.properties` and - for test variants - `module.properties`):
for every visited file it reads the sibling `pom.xml`, parses `groupId`/`artifactId`/`version` out of it, checks the
sibling `module.properties` for the `tests` key (which marks the directory as a test variant), and routes the file
to the standard Maven layout. Main-module files use the bare coordinate; test-variant files use a `-tests` classifier
suffix and route to the **main** module's coordinate. The test variant's `pom.xml` is never staged - the merged main POM is the canonical POM for the coordinate:

| File (main module)   | Maven destination                                       |
| -------------------- | ------------------------------------------------------- |
| `classes.jar`        | `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>.jar`              |
| `sources.jar`        | `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>-sources.jar`      |
| `javadoc.jar`        | `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>-javadoc.jar`      |
| `pom.xml`            | `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>.pom`              |

| File (test variant)  | Maven destination                                       |
| -------------------- | ------------------------------------------------------- |
| `classes.jar`        | `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>-tests.jar`         |
| `sources.jar`        | `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>-tests-sources.jar` |
| `javadoc.jar`        | `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>-tests-javadoc.jar` |
| `pom.xml`            | (skipped - the merged main POM is the canonical POM)                                |

Files without a sibling `pom.xml` are skipped, so the same step can be pointed at a tree that mixes
multi-module output and arbitrary other content without false hits. After copying, the bundled finalizer
walks the target and writes the `mvn install`-equivalent metadata: a `maven-metadata-local.xml` per artifact
(`<release>` set to the highest non-SNAPSHOT version by Maven semantics, `<versions>` sorted ascending,
`<lastUpdated>` timestamp), an `_remote.repositories` marker per version directory, and a `modelVersion="1.1.0"`
`maven-metadata-local.xml` inside each `-SNAPSHOT` version directory listing per-extension/classifier
`<snapshotVersions>`. Unhandled today: checksum sidecars (`.sha1`/`.md5`), GPG signatures, and `<parent>` version
inheritance - the `Pom` step always emits an explicit `<version>`, so the last is fine in practice for artifacts
produced by this build.

Build executor modules
----------------------

The modules listed here are pre-implemented for convenience; the build tool itself does not depend on any of them, and a build is free to ignore them and supply its own `BuildExecutorModule` implementations.

In every diagram below, blue rounded nodes are inputs (folders or files), yellow rectangles are steps, and
purple rectangles are nested sub-modules. Optional steps are connected with dashed edges; the edge label names
the method that enables them.

### `JavaModule`

Used for compiling and packaging a single Java module from its sources and its resolved dependencies. Between
compilation and packaging it runs a `Versions` step that consults the compile-scope `requires.properties` and
rewrites every `module-info.class` to embed the resolved versions on each `requires` directive, so the produced
jar carries the same versions that were used to assemble its module path. Calling `.test(engine)` or
`.testIfAvailable()` adds an extra `tests` sub-module that runs the compiled tests; the
`(repositories, resolvers)` overloads forward into `TestModule.withResolvers(...)` so the test runner can be fetched
on the side instead of being a compile-time `requires` of the test module (see *`TestModule`* below).

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  classDef optional fill:#fef3c7,stroke:#92400e,color:#78350f,stroke-dasharray:4 3;
  src(["sources/"]):::input
  arts(["dependency artifacts/"]):::input
  req(["dependency requires.properties"]):::input
  classes["classes<br/>(Javac)"]:::step
  versions["versions<br/>(Versions)"]:::step
  artifacts["artifacts<br/>(Jar, Sort.CLASSES)"]:::step
  tests["tests<br/>(TestModule)"]:::optional
  src --> classes
  arts --> classes
  classes --> versions
  req --> versions
  versions --> artifacts
  arts --> artifacts
  classes -.->|".test(engine) /<br/>.testIfAvailable()"| tests
  artifacts -.-> tests
  arts -.-> tests
```

### `TestModule`

A `BuildExecutorModule` that runs a configured `TestEngine` (e.g. JUnit 5) against the compiled tests of its
predecessors. The simple form adds only an `execute` step (`Java`) and expects the runner module to already be on
the inherited class- or module-path. Calling `withResolvers(repositories, resolvers)` instead fetches the runner
on the side, so the user never has to declare it as a compile-time `requires` of their test module:

- `resolved` (`TestModule.Requires`) writes the runner's coordinate to `requires.properties`, picking the first entry in
  `TestEngine.coordinates()` whose `<prefix>` is served by one of the configured resolvers - `TestDefaultEngine.JUNIT5`
  ships both `module/org.junit.platform.console` and `maven/org.junit.platform/junit-platform-console/<version>`,
  so the same engine works across `Modular`, `ModularToMaven`, and `Manual`-style builds. If the runner is already
  visible on an input folder (`TestEngine.hasRunner(...)`), nothing is written.
- `checked` (`Checksum`, optional, only when `.computeChecksums(algorithm)` is set) fetches each unresolved
  coordinate from its `Repository` and rewrites `requires.properties` with `algorithm/<hex>` checksums.
- `required` (`Resolve`) expands that single coordinate into its transitive closure via the matching
  `Resolver`.
- `prepared` (`TestModule.Prepare`) fetches each resolved coordinate via its `Repository` into a `runner/` subfolder of
  its output - deliberately not `artifacts/`, so the runner's jars stay invisible to a downstream `Assign` step
  that scans for module artifacts - and writes `process/java.properties` with `--module-path=<paths>` (paths
  separated by `\n`, recorded relative to the step's own output folder so they survive the temp-to-persistent move).
- `executed` (`TestModule.Run` extends `Java`) honours the `-Djenesis.test=<patterns>` system property: a
  comma-separated list of Java regex entries, each `<classRegex>` or `<classRegex>#<methodName>`. Class entries are
  emitted via the engine's `prefix()` (e.g. JUnit 5's `-select-class=`); method entries via `methodPrefix()` (e.g.
  `-select-method=`). The property's value is part of the step's serialized state (so changing it invalidates
  the cache); when set, the step is also forced to re-run regardless of cache consistency. The step picks the
  properties file up via the standard `ProcessBuildStep`
  mechanism. `Java` consumes `--module-path` and `--class-path` entries in its own per-folder iteration,
  resolving each relative value against the same `argument.folder()` it scans for `classes/`/`artifacts/` and
  folding them into the same path lists, so they end up in a single combined `--module-path` / `-classpath`
  argument instead of repeated flag instances the JVM would treat as last-wins overrides.

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  classDef optional fill:#fef3c7,stroke:#92400e,color:#78350f,stroke-dasharray:4 3;
  arts(["inherited classes/<br/>+ artifacts/"]):::input
  resolved["resolved<br/>(Requires)"]:::optional
  checked["checked<br/>(Checksum)"]:::optional
  required["required<br/>(Resolve)"]:::optional
  prepared["prepared<br/>(downloads runner/<br/>+ writes process/java.properties)"]:::optional
  executed["executed<br/>(Java/Run)"]:::step
  arts --> executed
  arts -.->|".withResolvers(repositories, resolvers)"| resolved
  resolved -.->|".computeChecksums(algorithm)"| checked
  checked -.-> required
  resolved -.-> required
  required -.-> prepared
  prepared -.-> executed
```

### `DependenciesModule`

Used for resolving and downloading external dependencies declared in `requires.properties`. The pipeline runs
`Resolve` (transitive closure) → `Checksum` (content hashes; only when `.computeChecksums(algorithm)` is set)
→ `Download` (jars). Without checksums the `checked` step is skipped and `Download` reads directly from
`resolved`.

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  classDef optional fill:#fef3c7,stroke:#92400e,color:#78350f,stroke-dasharray:4 3;
  deps(["requires.properties"]):::input
  resolved["resolved<br/>(Resolve)"]:::step
  checked["checked<br/>(Checksum)"]:::optional
  artifacts["artifacts<br/>(Download)"]:::step
  deps --> resolved
  resolved -.->|".computeChecksums(algorithm)"| checked
  resolved --> artifacts
  checked -.-> artifacts
```

### `MultiProjectModule`

Used as the generic shape behind multi-project layouts. An *identifier* sub-module discovers the projects in a
source tree and writes their coordinates and dependencies; a `Group` step partitions the cross-project
dependency graph; a *factory* then assembles one sub-module per discovered project, wiring cross-project edges
between them. Each per-project closure receives a `ModuleDescriptor` exposing `name()` and `dependencies()`; the
concrete subtype (`ModularModuleDescriptor` or `MavenModuleDescriptor`) also exposes the standardised inherited
keys as helpers (`sources()`, `manifests()`, `artifacts()`, `checked()`), so a closure doesn't need to know how
the identifier laid out its outputs. The example below shows two projects `A` and `B` where `A` requires `B`,
so `B` is built first and its output flows into `A`.

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  classDef module fill:#ede9fe,stroke:#7c3aed,color:#4c1d95;
  inh(["inherited inputs"]):::input
  subgraph "identifier"
    direction TB
    idA["module-A<br/>(identifier)"]:::module
    idB["module-B<br/>(identifier)"]:::module
  end
  subgraph "build"
    direction LR
    group["group<br/>(Group step)"]:::step
    subgraph "module"
      direction LR
      projB["B<br/>(factory output)"]:::module
      projA["A<br/>(factory output; requires B)"]:::module
      projB --> projA
    end
    group --> projA
    group --> projB
  end
  inh --> idA
  inh --> idB
  idA --> group
  idB --> group
  idA --> projA
  idB --> projB
```

### `MavenProject`

Used to drive a build from a Maven project layout. As the identifier inside a `MultiProjectModule`, it mirrors
every `pom.xml` into `pom/`, parses each into a per-module `maven/<path>.properties`, and emits one
`module-X` sub-module per discovered POM containing source folders, optional resource folders, and a
`manifests` step that writes the project's own coordinate (`identity.properties`) and its declared Maven
dependencies (`requires.properties`). Each POM's `<dependencyManagement>` block is captured into the same
manifests step's `versions.properties`, so the resolver sees the project's BOM entries the same way it would
see them if they had been declared in a top-level POM under resolution - pinning applies uniformly to declared
dependencies and to transitives that aren't directly required. A `<properties><maven.compiler.release>` element
in the POM is captured by the same manifests step into a `process/javac.properties` sidecar with `--release=<V>`,
which `ProcessBuildStep` forwards to `javac`. `MavenProject.make(...)` returns the full wrapped
`MultiProjectModule` whose factory runs `prepare` (`MultiProjectDependencies`), `dependencies`
(`DependenciesModule.computeChecksums`), `build` (caller-supplied, typically `JavaModule`), and `assign`
(`Assign`) for each project.

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  classDef module fill:#ede9fe,stroke:#7c3aed,color:#4c1d95;
  tree(["project tree<br/>with pom.xml files"]):::input
  subgraph "MavenProject (identifier)"
    direction LR
    scan["scan<br/>(mirrors pom.xml<br/>into pom/)"]:::step
    prepare["prepare<br/>(writes maven/*.properties)"]:::step
    subgraph "module"
      direction LR
      idA["module-A<br/>(sources, resources-N,<br/>manifests step)"]:::module
      idB["module-B<br/>(sources, resources-N,<br/>manifests step)"]:::module
    end
    scan --> prepare --> idA
    prepare --> idB
  end
  subgraph "B (per project)"
    direction LR
    pBprep["prepare<br/>(MultiProjectDependencies)"]:::step
    pBdeps["dependencies<br/>(DependenciesModule)"]:::module
    pBbuild["build<br/>(caller-supplied)"]:::module
    pBassn["assign<br/>(Assign)"]:::step
    pBprep --> pBdeps --> pBbuild --> pBassn
  end
  subgraph "A (per project, requires B)"
    direction LR
    pAprep["prepare<br/>(MultiProjectDependencies)"]:::step
    pAdeps["dependencies<br/>(DependenciesModule)"]:::module
    pAbuild["build<br/>(caller-supplied)"]:::module
    pAassn["assign<br/>(Assign)"]:::step
    pAprep --> pAdeps --> pAbuild --> pAassn
  end
  tree --> scan
  idA --> pAprep
  idB --> pBprep
  pBassn --> pAprep
```

### `ModularProject`

Used to drive a build from a Java-modular project layout. As the identifier inside a `MultiProjectModule`, it
walks the source tree for `module-info.java` files and emits one sub-module per descriptor, each containing a
`sources` source and a `manifests` step that parses the descriptor and writes `identity.properties` plus
`requires.properties` from the Java `requires` directives. Javadoc tags of the form `@requires <module> <version>`
on the module declaration are captured into the same manifests step's `versions.properties` as a BOM-style pin
map - the tag does not have to name a directly-required module, so a transitive can be pinned the same way:

```java
/**
 * @release 25
 * @requires org.junit.jupiter 5.11.3
 * @requires org.junit.platform.commons 1.11.4
 */
open module build.jenesis.test {
    requires org.junit.jupiter;
}
```

An `@release <V>` tag on the module declaration (independent of the BOM pins above) is captured by the
manifests step into a `process/javac.properties` sidecar containing `--release=<V>`, which `ProcessBuildStep`
forwards to `javac` when compiling the module.

`ModularProject.make(...)` returns the full wrapped `MultiProjectModule` whose factory runs `prepare`
(`MultiProjectDependencies`), `dependencies` (`DependenciesModule.computeChecksums`), `build` (caller-supplied,
typically `JavaModule`), and `assign` (`Assign`) for each project.

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  classDef module fill:#ede9fe,stroke:#7c3aed,color:#4c1d95;
  tree(["project tree<br/>with module-info.java files"]):::input
  subgraph "ModularProject (identifier)"
    direction LR
    idA["module-A<br/>(sources + manifests step<br/>writing identity + requires)"]:::module
    idB["module-B<br/>(sources + manifests step<br/>writing identity + requires)"]:::module
  end
  subgraph "B (per project)"
    direction LR
    pBprep["prepare<br/>(MultiProjectDependencies)"]:::step
    pBdeps["dependencies<br/>(DependenciesModule)"]:::module
    pBbuild["build<br/>(caller-supplied)"]:::module
    pBassn["assign<br/>(Assign)"]:::step
    pBprep --> pBdeps --> pBbuild --> pBassn
  end
  subgraph "A (per project, requires B)"
    direction LR
    pAprep["prepare<br/>(MultiProjectDependencies)"]:::step
    pAdeps["dependencies<br/>(DependenciesModule)"]:::module
    pAbuild["build<br/>(caller-supplied)"]:::module
    pAassn["assign<br/>(Assign)"]:::step
    pAprep --> pAdeps --> pAbuild --> pAassn
  end
  tree --> idA
  tree --> idB
  idA --> pAprep
  idB --> pBprep
  pBassn --> pAprep
```

### `ExternalModule`

Used for loading a `BuildExecutorModule` from a jar coordinate at build time, so a build can pull in plug-in
modules published to a repository instead of vendoring them as source. Given a `<prefix>/<key>` coordinate, a map
of `Repository` instances, a map of `Resolver` instances, and optional constructor arguments, it registers four
internal nodes:

- `coordinate` writes the requested coordinate into a fresh `requires.properties`.
- `dependencies` is an embedded `DependenciesModule` that resolves + downloads the coordinate's transitive closure
  using the supplied resolvers and repositories.
- `external` reads the main jar's `META-INF/MANIFEST.MF`, looks up the `Jenesis-Module` attribute, and writes the
  recorded class name into `external.properties`. Because this is a cached `BuildStep` keyed off the `artifacts/`
  folder, the manifest read is skipped on subsequent runs whose downloaded artifacts checksum-identically.
- `delegate` opens a `URLClassLoader` over the downloaded jars, loads the recorded class, picks the unique public
  constructor whose parameter count matches the supplied arguments, instantiates it, and invokes its `accept(...)`
  against the same inherited folders `ExternalModule` itself received.

The `URLClassLoader` parents to the build's own class loader, so `BuildExecutorModule` and the rest of the public
API stay shared types between host and plug-in - but the external coordinate's transitive dependencies live only
inside the loader, so they don't leak into the host classpath and two external modules with conflicting libraries
don't clash. The loader is intentionally kept alive for the build's lifetime, because the build steps the delegated
module registers hold class references to it.

`ExternalModule` overrides `resolve(...)` to hide its four internal nodes from the published output map and to
strip the `delegate/` prefix from the delegated module's results, so the delegated module's outputs appear under
`ExternalModule`'s registered name - exactly as if the delegated module had been wired into the build directly.
The hidden steps still execute and participate in the cache normally; only their published names disappear.

Implementing a `BuildStep`
--------------------------

A custom `BuildStep` is a serializable functional implementation of:

```java
CompletionStage<BuildStepResult> apply(Executor executor,
                                       BuildStepContext context,
                                       SequencedMap<String, BuildStepArgument> arguments);
```

A few rules of thumb for new steps:

- **Write only into `context.next()`.** Treat predecessor folders (`argument.folder()`) and `context.previous()` as
  read-only; use `context.supplement()` for scratch files that should not be published. The "immutable output
  folder" invariant is what makes downstream caches correct.

- **Use `shouldRun(...)` for finer dependencies.** The default re-runs the step whenever any input checksum
  changed. Overriding it lets a step ignore subtrees that do not affect its output (`Bind` only watches its bound
  paths; `Relocate` only watches its declared prefixes).

- **Decide what counts as "configuration".** Every non-`transient` field is folded into the step's configuration
  hash via `ObjectOutputStream.writeObject(step)`. Anything that should *not* count as part of the build's
  identity - a `Repository` that by contract returns the same artifact for the same coordinate, a JDK service
  factory, a `MavenPomEmitter` - must be marked `transient` so swapping equivalent backends does not invalidate
  the cache. Conversely, fields that *do* affect the output (a sort order, a placement function, a flag list)
  must stay non-transient.

- **Hold lambdas through serializable bounds.** Constructors that take functional values should declare an
  intersection bound (`<T extends Function<…> & Serializable>`) so the compiler emits a serializable lambda.
  A step that holds a non-serializable value will fail outright when `BuildStepHashFunction.ofSerializationDigest`
  tries to serialize it for the configuration hash, propagating a `NotSerializableException`. This is intentional:
  silent fallback would hide a bug that breaks cache invalidation, so the surface is loud at build time instead.
  `java.nio.file.Path` is the one exception that is *not* a bug to hold: the digest stream substitutes any `Path`
  for its `toString()`, making it a serializable participant in the configuration hash by design even though the
  JDK's concrete `Path` implementations don't implement `Serializable`.

- **Bump `serialVersionUID` to communicate code changes.** The cache's notion of "configuration" is the step's
  *serialized form* - the values of its non-transient fields plus the class's `serialVersionUID`. Editing the
  body of `apply(...)` (fixing a bug, changing a tool flag, switching to a different output layout) does not
  alter the serialized form, and therefore does **not** invalidate previously cached outputs. To force a rebuild
  after such a change, increment the step's `serialVersionUID`: the new value flows into the stream's class
  descriptor, the configuration hash changes, and every previously cached run of that step re-executes.
  (`-Djenesis.rebuild=true` achieves the same thing globally, but discards every other step's cache too.)

- **Return a meaningful `BuildStepResult`.** A successful result with `next() == true` atomically promotes
  `context.next()` over the previous run. A result with `next() == false` keeps the previous folder and discards
  the temp (useful for steps that detect their inputs would yield the same output as last time). A failed
  `CompletionStage` or a thrown exception deletes the temp and propagates as a build failure.

Steps that follow these rules participate fully in incremental builds: identical inputs and identical
configuration reuse the previous output unchanged, and a meaningful change anywhere - fields,
`serialVersionUID`, or predecessor outputs - re-executes the step into a fresh `next` folder.

Configuration
-------------

The following system properties and environment variables tune the build at launch time.

| Name                    | Kind                | Effect                                                                                                                                                                                                                                |
| ----------------------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `jenesis.rebuild`       | system property     | When `true`, the build script (e.g. `Modules.java`) deletes `target/` before constructing the `BuildExecutor`, forcing a full re-run of every step.                                                                                  |
| `jenesis.verbose`       | system property     | When `true`, the default `BuildExecutorCallback` prints per-step verbose output (input/output checksum diffs, decisions to skip or re-run) instead of just the high-level status lines.                                              |
| `jenesis.test`          | system property     | When set, `TestModule.executed` only emits selectors for classes (and optionally methods) matching the comma-separated regex entries `<classRegex>[#<method>]`. The value is part of the step's serialized state, and the step is forced to re-run regardless of cache consistency. |
| `jenesis.buildVersion`  | system property     | When set, stamps the version onto every artifact this build produces. `Javac` passes `--module-version <V>` when compiling a `module-info.java`, so the produced `module-info.class` carries it as `Module.version` (and downstream consumers automatically pick it up as `compiledVersion` on their `requires` directives). `Pom` replaces the project's own `<version>` element with this value; dependency versions are unaffected. The Maven export layout reads coordinates from the produced `pom.xml`, so the export folder path, artifact filenames and `maven-metadata-local.xml` follow along. |
| `jenesis.project.layout`        | system property | Read by `Project.Builder` (the canonical entry point) to force a `Layout` regardless of auto-detection or any in-code `.layout(...)`. Accepts `auto`, `maven`, `modular`, `modular_to_maven` (case-insensitive). Unknown values throw on `resolveProperties()`. |
| `jenesis.project.hashAlgorithm` | system property | Read by `Project.Builder` to override the digest algorithm passed to `MavenProject.make` / `ModularProject.make` (default `SHA256`). Has no effect on builds that don't go through `Project`. |
| `jenesis.project.skipTests`     | system property | When set (any value, including the empty string from a bare `-Djenesis.project.skipTests`), `Project.Builder` constructs its `JavaModule` without the `testIfAvailable(...)`/`test(...)` decoration, so test sources and test dependencies are not wired into the graph. |
| `jenesis.project.stageTests`    | system property | When set to `true`, the `STAGE` step includes test-variant artifacts. For `MAVEN` and `MODULAR_TO_MAVEN` that means the `-tests.jar` (plus `-tests-sources.jar` / `-tests-javadoc.jar` when those flags are on) and the test module's dependencies merged into the main `pom.xml` with `<scope>test</scope>`. For `MODULAR` it means the test module is staged as its own `<module>/<module>.jar` directory. Default `false`: tests still run during the build but their artifacts are not placed into the staging tree. |
| `jenesis.project.root`          | system property | Overrides the project root that `Project.Builder` scans for `module-info.java` / `pom.xml` (default `.`). |
| `jenesis.project.target`        | system property | Overrides the per-build output folder passed to `BuildExecutor.of(...)` (default `target`). Safe to delete to force a clean build. |
| `jenesis.project.cache`         | system property | Overrides the cross-build cache folder (default `cache`) under which `MODULAR` stores its `modules/` URI registry. Ignored by `MAVEN` and `MODULAR_TO_MAVEN`. |
| `MAVEN_REPOSITORY_URI`  | environment variable| Overrides the default `MavenDefaultRepository` upstream URL (`https://repo1.maven.org/maven2/`). Useful for pointing at an internal mirror; a trailing slash is added automatically if missing.                                       |
| `JAVA_HOME`             | environment variable| Consulted by `ProcessBuildStep`/`ProcessHandler` to locate the `java`/`javac`/`javadoc` binaries when the `java.home` system property is not set (typical when launching from a non-JDK runtime).                                     |

Properties are passed on the JVM command line, e.g.

    java -Djenesis.rebuild=true build/Modules.java

### Selectors on the command line

The build script forwards its `String[] args` to `BuildExecutor.execute(...)` (e.g. `root.execute(args)` in
`build/Modular.java`), so any positional arguments after the build file are interpreted as selectors. With no
arguments, the full graph runs.

| Invocation                                | What runs                                                                                                                                                       |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `java build/Modular.java`                 | Whole graph. On a warm cache, every step is `[SKIPPED]`.                                                                                                        |
| `java build/Modular.java ::/test`         | Every `test` sub-module at any depth, plus its transitive preliminary closure. Modules along the path have their step preliminaries cache-checked; sibling sub-graphs that happen to be scheduled by `::` lenient-skip. |
| `java build/Modular.java build/::/test`   | Same, but anchored under the top-level `build` module. Top-level entries that aren't on the path to `build` (e.g. the `collect` step that depends on `build`) are not scheduled at all.                  |
| `java -Djenesis.test='.*FooTest' build/Modular.java ::/test` | Same selector, but `TestModule.executed` re-runs unconditionally and only selects classes matching the regex; upstream `classes`/`artifacts` etc. stay cached. |

A literal selector that doesn't resolve throws (`Unknown selector: …`). Wildcards (`:` and `::`) are lenient - they
silently skip branches with no match - but as a result they over-schedule sibling subtrees: their modules' `accept`
runs and their declared step preliminaries are pinned for safety. Prefer literal paths when you know them.

Implementation details
----------------------

### `BuildExecutor`

`BuildExecutor` is the engine that owns one level of the graph. It does two things in sequence: collect
*registrations* into an ordered map, then *execute* that map under a set of selectors. The same class plays both
roles at every nesting depth - a module is implemented as a child `BuildExecutor` whose `target` directory is a
subfolder of its parent's and whose `location` prefix is "parent-path/", so log lines and error messages stay
addressable as slash-delimited paths.

**Registrations.** Every `addSource`/`addStep`/`addModule` call funnels into the private `add(...)` helper, which
stores a `Registration(Bound bound, Set<String> preliminaries, Map<String, String> dependencies)` keyed by the
caller-supplied identity. `dependencies` is the raw declaration (which may contain slashes for paths into
sub-modules); `preliminaries` is the derived set of *top-level* identifiers the registration depends on (the
substring before the first slash). Preliminaries drive scheduling order; dependencies drive how predecessor
summaries are filtered into a registration's input map. Sources reach the same code path: a source is a step that
publishes a fixed folder.

**`Bound`.** Each registration carries a `Bound`, a tiny internal interface with a single `apply(...)` method that
returns a `CompletionStage<Map<String, Map<String, StepSummary>>>` - the outer map's single key is the
registration's identity, the inner map is its published outputs (one entry per leaf for steps; potentially many,
keyed by sub-paths, for modules). `Bound` also carries a `module()` flag (default `false`, overridden to `true` by
`bindModule`). The flag is the only externally-visible distinction between steps and modules at execution time;
everything else falls out of `Bound.apply`'s behavior.

**`bindStep`.** Wraps a `BuildStep` in a `Bound` whose `apply` does the per-step caching. On entry it bails early
with an empty result if any forwarded selector reaches it (so lenient-skipping costs nothing). Otherwise it locates
the step's persistent folder under `target/<urlencoded-identity>/`, reads the previously written input checksums and
step-config hash, and computes `consistent` by comparing them against the current step-config hash and the current
output folder's contents. If `consistent && !shouldRun(arguments)`, the cached output is returned as-is. Otherwise
the step runs into a fresh temp directory; on success the temp directory atomically replaces the persistent folder
(or, if the step set `BuildStepResult.next() == false`, the temp is deleted and the previous run is reused).
Crashed runs always delete the temp.

**`bindModule`.** Wraps a `BuildExecutorModule` in a `Bound` whose `apply` is *not* short-circuited by selectors:
modules always run their `accept(buildExecutor, folders)` to register a sub-graph. The child `BuildExecutor` it
hands to `accept` shares the parent's hash functions and callback, but has its own `target` subfolder and its own
empty `registrations`. After `accept` returns, the child's `doExecute(...)` is invoked with the same selectors that
reached the parent module, so filtering descends with the user's selector intact. Each result published by the
child is run through the module's optional `resolver` to give the parent its public name.

**`doExecute`.** The selector resolver. It produces three sets: `scheduled` (registrations that will be dispatched
in this level), `pinned` (registrations whose preliminary chain must run unconditionally), and `direct`
(registrations that should not receive any forwarded selectors). It also produces a `forwarded` map of selectors
to pass into each registration:

- A literal first segment matching a registration name pins it. With no tail, it also goes into `direct`. With a
  tail, the tail is forwarded.
- `:` and `::` schedule every registration at this level. With no tail, all are pinned and direct. With a tail,
  the descend selector is forwarded to all and (for `::`) the tail is also re-queued so it can match at this depth.
- A literal first segment with no matching registration throws unless the selector is lenient.

After the queue drains, two passes pin preliminaries: every preliminary of any pinned registration is added to
`scheduled`, `direct`, and `pinned`, transitively. The second pass adds every *scheduled module* to `pinned` so
the same walk picks up the module's step preliminaries - this is what guarantees a module's `accept` finds its
predecessor folders even when the selector reached it only via lenient propagation. Finally, registrations in
`direct` have their forwarded selectors stripped, so they execute unconditionally instead of lenient-skipping.

Dispatch is then a topological loop: pick scheduled entries whose preliminaries have all been dispatched, build
their input map by translating each declared dependency into a `StepSummary` (cross-module dependencies via the
inherited map, intra-level dependencies by name, sub-path dependencies by exact path lookup into the predecessor's
published outputs), and call `bound.apply(...)` on the executor. Results are collected per-registration in
`dispatched`; the final result is the union of all top-level published summaries.

**Caching invariants.** Three things have to match for a step to be reused: the step's *configuration hash* (a
digest of `ObjectOutputStream.writeObject(step)`), the *predecessor input checksums* (compared against what was
recorded on the previous run), and the *current output folder* (re-hashed and compared against the previously
written `checksums` file). A divergence anywhere - a constructor field changed, a predecessor produced different
bytes, the output was tampered with - flips `consistent` to `false` and the step re-runs. Selectors are *not* part
of the hash; they only gate scheduling, so a step that runs under selectors produces the same cached output a full
build would have, and subsequent unselected runs hit the cache as expected.

### Java support

Generic infrastructure (`BuildExecutor`, `BuildStep`, `BuildExecutorModule`) doesn't know anything about Java. The
Java-specific classes are a thin layer of `BuildStep`/`BuildExecutorModule` implementations that plug into it.

- **`ProcessBuildStep`** is the abstract base for every step that shells out to an external command. Subclasses
  return their command-line via `process(...)`; the base class assembles the process, captures stdout/stderr,
  validates the exit code, and reports a `BuildStepResult`. It also defines the `process/<name>.properties`
  convention used by upstream steps to inject command-line arguments (see `TestModule.Prepare`, which writes
  `process/java.properties` with `--module-path=…`).
- **`JdkProcessBuildStep`** extends `ProcessBuildStep` with a single twist: it serializes `Runtime.version()`
  into its config hash so a JDK upgrade invalidates every cached `javac`/`java`/`javadoc` output without any
  per-step opt-in.
- **`ProcessHandler`** wraps the actual invocation (forked `Process` or in-process `ToolProvider` call). The
  factory function passed to a step's constructor decides which: `Javac.tool()` runs `javac` in-process via
  `java.util.spi.ToolProvider`; `Javac.process()` forks. The same split exists for `Jar` and `Javadoc`.
- **`Javac`, `Jar`, `Javadoc`, `Java`** are the concrete tool drivers. They consume the conventional folders
  (`sources/`, `classes/`, `resources/`, `artifacts/`) and produce the conventional outputs documented in the
  *Conventional folders and files* section.
- **`TestModule`** is a `BuildExecutorModule` that wires `Java` into a runner. `TestEngine` and `TestDefaultEngine`
  encode per-framework metadata (main class, command-line prefix for selecting classes/methods, marker class
  used to detect the framework on the classpath, optional Maven coordinates of the runner). New frameworks slot
  in by implementing `TestEngine`.
- **`JavaModule`** is the canonical `BuildExecutorModule` for "compile sources, package as a jar, optionally run
  tests". It delegates to `Javac`, `Jar`, and `TestModule`. Build scripts that don't have multi-project structure
  (`Minimal.java`, `Manual.java`) wire it directly.
- **`ModuleInfoParser` / `ModuleInfo`** parse `module-info.java` via the `javax.tools` / `com.sun.source` APIs
  and surface the module name, its `requires` set (including `requires transitive`, `requires static`, and
  `requires static transitive`), and a `versions` map extracted from `@requires <module> <version>` Javadoc tags
  on the module declaration - the input that `ModularProject` writes to `versions.properties` so transitives can
  be pinned without listing them as direct `requires`.
- **`ModularJarResolver`** is a `Resolver` (the generic resolution interface) backed by parsing each fetched
  jar's `module-info.class` straight from bytecode (no class loading). It walks the `requires` edges into a
  transitive closure and emits resolved coordinates of the form `<prefix>/<module>[/<version>]`. The version
  is chosen from (in order): the `versions` SPI input passed by `Resolve` (Javadoc pin); the
  `rawCompiledVersion()` recorded on the parent's `requires <X>` directive when first encountered (first parent
  wins); the `ModuleDescriptor.rawVersion()` recorded in the fetched jar's `module-info.class`; or omitted
  entirely if none of these are present. "First parent" is concrete and deterministic: the traversal is a
  single BFS over a queue seeded with the coordinates from `requires.properties` in file order, and each
  visited module emits its transitive `requires` into the queue sorted by module name. The propagated
  `rawCompiledVersion` map is updated via `putIfAbsent`, and once a module name has been visited the next pop
  short-circuits on the `resolved` set, so a *later* requirer that bakes a different version into its own
  `requires <X>` directive is silently ignored. One practical consequence: re-ordering the lines of
  `requires.properties` is a semantically meaningful edit that can change which transitive version of a shared
  module wins (the cache hashes the file faithfully, so the next build re-resolves correctly, but a code
  review that only diffs content will miss it). When a version is chosen *before* the fetch (pin or propagated
  compiledVersion), the resolver asks the `Repository` for `<module>/<version>` so a Maven-conventional URL
  carrying a registry default can be rewritten by `Repository.ofUris` into the requested version; the resolver
  also falls back to a bare-name fetch when the versioned lookup returns nothing, so repositories that don't
  understand versioned coordinates continue to work. Java module rules differ from Maven's (no Maven-style nearest-wins;
  transitivity is opt-in per `requires transitive`), so the resolver is small but distinct.
- **`Versions`** is the `BuildStep` that closes the loop: it reads `requires.properties` after resolution,
  builds a `<module-name> → <version>` map from every `<prefix>/<name>/<version>` entry, and rewrites every
  `module-info.class` it finds under the predecessor `classes/` folders to stamp those versions onto each
  matching `requires` directive's `compiledVersion`. Non-`module-info` files are hard-linked through to the
  output unchanged. `JavaModule` wires this step between `Javac` and `Jar` so the packaged jar's
  `module-info.class` carries the resolved versions.
- **`DownloadModuleUris`** is a `BuildStep` that materializes a properties file mapping module name → URI.
  `Modular.java` and similar scripts feed it the `sormuras/modules` registry plus a project-local override.
- **`ModularProject`** is the equivalent of `MavenProject` but for module-based multi-project builds: it walks
  a directory tree, identifies modules from their `module-info.java`, and registers a `JavaModule` per module
  via `MultiProjectModule`.

The wiring pattern is uniform: anything Java-specific that runs as part of a step is a `BuildStep` (cached on
config hash + I/O); anything that wires sub-graphs is a `BuildExecutorModule`; anything that resolves
coordinates implements `Resolver`. The generic infrastructure treats all three as opaque.

### Maven support

Maven support is layered on top of the same generic interfaces, with one extra wrinkle: Maven repositories
serve POMs in addition to artifacts, so they get a refined `Repository` interface.

- **`MavenRepository`** extends the generic `Repository` with `fetchMetadata(groupId, artifactId, version)`
  returning an `InputStream` over the POM. Implementations: `MavenDefaultRepository` (HTTP, with on-disk cache
  in `cache/`) and any user-supplied subclass (e.g. an internal Nexus mirror). The `MAVEN_REPOSITORY_URI`
  environment variable overrides the default upstream URL.
- **`Pom`** is a `BuildStep` that emits or transforms `pom.xml` files in the build graph (used by
  `MavenProject`'s scan/prepare flow).
- **`MavenPomEmitter`** is a stateless serializer: takes a `Pom` model and writes a `pom.xml`. Used both for
  publishing and for materializing per-module POMs during multi-project builds.
- **`MavenPomResolver`** is the resolver. Implements `Resolver` and is the densest piece of code in the repo:
  parses POMs (with parent inheritance), applies dependency-management overrides, walks the transitive closure
  with Maven's nearest-wins version conflict resolution, honors `<exclusions>`, distinguishes `compile`/`runtime`
  /`provided`/`test` scopes (and prunes them per Maven's transitive-scope rules), and resolves BOM (`pom`-type)
  imports. Any entries supplied via the `Resolver` SPI's `versions` parameter are folded into the resolver's
  internal `managedDependencies` map as if they had been declared in a virtual outermost `<dependencyManagement>`
  block, so an external pin (e.g. a `<dependencyManagement>` entry from the local POM emitted by `MavenProject`)
  overrides what each visited POM would have selected on its own. The output is a flat list of resolved
  coordinates that downstream steps (`Checksum`, `Download`) consume.
- **`MavenDependencyKey` / `MavenDependencyName` / `MavenDependencyValue` / `MavenDependencyScope`** are the
  data records the resolver operates on. `Key` is the conflict-resolution identity (groupId+artifactId+type
  +classifier, version excluded so the resolver can pick one); `Name` is `groupId+artifactId` for BOM/parent
  lookups; `Value` carries everything else (version, scope, optional, exclusions).
- **`MavenLocalPom`** captures a parsed POM (coordinates, parent, dependencies, dependency-management,
  properties). The resolver expands these lazily.
- **`MavenVersionNegotiator` / `MavenDefaultVersionNegotiator`** handle Maven's version-range syntax
  (`[1.0,2.0)`, `LATEST`, `RELEASE`, etc.) - picking a concrete version from a candidate list per the rules
  described in the Maven version comparison spec.
- **`MavenRepositoryPlacement`** is a `Function<Path, Optional<Path>>` that maps a coordinate-named file (e.g.
  `maven-org.junit.jupiter-junit-jupiter-5.10.0.jar`) into the Maven local-repo layout
  (`org/junit/jupiter/junit-jupiter/5.10.0/junit-jupiter-5.10.0.jar`). It plugs into `Relocate` to materialize
  a local Maven repository alongside the build's normal artifact folder.
- **`MavenUriParser`** maps coordinate strings to/from URI form, used by repository implementations.
- **`MavenModuleDescriptor`** is `MavenProject`'s implementation of `ModuleDescriptor` - the bridge between
  Maven's per-module data and `MultiProjectModule`'s generic factory contract.
- **`MavenProject`** is the `BuildExecutorModule` entry point: scans a directory tree of `pom.xml` files,
  produces one `MavenModuleDescriptor` per module, and feeds them into `MultiProjectModule` along with a
  `JavaModule` factory. The scan is itself a step (`Pom`) so it caches; the per-module wiring runs in
  `MultiProjectModule`'s body each build.

Same uniform pattern as Java support: `BuildStep` for cached units of work, `BuildExecutorModule` for sub-graph
wiring, `Resolver`/`Repository` for dependency lookup. A user wiring Maven into a build never touches the
resolver mechanics directly - they pass a `Map<String, Resolver>` keyed by prefix (`"maven"`, `"module"`) to
`JavaModule.testIfAvailable(...)` or `DependenciesModule`, and the generic infrastructure dispatches
coordinates to the right resolver by prefix.

Project metadata
----------------

Jenesis carries POM descriptive metadata (name, description, url, license, developer, SCM) through a single
hash-tracked channel keyed off a properties file named by convention `metadata.properties`. The same channel is
fed by per-layout defaults extracted from the project's own sources (module-info or pom.xml), and overridden
key-by-key by the user-supplied file.

### Pointing jenesis at the file

The file path is set in one of two equivalent ways:

- System property: `-Djenesis.project.metadata=metadata.properties` (path resolved relative to the project root).
- Programmatic API: `Project.builder().metadata(Path.of("metadata.properties"))`.

A `null` (unset) value means no project-level metadata file; jenesis still emits POMs that contain only the
fields the active layout supplies. When the value is set, the assembler creates a single-file source pointing at
that path, hard-links it through a `Bind` step, and exposes the result as a predecessor named `metadata` to the
`Pom` step. Because the file's content participates in the build hash chain, any edit to `metadata.properties`
invalidates downstream `pom`, `collect`, and `stage` outputs the same way a source change would.

### Recognised keys

```properties
# Release-target filter (optional). When set, only the module whose Pom-resolver
# artifactId matches this value emits a pom.xml; other modules are silently
# skipped, and MavenRepositoryPlacement therefore omits them from the staged tree.
# For MODULAR projects the value is the full Java module name (e.g. build.jenesis);
# for MAVEN projects it is the artifactId from pom.xml.
project.module=build.jenesis

# Descriptive metadata - usually supplied by the layout (see below) and only
# placed here when overriding. Emitted as <name>, <description>, <url>.
project.name=Jenesis
project.description=A build tool for Java projects, written and configured in Java itself.
project.url=https://github.com/raphw/jenesis

# Single <license> entry. Only one license is supported; if a pom.xml declares
# several, only the first is extracted into the defaults.
license.name=Apache-2.0
license.url=https://www.apache.org/licenses/LICENSE-2.0.txt

# Single <developer> entry. Same single-instance rule as license.
developer.id=raphw
developer.name=Rafael Winterhalter
developer.email=rafael.wth@gmail.com

# <scm> block. <developerConnection> is omitted-then-derived: when
# scm.developerConnection is missing, the emitter writes scm.connection
# into <developerConnection> as well, so most projects only need the two
# keys below.
scm.connection=scm:git:https://github.com/raphw/jenesis.git
scm.url=https://github.com/raphw/jenesis
```

The filename string itself lives as the constant `BuildStep.METADATA` ("metadata.properties"), alongside
`IDENTITY`, `REQUIRES`, and `VERSIONS`, and is the name the `Bind` step writes inside its step output and the
`Pom` step expects to find on a predecessor folder.

### Per-layout defaults

Each layout's manifests step writes its own `metadata.properties` into the per-module manifests folder, so
defaults travel through the same predecessor channel as the user-supplied file. The user's file is iterated
last, so user keys override layout-derived keys on a key-by-key basis.

`MODULAR` extracts `project.name` from the module-info's javadoc first sentence (trailing `.` stripped) and
`project.description` from the rest of the body. The same parser pass that already reads `@release` and
`@requires` reads the description, so the cost is essentially free:

```java
/**
 * Jenesis.
 *
 * A build tool for Java projects, written and configured in Java itself.
 *
 * @release 25
 */
module build.jenesis { ... }
```

contributes `project.name=Jenesis` and `project.description=A build tool for Java projects, ...` automatically.
A javadoc with no body produces neither key. A single sentence with no trailing body produces only
`project.name`.

`MAVEN` (and `MODULAR_TO_MAVEN`) parses each module's source `pom.xml` for `<name>`, `<description>`,
`<url>`, the first `<license>`, the first `<developer>`, and the `<scm>` block, and writes the same property
keys into `metadata.properties`. The extraction is a direct DOM read - property expansion (`${var}`) and parent
POM inheritance are deliberately not applied to these specific fields. A project that needs `${project.url}`
substituted, or a value inherited from a parent POM, must put the resolved value into the project-level
`metadata.properties` so it overrides the literal default.

### The Pom step's predecessor order

`Pom` is wired with predecessors in the order `sources, manifests` (the project-level `metadata.properties`
wraps in via the `Pom(Map<String, String> shared)` constructor, not as a step argument). Each manifests
folder is iterated and contributes `identity.properties`, `module.properties`, `metadata.properties`,
`requires.properties` and `scopes.properties`. The override chain is:

1. The layout's manifests-derived `metadata.properties` (from module-info or pom.xml) lands first.
2. The project-level `metadata.properties` (the file `-Djenesis.project.metadata` points at) is applied
   last via `shared.forEach(metadata::setProperty)`, winning on any overlapping key.

The `project.module` key (read from `metadata.properties`) and the `tests` key (read from `module.properties`)
together suppress POM emission for any module whose resolver-computed `artifactId` does not match -
that's how the test module gets filtered out of a release build of jenesis without anyone having to thread
an exclusion list through the assembler.

Releasing to Maven Central
--------------------------

This section describes the release pipeline that any project using jenesis can adopt. The next section
documents how this repository wires those mechanisms together for its own releases.

### The stage step

Each of the three `Project.Layout` constants wires a `stage` step right after `collect`. The Maven-side
layouts use a dedicated `MavenRepositoryStage` build step that combines Maven repository placement with
test-aware POM merging in one pass. `MODULAR` uses a plain `Relocate` parameterized with the simpler
`ModularPlacement` function:

```java
executor.addStep("collect", new Relocate(MavenProject.artifactsByModule()),   BUILD);     // MAVEN, MODULAR_TO_MAVEN
executor.addStep("collect", new Relocate(ModularProject.artifactsByModule()), BUILD);     // MODULAR
executor.addStep("stage",   new MavenRepositoryStage(),                       "collect"); // MAVEN, MODULAR_TO_MAVEN
executor.addStep("stage",   new Relocate(new ModularPlacement()),             "collect"); // MODULAR
```

The stage step writes its tree under `target/stage/output/`. Files are hard-linked rather than copied -
the staged tree shares inodes with `target/collect/output/`. Like every other jenesis step, its output is
content-hashed and skipped on re-runs when inputs are unchanged.

Under `MavenRepositoryStage` (used by `MAVEN`, `MODULAR_TO_MAVEN`), each per-module directory from
`collect` is classified by reading the `tests` key from its `identity.properties`, then staged differently:

- **Main modules** (no `tests` key in `identity.properties`) have their jars hard-linked at the standard
  Maven repository path
  `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<ext>`. The POM is
  either hard-linked as-is, or - when at least one test variant exists - written out via DOM merge: the
  main POM's `<dependencies>` gains one `<dependency>` per test-variant dep, each carrying
  `<scope>test</scope>`. Test deps that point back at any main artifact (a Java module system test module's
  `requires <main>;` becomes a `<dependency>` on `<main>`) are dropped from the merge to avoid
  self-references.
- **Test variants** (`tests=<main-artifactId>` in `identity.properties`) have their jars hard-linked
  under the **main's** Maven coordinate (not their own) with a `-tests` classifier suffix:
  `<main>-<version>-tests.jar`, `<main>-<version>-tests-sources.jar`,
  `<main>-<version>-tests-javadoc.jar`. No separate POM is staged for the test variant - the merged main
  POM is the single canonical POM for the coordinate.

The `tests` key in `module.properties` is set from existing metadata: `MavenProject.Manifests`
flags any per-module variant whose generated coordinate carries the `tests` classifier, and
`ModularProject.Manifests` flags any module whose `module-info.java` declares an `@tests` javadoc tag
(parsed by `ModuleInfoParser` into `ModuleInfo.testOf()`). Its value is the `artifactId` of the main
module the tests cover (or empty for the deprecated bare `@tests` form, which only resolves when exactly
one main module is present). It does not refer to a Maven `<parent>` POM relationship. Path-based inference is intentionally not used. The `Pom` step lets test variants
through its `project.module` filter so that their POMs are still emitted into `collect/output` for
`MavenRepositoryStage` to harvest dependencies from. Any other module without a POM is naturally absent
from the staged tree (`MavenRepositoryStage` skips it because there are no main coordinates to anchor it
to).

Under `MODULAR`, the `Relocate(new ModularPlacement())` step is unchanged from before: the placement
reads `project.module` from the per-module `metadata.properties` (written by `ModularProject.Manifests`
from the Java module system module declaration and carried through `collect`) and uses that Java module name as the
staging directory and jar prefix; no POM is required or written. The `tests` marker in
`identity.properties` is **ignored** by `ModularPlacement` when `-Djenesis.project.stageTests=true` -
test modules are staged under their own Java-module-named directory with no `-tests` suffix and no merging.
When the flag is unset (default), test modules are simply omitted from the staging tree. When
`-Djenesis.buildVersion=<v>` is set, `ModularPlacement` inserts `<v>` as an additional path segment
between the module name and the jar files.

The resulting trees under `target/stage/output/` (with `<module>=build.jenesis`, `<v>=1.0.0`,
`-Djenesis.project.sources=true`, `-Djenesis.project.docs=true`):

`MAVEN` and `MODULAR_TO_MAVEN` (Maven repository layout, identical for the jenesis project):

```
target/stage/output/
└── build/
    └── jenesis/
        └── build.jenesis/
            └── 1.0.0/
                ├── build.jenesis-1.0.0.jar
                ├── build.jenesis-1.0.0-sources.jar
                ├── build.jenesis-1.0.0-javadoc.jar
                ├── build.jenesis-1.0.0.pom
                ├── build.jenesis-1.0.0-tests.jar
                ├── build.jenesis-1.0.0-tests-sources.jar
                └── build.jenesis-1.0.0-tests-javadoc.jar
```

`MODULAR` with `-Djenesis.buildVersion=1.0.0`:

```
target/stage/output/
└── build.jenesis/
    └── 1.0.0/
        ├── build.jenesis.jar
        ├── build.jenesis-sources.jar
        └── build.jenesis-javadoc.jar
```

`MODULAR` without `jenesis.buildVersion` set (no version segment):

```
target/stage/output/
└── build.jenesis/
    ├── build.jenesis.jar
    ├── build.jenesis-sources.jar
    └── build.jenesis-javadoc.jar
```

(`MAVEN` and `MODULAR_TO_MAVEN` route the test module's jars onto the main artifact's coordinate with a
`-tests` classifier suffix and merge the test-variant dependencies into the main POM with
`<scope>test</scope>`; the per-module `tests=<main-artifactId>` marker in `identity.properties` triggers
this in `MavenRepositoryStage`. `MODULAR` ignores that marker and stages every discovered Java module
under its own Java-module-named directory at the same level when `-Djenesis.project.stageTests=true`.)

A release build is therefore typically:

```
java -Djenesis.buildVersion=<version> \
     -Djenesis.project.sources=true \
     -Djenesis.project.docs=true \
     -Djenesis.project.metadata=metadata.properties \
     build/jenesis/Project.java stage
```

`jenesis.buildVersion` is what `Javac` stamps as `--module-version` and what the `Pom` step writes into
`<version>`; the staged paths use the same value to form `<artifactId>-<version>[.<classifier>].<ext>`.

### Handing off to JReleaser

[JReleaser](https://jreleaser.org) consumes the staged directory directly. A `jreleaser.yml` at the project
root with `deploy.maven.mavenCentral.sonatype.stagingRepositories` pointing at `target/stage/output/`, plus the
standard `JRELEASER_MAVEN_CENTRAL_SONATYPE_USERNAME`/`_TOKEN` and `JRELEASER_GPG_*` environment variables, lets
a single `jreleaser deploy` (or `full-release` from `jreleaser/release-action@v2` in CI) sign and upload the
staged artifacts to Maven Central. `JRELEASER_PROJECT_VERSION` should be set to the same value passed as
`jenesis.buildVersion` so JReleaser and the emitted POM agree on the coordinate.

How jenesis itself releases
---------------------------

The release configuration that this repo actually uses lives in three files: `metadata.properties` at the root,
`jreleaser.yml` at the root, and `.github/workflows/release.yml`.

`metadata.properties` carries the SCM, license, developer, url, and the `project.module=build.jenesis` filter
that limits the staged tree to the main artifact (excluding `module-tests`). `project.name` and
`project.description` are not in this file because the `MODULAR` layout reads them from the module-info's
javadoc, and `module-info.java` has:

```java
/**
 * Jenesis.
 *
 * A build tool for Java projects, written and configured in Java itself.
 *
 * @release 25
 */
module build.jenesis { ... }
```

`pom.xml` at the root mirrors the same metadata for IDE/Maven consumers and for jenesis itself if anyone forces
the `MAVEN` layout. The published coordinate is therefore `build.jenesis:build.jenesis:<version>`, and a
successful release produces:

```
target/stage/output/build/jenesis/build.jenesis/<version>/build.jenesis-<version>.jar
target/stage/output/build/jenesis/build.jenesis/<version>/build.jenesis-<version>-sources.jar
target/stage/output/build/jenesis/build.jenesis/<version>/build.jenesis-<version>-javadoc.jar
target/stage/output/build/jenesis/build.jenesis/<version>/build.jenesis-<version>.pom
```

`.github/workflows/release.yml` triggers on commits whose first line begins with `[release]`. The release
version is resolved as follows:

- `[release] 1.2.3` (explicit version after the marker) - use `1.2.3`.
- `[release]` (marker alone) - take the highest `v?X.Y.Z` git tag, bump its minor by one, reset patch. So
  `v0.1.0 -> 0.2.0`, `v0.9.0 -> 0.10.0`, `v1.4.0 -> 1.5.0`.
- `[release]` with no semver tags in the repo - bootstrap at `0.0.1`.

The workflow then runs the release build (the canonical command above, with `jenesis.buildVersion` set to the
resolved version) and hands off to `jreleaser/release-action@v2` with `full-release`. JReleaser signs, uploads,
and (because `release.github.skipRelease: false`) cuts a matching `v<version>` git tag, which the next
`[release]` commit will pick up to compute its own auto-incremented version.
