Jenesis
=======

Getting started
---------------

Jenesis is a build tool for Java projects, written and configured in Java itself. It builds **modular projects**
out of the box (anything whose modules declare themselves via `module-info.java`) and also understands the
declarative slices of a `pom.xml` - descriptive metadata, plugin-free dependency lists, parent coordinates - so
a Maven-shaped project that does not lean on plugin lifecycles can be built without conversion. Pointed at a
project root containing a `module-info.java`, a `pom.xml`, or both, Jenesis discovers the multi-project graph
automatically and wires the matching compile, package, and (where sources are present) test pipeline.

One design goal is to ship the build *with* the project as plain Java source, and not as a binary. The Jenesis
sources sit inside your repository under `build/jenesis/`, the launcher is the JVM's single-file mode
(`java build/jenesis/Project.java`), and the build is reproducible from a clone plus a JDK. There is no opaque
wrapper, no fetched plugin tree, no fetched daemon - which closes the supply-chain surface that wrappers and
plugin resolvers otherwise expose. Shipping the build as plain source also keeps it fully modifiable where
needed: humans (and AI agents) can adjust how a project is built by implementing build steps in ordinary Java,
without a large API to learn first.

A second design goal is that the build is naturally incremental at every step and naturally produces reproducible
outputs. Each build step's inputs, outputs, and configuration are content-hashed, so unchanged work is reused
unchanged from the previous run, and identical inputs always reproduce identical outputs. That same posture is
what makes Jenesis strongly security-focused: dependencies can be pinned not only by version number but also by
the checksum of every downloaded artifact, so a build is naturally resistant to supply-chain attacks on its
inputs. Pinning at that level of detail is itself a consequence of embedding the build tool inside the project,
since the pin set lives in the same committed sources as everything else. The combination of plain-Java
sources and content-hashed steps also lays a foundation for optimising complex builds: a non-trivial custom
build can itself be compiled ahead of time, or shipped as a native image for environments that run the same
build at high frequency (a CI server, for example), and step outputs - being pure functions of their
content-hashed inputs - are easy to share between builds as a cache. A Jenesis build requires a JVM of
version 25 or newer, but nothing else.

### Installing

Three equivalent ways to populate `build/jenesis/` inside your project. All three land at the same on-disk
state, so the canonical `java build/jenesis/Project.java` invocation works identically afterwards:

**curl-piped bootstrap.** Fastest, no prerequisites beyond a JDK and `curl`. Run from your project root:

    curl -fsSL https://get.jenesis.build | bash
    java build/jenesis/Project.java

Set `JENESIS_VERSION=X.Y.Z` to pin a specific version. The script is `install.sh` at the repository root.

**Git submodule.** Most explicit; the pinned submodule commit is the reproducibility anchor, so a fresh clone
plus `git submodule update --init` is the entire setup with no separate install step:

    git submodule add https://github.com/raphw/jenesis.git .jenesis
    ln -s ../.jenesis/sources/build/jenesis build/jenesis
    java build/jenesis/Project.java

On platforms without symlink support, replace the `ln -s` with `cp -r .jenesis/sources/build/jenesis build/jenesis`
and refresh after each submodule update.

**SDKMAN.** Best fit when you would rather manage versions globally instead of vendoring sources per project.
Install once, then initialise each consuming project from the SDK:

    sdk install jenesis
    jenesis-init                       # from your project root
    java build/jenesis/Project.java    # or just 'jenesis', equivalent

`jenesis-init` populates `build/jenesis/` from the installed SDK. The companion scripts `jenesis-validate`,
`jenesis-version`, and `jenesis-switch` are documented under [Using Jenesis as a CLI](#using-jenesis-as-a-cli).

You can also skip the embedding entirely and run `jenesis` directly from a project root: the SDK's own copy
of `Project.main(...)` is invoked against the current directory, with no `build/jenesis/` written. Customisation
is then limited to system properties (`-Djenesis.project.layout=...` and friends); custom builders and
hand-wired `.java` files under `build/` are not reachable. Useful for quick trials and for building projects
with an untrusted build source, where Jenesis itself stays the trusted, SDK-installed copy.

### Example: Building Jenesis itself

A clone of this repository is the easiest working example. Sources live under `sources/` and tests under
`tests/`, with a `module-info.java` in each (`build.jenesis` and `build.jenesis.test`) and a single root
`pom.xml` that points at both directories. The same canonical invocation builds it:

    git clone https://github.com/raphw/jenesis.git
    cd jenesis
    java build/jenesis/Project.java

The auto-detected layout is `MAVEN`, since the root `pom.xml` takes precedence over a nested module-info.
The build compiles main and test sources, runs the tests, and writes artifacts under `target/`. Try
`java build/jenesis/Project.java stage` to materialise the release tree pushed to Maven Central, or browse
`metadata.properties` and the module-info javadoc to see how descriptive metadata flows into the emitted POM.

### Customizing the build

Customisation comes in three stages, picked by how far from the auto-wired pipeline you need to go.

**1. System properties on the canonical launcher.** When the project shape is fine but a knob needs flipping -
skip tests, force a layout, route `target/` elsewhere - pass `-Djenesis.project.*` flags. No Java code, no
separate entry point:

    java -Djenesis.project.skipTests=true \
         -Djenesis.project.layout=MODULAR_TO_MAVEN \
         build/jenesis/Project.java

`Project.main(...)` calls `resolveProperties()` first, which maps `jenesis.project.*` onto the corresponding
`Project.Builder` setters. The full list is in [Configuration](#configuration). `jenesis.project.root` also
lets you target a project that lives outside the directory holding `build/jenesis/`:

    java -Djenesis.project.root=/path/to/other/project build/jenesis/Project.java

**2. Custom entry point under `build/`.** When you want code-level control - a tailored assembler, an extra
step on top of the default per-module pipeline - drop a `.java` file alongside `Project.java` and use the
Builder there. Run it the same way (`java build/MyBuild.java`):

```java
package build;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Project;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;

public class MyBuild {

    static void main(String[] args) throws IOException {
        MultiProjectAssembler<ProjectModuleDescriptor> base = new JavaMultiProjectAssembler();
        MultiProjectAssembler<ProjectModuleDescriptor> withSign = (descriptor, repos, resolvers) -> {
            BuildExecutorModule delegate = base.apply(descriptor, repos, resolvers);
            return (sub, inherited) -> {
                sub.addModule("assemble", delegate, inherited.sequencedKeySet().stream());
                sub.addStep("sign", new Sign(), "assemble"); // Sign is a user-defined BuildStep
            };
        };
        Project.builder()
               .assembler(withSign)
               .build(args);
    }
}
```

The wrapper registers `JavaMultiProjectAssembler`'s output as a nested module named `assemble` and then
chains a `sign` step that depends on it. Assemblers compose this way freely: each layer registers its
delegate as a sub-module and adds its own steps next to it, so you can stack multiple decorators (sign,
attach licence headers, emit checksums) on top of a base assembler without subclassing. Jenesis itself
relies on the same pattern internally - the `MAVEN` and `MODULAR_TO_MAVEN` layouts transparently wrap the
user's assembler with `PomAwareAssembler`, which registers the user-supplied assembler under `assemble/`
and emits the per-module POM alongside it.

**3. Hand-wired build on the `BuildExecutor` API.** When auto-detection is not the right starting point at
all - a non-Java pipeline, a wildly custom graph, or you just want the primitives - bypass `Project` and
wire the build yourself:

```java
package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.step.Bind;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;

public class Hand {

    static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addSource("sources", Bind.asSources(), Path.of("sources"));
        root.addStep("classes", Javac.tool(), "sources");
        root.addStep("artifacts", Jar.tool(Jar.Sort.CLASSES), "classes");
        root.execute(args);
    }
}
```

`BuildExecutor.of(Path.of("target"))` is the root of the graph and writes all outputs under `target/`.
`addSource` binds an input directory through a `Bind` step so changes to the path invalidate downstream
caches; `addStep` chains a `BuildStep` whose argument list names its predecessors (`"sources"` for
`classes`, `"classes"` for `artifacts`); `execute(args)` runs the requested target (or the whole graph by
default), reusing cached outputs whose inputs have not changed. The full primitive set is documented under
[Architecture](#architecture), [Build steps](#build-steps), and [Build executor modules](#build-executor-modules),
and the example launchers under `build/` (`Minimal.java`, `Manual.java`, `Maven.java`, `Modular.java`,
`ModularToMaven.java`, `Modules.java`) are progressively richer working starting points.

### Selectors

`Project.main(args)` and `Builder.build(args)` accept selector strings as positional arguments. The canonical
example is `stage`, which runs the full release recipe (build → stage) and materialises a Maven-shaped tree
under `target/stage/output/`:

    java build/jenesis/Project.java stage

Without arguments, `Project` runs whatever its `defaultTarget` is set to. Out of the box that is `"build"`,
which compiles and packages every discovered module but stops short of the downstream `stage` step.
`Project.Builder.defaultTarget(...)` changes the default (there is no matching system property). The other
top-level targets the shipped layouts register are `export` (only on `MAVEN` and `MODULAR_TO_MAVEN`; publishes
the staged tree into the local Maven repository) and `pin` (rewrite every `pom.xml` / `module-info.java` so
the full transitive closure is pinned at source level).

**Module selectors.** Selectors that start with `+` are rewritten by the active layout into the per-project
module path of that name, so a single module can be built without dragging its siblings in. The shipped
layouts encode names as `module-<URLEncode(name)>` and place them under their per-project aggregator:

- `+sources` resolves to `build/modules/compose/module/module-sources` under `MODULAR` and `MODULAR_TO_MAVEN`,
  or to `build/maven/compose/module/module-sources` under `MAVEN`.
- `+` alone resolves to `module-` (trailing empty segment), the identity Maven's scanner produces for the root
  POM in a multi-module Maven layout. A pure modular project has no such root, so `+` alone will not resolve
  there.

The rewriter always yields a literal path, which avoids the lenient cascade that a bare module name would
trigger across sibling modules.

**General syntax and wildcards.** Under the hood every selector is a slash-delimited path of step identities
(`module/step`) that the executor matches against the registered graph. Two wildcards are supported:

- `:` matches a single path segment, so `build/:/java` matches the `java` module of every direct child of
  `build`.
- `::` matches any depth (zero or more segments), so `::/sign` matches every `sign` step anywhere in the
  tree.

Wildcards are lenient: branches that fail to match are silently skipped. A literal path that does not
resolve throws. Once a step is matched, its transitive preliminary closure runs unconditionally, so its
inputs are real folders rather than lenient-skipped placeholders. The full mechanics, including how sibling
modules along a wildcard path still have their `accept(...)` invoked, are documented under
[`BuildExecutor`](#buildexecutor) and [Selectors on the command line](#selectors-on-the-command-line).

### Layouts and assemblers

Two callbacks govern how the build is assembled, and they are pluggable independently:

- `Project.Layout` (set via `.layout(...)`) wires the top-level pipeline (the `download` step where applicable, the
  `build` multi-project module, the `stage` step that walks per-module inventories, and on the Maven layouts the
  `export` step that publishes the staged tree) and returns the `Function<String, String>` that expands
  `+`-prefixed selectors. The shipped constants `Layout.MAVEN`, `Layout.MODULAR`,
  `Layout.MODULAR_TO_MAVEN` mirror `build/Maven.java`, `build/Modular.java`, and `build/ModularToMaven.java`.
  `Layout.AUTO` (the default) calls `Layout.of(root)` and dispatches to one of the concrete layouts;
  `MODULAR_TO_MAVEN` is reachable only explicitly, because its on-disk signature (`module-info.java` +
  `pom.xml`) is indistinguishable from a pure modular project that keeps a `pom.xml` for IDE support.
- `MultiProjectAssembler<D extends ModuleDescriptor>` (set via `Project.Builder.assembler(...)`) wires the per-project
  sub-graph: what each discovered module compiles, packages, and tests. The assembler's
  `apply(D descriptor, Map<String, Repository> repositories, Map<String, Resolver> resolvers)` receives the
  per-module descriptor *and* the per-module merged repositories/resolvers (the layout-level maps with each
  sibling sub-module's `assign` URI prepended, so a coordinate resolved locally never falls back to the global
  repository). `Project` parameterises this over `ProjectModuleDescriptor`, a record that wraps the layout's
  base descriptor (`MavenProject.MavenModuleDescriptor` or `ModularProject.ModularModuleDescriptor`) and adds
  the project-level flags `tests`, `source`, `javadoc`. The default assembler `JavaMultiProjectAssembler` is
  stateless and reads those flags off the descriptor it receives - no `Context` object: a `prepare` step plus a
  `JavaModule` is wired against the six descriptor paths and the module's resources, and when `descriptor.test()`
  is set and the module's `module.properties` flags it as a test variant a `TestModule` sub-module is wired
  alongside the `JavaModule`, with optional `sources` and `javadoc` steps appended when the matching flag is set. The `MAVEN` and `MODULAR_TO_MAVEN` layouts wrap the
  user's assembler with a `PomAwareAssembler` that emits a per-project `pom` step seeded with project-wide
  metadata read once from `metadata.properties` (when configured); `MODULAR` does not. Each layout adds a
  top-level `pin` module (sibling of `build`) that walks the BUILD outputs and rewrites every discovered
  `pom.xml` / `module-info.java` so the full transitive closure (with checksums where available) is pinned at
  source level. Pin is opt-in - it's not part of the default target - and it skips coordinates that come from
  within the project (i.e. anything advertised through an `assign` step's `identity.properties`), so internal
  modules never leak into the dependency-management block.

Layouts always combine their built-in repositories and resolvers (e.g. a Maven default for `MAVEN`, a chained
Jenesis module repository for `MODULAR`) with any user-provided ones. The merged map then has each sub-module's `assign`
URI prepended inside `MavenProject.make` / `ModularProject.make` and is handed to the assembler per call. User
entries with the same key override the layout default.

| Layout               | Pipeline                                                                                  | Mirrors                |
| -------------------- | ----------------------------------------------------------------------------------------- | ---------------------- |
| `Layout.MAVEN`       | **Input: `pom.xml`. Output: classic JAR + `pom.xml`.** `MavenProject` scan + per-project `JavaModule` + per-module `Pom` step + `MavenRepositoryStaging` + `MavenRepositoryExport` | `build/Maven.java`     |
| `Layout.MODULAR`     | **Input: `module-info.java`. Output: modular JAR (no `pom.xml`).** `ModularProject` over `JenesisModuleRepository` (public overlay, cached under `.jenesis/cache/`) with `JenesisModuleRepository.ofLocal()` prepended + per-project `JavaModule` + `ModularStaging` + `JenesisModuleRepositoryExport` | `build/Modular.java`   |
| `Layout.MODULAR_TO_MAVEN` | **Input: `module-info.java`. Output: modular JAR + `pom.xml`.** `DownloadModuleUris` + `ModularProject` against a `MavenDefaultRepository` (`MavenPomResolver` translated through `MavenUriParser`), with a per-module `Pom` step on top of the assembler, plus `MavenRepositoryStaging` + `MavenRepositoryExport` | `build/ModularToMaven.java` |
| `Layout.AUTO` (default) | Detection: a root `pom.xml` → `MAVEN`; else any `module-info.java` under the root → `MODULAR`. Trees rooted at a nested `.jenesis.build` marker are skipped. Falling through throws. | - |

`MODULAR_TO_MAVEN` resolves the transitive closure through `MavenPomResolver` against a `MavenDefaultRepository`,
so versions follow Maven's nearest-wins rules and dependency management - not the Java module system's
single-binding requirement. The resolver does not check that every transitive jar carries a `module-info.class`
or a manifest `Automatic-Module-Name`, and Maven coordinates do not encode a Java module name, so the resolved
set may include plain classpath jars or coordinates whose filename is not a legal automatic module name. The
artifact may still be module-path-consumable in practice; the layout simply does not prove it. For this reason
the layout omits `prefix.module` from `inventory.properties` and `Execute` launches the staged jar on the
classpath rather than via `--module-path`. `MODULAR` is the layout that resolves only against a module-name
registry and so guarantees a module-path-consumable closure.

All three concrete layouts run a `stage` step that depends directly on `BUILD` and materializes the staged
tree under `target/stage/output/` by walking every per-module `inventory.properties` the assembler produced.
The staging shape differs by layout:

- `MAVEN` and `MODULAR_TO_MAVEN` use `MavenRepositoryStaging`. For each main module it parses `prefix.pom`
  for `groupId` / `artifactId` / `version` and hardlinks the artifacts as
  `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>.<ext>` (suitable for upload to a Maven
  repository). Test variants (those whose inventory carries a `prefix.test=<main-artifactId>` marker) are
  routed onto the main coordinate with a `-tests` classifier, and the test module's `pom.xml` is parsed for
  its dependencies, which are appended to the staged main POM with `<scope>test</scope>`. The follow-up
  `MavenRepositoryExport` step copies the staged tree into the local Maven repository (default
  `~/.m2/repository`, overridable via `MAVEN_REPOSITORY_LOCAL`) with the right `maven-metadata-local.xml` and
  `_remote.repositories` markers.
- `MODULAR` uses `ModularStaging`. For each module's inventory it reads `prefix.module` (the Java module system module name)
  and the optional `prefix.version`, then hardlinks the artifacts as `<module>/<module>.jar` (plus
  `-sources.jar` / `-javadoc.jar` siblings when produced). When `prefix.version` is present, the version is
  inserted as one extra path segment: `<module>/<version>/<module>.jar`. There is no `pom.xml` to anchor a
  Maven coordinate. The follow-up `JenesisModuleRepositoryExport` step copies that staged tree into the
  local Jenesis module repository (default `~/.jenesis`, overridable via the `JENESIS_REPOSITORY_LOCAL`
  environment variable), preserving the same `<module>[/<version>]/` shape. When a module is versioned, its
  files are *also* mirrored to the unversioned `<module>/` root so the module root always reflects the most
  recently built version (a subsequent build of the same module overwrites the root regardless of which
  version it produces). Each target directory written in a run is cleaned of pre-existing regular files
  before the new ones are linked in, so a build that no longer produces a `-javadoc.jar` does not leave a
  stale one behind; sibling version directories (e.g. `<module>/0.9/` while exporting `<module>/1.0.0/`) are
  untouched.

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

### Running a module's main entry

`build/jenesis/Execute.java` is a companion launcher to `Project.java`. It runs the build first, finds the
module that declares a `@jenesis.main` (in its `module-info.java`) or `<mainClass>` (in its `pom.xml`), and spawns a
child `java` process for it, forwarding any trailing arguments to the program:

    java build/jenesis/Execute.java arg1 arg2

If exactly one module in the project declares a main, Execute selects it implicitly. If several do, it aborts
and lists the candidates; pass `-Djenesis.execute.module=<path>` (the same path you would use after `+` in a
build selector) and `-Djenesis.execute.mainClass=<fqcn>` to specify the target explicitly. Doing so also
narrows the build to that module's subtree, skipping siblings:

    java -Djenesis.execute.module=tools \
         -Djenesis.execute.mainClass=org.example.tools.Cli \
         build/jenesis/Execute.java --help

Execute can also run the launched program inside a container, independently of whether the build itself was
dockerised. Set `-Djenesis.execute.docker=true` to dispatch the final `java -m <module>/<main>` (or `java -cp
... <main>`) invocation through Docker, with `-Djenesis.execute.docker.image=<reference>` overriding the
image. The build runs as usual (locally, or in `jenesis.project.docker.image` if set), and only the launch
step crosses the container boundary, so the build image and the runtime image can differ.

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
changed).

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

Declaring an explicit `serialVersionUID` on a `BuildStep` is the Java-native equivalent of adding a manual
version field to the type: it replaces the JVM-computed shape fingerprint with a pinned value the author
maintains by hand. The trade-off is real, because the auto-computed UID is the only part of the default
serialization stream that tracks method signatures at all. The class descriptor itself records only the class
name, flags, non-transient field shape, and superclass chain, never methods or bodies. Pinning a UID therefore
removes the cache's only handle on behavioural changes: a step whose `execute(...)` gains parameters, whose
helpers change signature, or whose superclass adds a method then hashes identically to the prior version, and
stale outputs may be reused. The author is then responsible for bumping the UID by hand on every
behaviour-affecting change. The implicit UID is not perfect either, since it does not recurse into superclasses
or interfaces and ignores method bodies, but it catches more accidental drift than a pinned value and is the
default `BuildStep` authors should rely on. Pin one only when stream stability across JVMs or compiler versions
outweighs the loss of automatic discovery, and treat the value as something you bump by hand thereafter; once
an explicit UID is declared the JDK no longer computes the implicit one, and there is no supported way to ask
`ObjectOutputStream` what it would have been.

The executor places a `.jenesis.build` marker at the build root so source scanners (`MavenProject`,
`ModularProject`) can skip nested builds, stores all per-step state under `target/`, and uses `.jenesis/cache/`
by convention for cross-build caches such as downloaded module URIs.

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
- **A module's exposed steps must not publish the same file at the same relative path in more than one of
  them.** Exposing several intermediate steps is *not* a problem by itself - a consumer that doesn't recognise
  a given file/folder convention just ignores those entries. The problem is when two of a module's exposed
  steps both write, say, `versions.properties` at the same relative path: a consumer iterating
  `inherited.values()` and resolving `folder.resolve(BuildStep.VERSIONS)` will find that file twice with
  possibly different content (typically an early-pipeline placeholder and a later-pipeline refined version),
  and which one wins depends on iteration order. Override `resolve(String path)` to return `Optional.empty()`
  for any leaf whose exposure would create such a collision, keeping only the step that holds the **final**
  state of each file. A chain like `Resolve` -> `Download` where each step rewrites `requires.properties` /
  `versions.properties` should expose only the downstream `Download` leaf; the upstream `Resolve` output stays
  available to its in-module successor by name but disappears from the module's published map. Leaves whose
  files don't collide with any sibling can stay exposed unchanged. `ExternalModule` is the strict end of the
  spectrum: it hides every internal node (`coordinate`, `dependencies`, `external`, `delegate`) and republishes
  the delegated module's leaves under its own registered name (see [`ExternalModule`](#externalmodule)).
- **Define each step-name constant once, at the class that adds the step**, and have all consumers reference
  that constant. `MultiProjectModule.IDENTIFIER` / `.COMPOSE` / `.MODULE` belong on `MultiProjectModule`
  because that's the framework that wires those sub-modules; `DependenciesModule.RESOLVED` / `.ARTIFACTS`
  belong on `DependenciesModule` because that's where the steps are added. The per-scope sub-module folder
  names are derived from `DependencyScope.label()` rather than living as separate constants. A class that
  wants to point at a predecessor's leaf step uses the owner's constant - no separate "same string" duplicate.
- **`*.properties` files exchanged between steps in different files should have a documented schema.** The
  conventional files (`identity.properties`, `module.properties`, `metadata.properties`, `requires.properties`,
  `versions.properties`, `scopes.properties`) are listed in the table below with their produced/consumed keys
  and value semantics. The filenames live as constants on `BuildStep`; each property key's contract belongs in
  the README rather than as a magic string scattered across writer and reader sites.
- **Paths inside a properties file should be self-anchored: written relative to that file's own folder.** A
  consumer resolves the path with `<file's parent folder>.resolve(<value>).normalize()` and never depends on
  the absolute layout of `target/` or on where the file happens to live in the build graph. Writers achieve
  this by `context.next().relativize(absolutePath)` before storing the value. This is what `process/*.properties`
  does for command-line path fragments, what `identity.properties` does for assigned artifact paths, and what
  `inventory.properties` does for `artifact*`, `pom`, and `runtime`. The convention is load-bearing for
  reproducible builds: it means the same folder tree linked, copied, or mounted under a different absolute
  prefix continues to work without rewriting any properties file, and a step's output is therefore safe to
  hard-link into another build's cache, ship between machines, or move between `target/` directories. The
  inverse - storing absolute paths or paths anchored to some shared root - couples the file's validity to
  its physical location and breaks the moment the build tree moves.
- **Schema-level vocabulary in those properties files is matched as literal strings.** The values written to
  `scopes.properties` (e.g. `COMPILE`, `RUNTIME`) are an open-ended token set documented in the table below;
  new steps and producers are free to introduce additional tokens without touching the shared `DependencyScope`
  enum. Today's producers and consumers happen to use `DependencyScope.COMPILE.name()` /
  `DependencyScope.RUNTIME.name()` to derive the string, which keeps writer and reader spellings in sync
  without forcing every participant to depend on the enum (the wire format is the string, not the enum value).
  Property-file tokens are written in upper case (`COMPILE`, `RUNTIME`) to keep them visually distinct from
  the lower-case sub-module folder names (`compile/`, `runtime/`) that share the same root word and to reduce
  the chance of a typo silently matching. The general infrastructure (`BuildExecutor`, `BuildStep`, the
  `scopes.properties` file format) does not enforce a closed token set: only the bundled `MavenProject.make` /
  `ModularProject.make` wiring and its helpers (`MultiProjectDependencies`, `Pom`) reference
  `DependencyScope`, and they only consume the tokens they know about. A custom project type or layout that
  supplies its own `Manifests` step, its own per-scope prepare step, and its own consumer (or skips `Pom`
  entirely) can introduce additional scope tokens with no framework-level changes; the `DependencyScope` enum
  is a convenience for the bundled flow, not a global registry.

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
| `sources/`                 | `BuildStep.SOURCES`              | A directory tree of `.java` source files (mirroring their package structure) consumed by compilation and documentation tooling. The same folder name is also the conventional output location for the packaged source jar produced by `Jar.tool(Jar.Sort.SOURCES)`, which writes a single `sources.jar` file alongside the tree at `sources/sources.jar`. A sources jar is not a deployable artifact, so it lives next to the source tree rather than in `artifacts/`. |
| `resources/`               | `BuildStep.RESOURCES`            | A directory tree of non-source files (configuration, message bundles, static assets) that should appear on the classpath alongside compiled classes and be embedded into produced jars.                                                              |
| `classes/`                 | `BuildStep.CLASSES`              | A directory tree of compiled `.class` files in their package layout, plus any non-source companion files copied verbatim from `sources/`. Forms a class- or module-path entry for downstream compilation, packaging and execution.                  |
| `artifacts/`               | `BuildStep.ARTIFACTS`            | A flat directory holding **the module's own produced binary jars** (typically just `classes.jar`, emitted here by `Jar.tool(Jar.Sort.CLASSES)`). Downloaded dependency jars deliberately do not live here, see `dependencies/`. Source jars and documentation jars do not live here either, since they are not deployable binaries, see `sources/` and `documentation/`. |
| `dependencies/`            | `BuildStep.DEPENDENCIES`         | A flat directory holding **downloaded dependency jars** that `Download` placed for this step (every transitive jar pulled in for the configured scope, whether external Maven or a sibling module's binary that was resolved by coordinate). Downstream classpath/module-path consumers (`Javac`, `Java`, `Javadoc`, `TestEngine`) walk both `artifacts/` and `dependencies/` to assemble the full set of jars. |
| `javadoc/`                 | `Javadoc.JAVADOC`                | A generated Javadoc tree (HTML, CSS and supporting resources), ready to be archived into a documentation jar or served as static content.                                                                                                            |
| `documentation/`           | `BuildStep.DOCUMENTATION`        | Conventional output location for packaged documentation. `Jar.tool(Jar.Sort.JAVADOC)` writes `documentation/javadoc.jar` here, distinct from both the generated tree under `javadoc/` and from `artifacts/` (a javadoc jar is documentation, not a deployable binary).                                                                  |
| `groups/`                  | `Group.GROUPS`                   | One `<encoded-group-name>.properties` file per identified group, listing the other groups whose coordinates the group transitively depends on so cross-project wiring can be derived purely from on-disk state.                                      |
| `pom/`                     | `MavenProject.POM`               | A mirror of the directory layout of a Maven multi-module project, with each `pom.xml` hard-linked from its original location to give downstream tooling a stable, sandboxed snapshot of the project's POM tree.                                      |
| `maven/`                   | `MavenProject.MAVEN`             | One properties file per discovered Maven module (`module-<encoded-path>.properties` for the main artifact, `test-module-<encoded-path>.properties` for the test artifact), holding the parsed coordinate, source/resource directories, packaging and dependency list extracted from a single `pom.xml`. |
| `identity.properties`      | `BuildStep.IDENTITY`             | `<prefix>/<coordinate>` keys (e.g. `maven/groupId/artifactId/[type/[classifier/]]version` or `module/<java-module-name>`) mapped to either an empty value (artifact not yet built; identifies the project's own coordinate) or the absolute filesystem path of an already-built jar.                          |
| `requires.properties`      | `BuildStep.REQUIRES`             | Same `<prefix>/<coordinate>` keys as `identity.properties`, mapped to either an empty value (no integrity validation requested) or an `<algorithm>/<hex>` content checksum that `Download` verifies against the downloaded artifact (mismatch fails the build). Checksums are pinned in source by the user: a `<!--Checksum/<algorithm>/<hex>-->` comment inside a POM `<dependency>` element, or a `<!--Checksum/...-->` inside `<dependencyManagement>` (which propagates to whichever transitive resolves to that coord), or an `@jenesis.pin <module> <version> <algorithm>/<hex>` Javadoc tag in `module-info.java`. Checksums are computed once, by the `pin` step: `PinPom` / `PinModuleInfo` rehash every resolved jar in the upstream `artifacts/` folders using `-Djenesis.project.pinAlgorithm` (default `SHA-256`) and write the result back into `pom.xml`'s `<!--Checksum/...-->` comments or `module-info.java`'s `@jenesis.pin <module> <version> <algorithm>/<hex>` Javadoc tags. `Download` then validates every subsequent fetch against the pinned checksum (mismatch fails the build); a coordinate that still has no pinned checksum is downloaded without integrity validation - or, when strict pinning is enabled (via `Project.strictPinning(true)` / `-Djenesis.project.strictPinning=true`, propagated through `MavenProject.make` / `ModularProject.make` / `DependenciesModule` and through `ProjectModuleDescriptor` / `JavaMultiProjectAssembler` / `TestModule` into every `Download` instance), the build fails. After `Resolve` runs, module-style coordinates carry an optional trailing `/<version>` segment (`module/org.junit.jupiter/5.11.3`) reflecting the version a resolver chose for that module. |
| `versions.properties`      | `BuildStep.VERSIONS`             | `<prefix>/<version-less-coordinate>=<version>[ <algorithm>/<hex>]` entries that act as a *bill of materials* for the resolution that follows: every resolver receives this map alongside `requires.properties` and uses the version part to pin any (declared or transitive) dependency that matches the bare coordinate. The optional space-separated `<algorithm>/<hex>` suffix is the pre-pinned content checksum for that coordinate; resolvers carry it through into the resolved `requires.properties` value so `Download` validates the bytes against it. For Maven the key is `groupId/artifactId[/type[/classifier]]`; for modules it is the bare Java module name. The file is written next to `requires.properties` by producers that have version data to contribute (`ModularProject` from `@jenesis.pin` Javadoc tags, `MavenProject` from `<dependencyManagement>`). |
| `scopes.properties`        | `BuildStep.SCOPES`               | Sibling of `requires.properties` produced by the `Manifests` steps in `ModularProject` and `MavenProject`. Each key is a `<prefix>/<coordinate>` from `requires.properties`; the value is a comma-separated list of scope tokens describing in which scopes the dependency is visible. The token set is open-ended (matched as literal strings) so additional steps can introduce their own scope tokens later. The currently recognized tokens are the upper-case `DependencyScope` enum names `COMPILE` and `RUNTIME`: compile-only entries (Maven `provided`, Java `requires static`) carry just `COMPILE`; runtime-only entries (Maven `runtime`) carry just `RUNTIME`; entries visible in both carry `COMPILE,RUNTIME`. `MultiProjectDependencies` filters `requires.properties` against the `DependencyScope` it is bound to; `Pom` reads it to decide whether each dependency is emitted as `compile`, `provided`, or `runtime`. The upper-case spelling distinguishes property-file content from the lower-case sub-module folder names (`compile/`, `runtime/`) that share the same root word. |
| `exclusions.properties`    | `BuildStep.EXCLUSIONS`           | Sibling of `requires.properties` produced by `MavenProject.Manifests` (only when a dependency declaration in a `pom.xml` carries `<exclusions>`). Each key is a `<prefix>/<coordinate>` from `requires.properties`; the value is a comma-separated list of `<groupId>/<artifactId>` patterns that the resolver must subtract from this dependency's transitive closure (so e.g. `mockito-core <exclusion>net.bytebuddy/byte-buddy</exclusion>` does not silently re-pull `byte-buddy` through the test classpath). `MultiProjectDependencies` carries the entries through to the per-scope prepare step alongside the matching `requires.properties` rows; `Resolve` reads the file from its arguments and threads the exclusion set per coordinate into `Resolver.dependencies`, where `MavenPomResolver` populates `MavenDependencyValue.exclusions` so the transitive walk honours them. `ModularJarResolver` rejects any non-empty exclusion set up front because Java modules have no exclusion concept. `Pom` reads the file to emit each `<dependency>` with its declared `<exclusions>` so consumers of the published POM keep the same closure. The file is omitted entirely when no dependency in the module declares exclusions. |
| `module.properties`        | `BuildStep.MODULE`               | Per-module **graph-state** descriptor written by every `Manifests` step. Carries only keys the framework manages, never the user. Always present with `path=<directory-relative-to-project-root>` (the source folder housing this module's `pom.xml` / `module-info.java`). `ModularProject.Manifests` also writes `module=<java-module-name>`. Test variants additionally carry `test=<artifactId>` (or the empty string for the deprecated bare `@jenesis.test` form); the key is absent on main modules, and consumers (`Pom`, `JavaMultiProjectAssembler`, `Inventory`) use that absence/presence as the test-variant signal, with `Inventory` mirroring the value into `inventory.properties` as `prefix.test` so `MavenRepositoryStaging` and `ModularStaging` can route test modules at staging time. Modules with an entry point carry `main=<class>` on the **main** variant (omitted on test variants): `ModularProject.Manifests` populates it from an `@jenesis.main <class>` Javadoc tag on `module-info.java`, `MavenProject`'s per-module manifests step populates it from a `<properties><mainClass>...</mainClass></properties>` entry in the module's `pom.xml`. `JavaMultiProjectAssembler` runs a `prepare` step that translates `main` into a `process/jar.properties` file with a `--main-class=<class>` flag; the existing `ProcessBuildStep` plumbing then prepends that flag to the `jar` command line, which makes the produced `classes.jar` carry both a manifest `Main-Class:` entry and a `ModuleMainClass` attribute on the bundled `module-info.class`. `Project.PinModule` reads `path` from every input folder that carries this file to discover which source files to pin without pattern-matching graph paths. |
| `metadata.properties`      | `BuildStep.METADATA`             | Per-module **POM coordinates and descriptive metadata** written by every `Manifests` step. Always carries the three coordinate keys `project=<groupId>`, `artifact=<artifactId>`, `version=<version>`: `MavenProject`'s per-module manifests step copies them straight from the `pom.xml`, while `ModularProject.Manifests` derives them from the Java module system module name (first two dot-separated segments for `project`, the full name for `artifact`) and defaults `version` to `0-SNAPSHOT`. On top of the coordinates the step adds whatever descriptive metadata is available: `ModularProject.Manifests` parses `name` and `description` from the module-info Javadoc; `MavenProject`'s manifests step lifts `<name>`, `<description>`, `<url>`, every `<license>` (as `license.<id>.name` / `license.<id>.url`, where `<id>` is the license name lowercased with spaces and dots replaced by `_`), every `<developer>` (as `developer.<id>.name` / `developer.<id>.email`), and the `<scm>` block (`scm.connection`, `scm.developerConnection`, `scm.url`) from the module's `pom.xml`. After the framework's own defaults are written, the step folds any upstream `metadata.properties` from its input folders on top (later puts win), which is how user-supplied overrides take precedence over both the framework defaults and the POM-extracted values. `Pom` consumes the file as the single source of truth for the emitted pom and throws if any of `project` / `artifact` / `version` is missing. The optional project-root override file (conventionally `project.properties`, pointed at via `-Djenesis.project.metadata=<path>`) uses the same key schema and is bound into the executor's `metadata` module so its entries reach every per-module `metadata.properties` as upstream input; `-Djenesis.project.version=<v>` is appended last and overrides any `version` from either layer. |
| `inventory.properties`     | `Inventory.INVENTORY`            | Per-module **launchable and stageable summary** written by `Inventory`. Each module produces one file whose keys carry a single-segment prefix derived from the module's path: `module` for the root module (empty `path`), `module-<path>` otherwise (e.g. `module-core`). Keeping the prefix dot-free lets a consumer recover the prefix from any key by taking the substring up to the first `.`. The three folder-listing keys each carry a comma-separated list of files found in the matching folder among the inventory's predecessors: `prefix.artifacts` (the contents of every `artifacts/` folder, i.e. the module's produced binary jars; the staging steps require exactly one entry ending in `.jar`), `prefix.sources` (the contents of every `sources/` folder, typically just the produced `sources.jar`; the staging steps require at most one entry ending in `.jar`), `prefix.documentation` (the contents of every `documentation/` folder, typically just the produced `javadoc.jar`; same at-most-one-jar rule). Plus the existing scalar keys: `prefix.pom` (path to the generated `pom.xml` when the layout emits one), `prefix.version` (mirror of `metadata.properties`' `version`), `prefix.test` (mirror of `module.properties`' `test`, set only on test modules), `prefix.module` (mirror of `module.properties`' `module`, omitted under `MODULAR_TO_MAVEN` because that layout publishes via Maven coordinates and consumers may not place the artifact on the module path), `prefix.mainClass` (mirror of `module.properties`' `main`), and `prefix.runtime` (comma-separated jar paths: the binary artifact followed by every file in any `dependencies/` folder — the runtime classpath that `Execute` uses). All path values are **self-anchored**: written relative to the inventory file's own folder, and consumers resolve them with `<inventory's parent>.resolve(value).normalize()`. Any key whose value would be empty is omitted entirely. A consumer that reads several modules' inventories can `putAll` them into one `Properties` map without key collisions, then group by prefix to recover per-module records. Consumers: `Execute` picks candidates with `prefix.mainClass` set and assembles the classpath/modulepath from `prefix.runtime`; `MavenRepositoryStaging` parses `prefix.pom` for coordinates, routes by `prefix.test`, validates the folder-listing keys, and hardlinks the single jars into the Maven repository layout; `ModularStaging` reads `prefix.module` plus optional `prefix.version`, validates the folder-listing keys, and hardlinks the single jars under `<moduleName>/[<version>/]`. |
| `uris.properties`          | `DownloadModuleUris.URIS`        | `<prefix>/<java-module-name>` keys mapped to an absolute jar URL; populated from line-based `<module>=<url>` registries (default: sormuras/modules) and used during dependency resolution to translate a Java module name into a download URL. When a versioned coordinate is requested (e.g. `org.assertj.core/3.27.0`) and the bare name is mapped to a URL whose final path segments follow the Maven repository layout (`.../<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<ext>`), an opt-in version-resolver function (`MavenDefaultRepository.versionResolver()`) supplied by the caller rewrites the path's version segment and the filename's version segment to the pinned value, so a single-URL registry still satisfies version pins. Without that function, `Repository.ofUris` performs strict literal lookup only; if the version resolver is supplied but returns `Optional.empty()` for a versioned coordinate (e.g. the registered URL is not in Maven layout), the fetch is a clean miss - the bare-name URL is **not** silently substituted, so a build that asked for `foo/1.2.3` will never quietly receive the registry's default version. The standalone example script `build/Modular.java` passes this resolver explicitly when wiring `Repository.ofProperties`, since the dominant Java module URL registries (sormuras/modules and most internal mirrors) point at Maven Central -- making the Maven layout assumption visible at the use site rather than baked into the generic `Repository` infrastructure. The shipped `Layout.MODULAR` does not consume `uris.properties` directly anymore; its `module` prefix is served by the `https://repo.jenesis.build/modules/` overlay (which performs the same version rewrite internally), with `JenesisModuleRepository.ofLocal()` prepended. |
| `process/<command>.properties` | `ProcessBuildStep.PROCESS` (folder)  | Command-line fragments contributed to a downstream `ProcessBuildStep` whose tool name matches `<command>` (`java`, `javac`, `jar`, `javadoc`). Keys are flags (e.g. `--add-modules`); values are flag values, with literal `\n` inside a value emitting the same flag once per piece. Each input folder's file is processed independently and its entries are appended to the command line in folder order, so the same key in two folders becomes two flag instances. Values that name filesystem paths are written relative to the file's containing folder (paths are not resolved until the consumer step needs them), which keeps the on-disk content position-independent so build outputs can be relocated or shared between caches without rewriting.                                                                                          |
| `pom.xml`                  | `Pom.POM`                        | A generated Maven Project Object Model, ready to be packaged alongside a built jar so the artifact can be published to and consumed from any Maven-aware repository.                                                                                  |
| `target/`                  | (passed to `BuildExecutor.of`)   | The root folder under which every step's per-run output and the executor's incremental bookkeeping (output checksums and predecessor checksum snapshots used to decide whether a step needs to re-run) live. Safe to delete to force a clean build.   |
| `.jenesis/cache/`          | by convention                    | A project-root folder for caches that outlive a single build, hardlink-shared with `target/`. The `MODULAR` layout populates `.jenesis/cache/<encoded-coordinate>.jar` via `Repository.cached(...)` so module jars survive a `target/` wipe; `MAVEN` and `MODULAR_TO_MAVEN` cache into `~/.m2/repository` instead. Relocatable via `Project.cache(Path)` or `-Djenesis.project.cache=<path>`. See the *Repositories and resolvers* and *The `.jenesis/cache/` folder* sections below for the full picture. |
| `.jenesis.build`           | `BuildExecutor.BUILD_MARKER`     | An empty marker file placed at the root of an active build directory. Project-tree walkers honour it as a stop signal so nested builds aren't re-discovered as part of the parent build's project graph.                                              |

Build steps
-----------

The steps listed here are pre-implemented for convenience; the build tool itself does not depend on any of them, and a build is free to ignore them and supply its own `BuildStep` implementations.

| Step                       | What it does                                                                                                                                                                                   | Inputs (per predecessor folder)                                                                                                       | Outputs (under `context.next()`)                                                |
| -------------------------- |------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------| ------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `Bind`                     | Hard-links files from each predecessor into a target layout under `context.next()`, driven by a `Map<Path, Path>` that mirrors specific subtrees under canonical names (used by the static factories `asSources()`, `asResources()`, `asIdentity(...)`, `asRequires(...)`). | a source folder, a named properties file, or any other predecessor subtree named in the map                                          | `sources/`, `resources/`, `identity.properties`, `requires.properties`, or any layout produced by the configured map |
| `Javac`                    | Compiles each predecessor's `sources/` with the `javac` tool, using their `classes/` and `artifacts/` as class- or module-path entries; writes the resulting `.class` files to `classes/`.    | `sources/`, `classes/`, `artifacts/`                                                                                                  | `classes/`                                                                       |
| `Jar`                      | Packages the folders selected by the configured `Jar.Sort` into a single jar at the convention path that matches the sort: `CLASSES` writes `artifacts/classes.jar` (the deployable binary), `SOURCES` writes `sources/sources.jar` (alongside the source tree), `JAVADOC` writes `documentation/javadoc.jar` (alongside the docs tree); the latter two stay out of `artifacts/` since they are not deployable binaries. | per `Jar.Sort`: `CLASSES` reads `classes/` + `resources/`; `SOURCES` reads `sources/` + `resources/`; `JAVADOC` reads `javadoc/`         | `artifacts/classes.jar`, `sources/sources.jar`, or `documentation/javadoc.jar` (depending on `Jar.Sort`) |
| `Javadoc`                  | Invokes the `javadoc` tool over each predecessor's `sources/` and writes the generated documentation tree to `javadoc/`.                                                                       | `sources/`                                                                                                                            | `javadoc/`                                                                       |
| `Java`                     | Runs `java` with each predecessor's `classes/`, `resources/` and the jars in `artifacts/` assembled into a class- and module-path; the entry point and command line are supplied by subclasses or `Java.of(...)`. | `classes/`, `resources/`, `artifacts/`                                                                                                | runs `java`; no canonical output                                                 |
| `Resolve`                  | Reads `requires.properties` and (when present) `versions.properties`, asks each prefixed group's `Resolver` for the transitive closure with the version map as a pin set, and writes the resolved coordinates to a fresh `requires.properties` (module-style coordinates pick up a trailing `/<version>` segment when a version is known). Checksums supplied via the `versions.properties` `version checksum` suffix - or via comments in transitive POMs the resolver visits - are propagated as the value for matching resolved coordinates, so the downstream `Download` step can validate them. | `requires.properties`, `versions.properties`                                                                                          | `requires.properties` (transitively resolved, per-prefix `Resolver`)             |
| `Download`                 | Reads `requires.properties` and downloads each coordinate's artifact into `dependencies/`, validating against the recorded checksum where present (mismatch fails the build) and reusing a previous run's file when valid. Coordinates with empty values are downloaded without integrity validation. Writing to `dependencies/` (instead of `artifacts/`) keeps the module's own produced binary separated from its downloaded deps. | `requires.properties`                                                                                                                 | `dependencies/<prefix>-<coordinate>.jar`, plus an empty `requires.properties`    |
| `Translate`                | Rewrites the keys of `requires.properties` (and `versions.properties` when present, with the same translator) through user-supplied per-prefix translator functions (e.g. Java module name → Maven coordinate).                                                  | `requires.properties`, `versions.properties`                                                                                          | `requires.properties`, `versions.properties` (keys remapped per-prefix)                                 |
| `Versions`                 | Walks each predecessor's `classes/`, hard-links every non-`module-info.class` file under `context.next()/classes/`, and rewrites every `module-info.class` so each `requires <X>` directive gets a `compiledVersion` set from the matching entry in the resolved `requires.properties` (module-style `<prefix>/<name>/<version>` coordinates). Uses the JDK's `java.lang.classfile` API; module flags (`OPEN`), the module's own version, `exports`, `opens`, `uses` and `provides` round-trip unchanged. | `classes/`, `requires.properties`                                                                                                     | `classes/` (non-`module-info` hard-linked, `module-info.class` rewritten in-place) |
| `Group`                    | Reads each predecessor's `identity.properties` and `requires.properties`; for each identified group, writes a `groups/<name>.properties` listing the other groups whose coordinates it depends on. | `identity.properties`, `requires.properties`                                                                                          | `groups/<encoded-name>.properties`                                               |
| `Assign`                   | Fills the empty values of `identity.properties` with paths to the jars in the predecessors' `artifacts/`, finalising the coordinate → file mapping.                                           | `identity.properties`, `artifacts/`                                                                                                   | `identity.properties` (empty values filled with artifact paths)                  |
| `Inventory`                | Builds a per-module **launchable and stageable summary**: scans each predecessor for `module.properties` (`path`, `main`, `module`, `test`), `metadata.properties` (`version`), any `artifacts/` subdir (each file goes into `prefix.artifacts`), any `sources/` subdir (each file into `prefix.sources`), any `documentation/` subdir (each file into `prefix.documentation`), any `dependencies/` subdir (each file appended to the runtime classpath), and a top-level `pom.xml` (the generated POM when the layout emits one). Emits one `inventory.properties` with a single-segment prefix (`module` for the root, `module-<path>` otherwise); all path values are self-anchored to the inventory file's folder. See the row in the conventions table above for the recognised keys. | `module.properties`, `metadata.properties`, `artifacts/`, `sources/`, `documentation/`, `dependencies/`, `pom.xml`                                                                              | `inventory.properties`                                                            |
| `DownloadModuleUris`       | Fetches the configured remote URL lists and concatenates them into a single `uris.properties`. The default registry is [sormuras/modules](https://github.com/sormuras/modules), a community-maintained map of Java module names to Maven Central jar URLs; its refresh is manual, so a brand-new upstream version may not appear in the registry until the next refresh is published. | none (fetches the configured URLs)                                                                                                    | `uris.properties`                                                                |
| `MultiProjectDependencies` | Merges per-project `requires.properties` (and looks up sibling-project paths in their `identity.properties`) into one unified `requires.properties`. Sibling-built coordinates are written with empty values (no integrity validation; trust is implicit within the same build); externally-pinned coordinates pass through with their declared checksums intact. | per-predecessor `identity.properties` or `requires.properties`, partitioned by predicate                                              | unified `requires.properties`                                                    |
| `Pom`                      | Emits a Maven `pom.xml`, taking the project's own coordinate from the empty entry in `identity.properties` and its dependencies from `requires.properties` entries that share the same prefix. | `identity.properties` (self coordinate = empty value), `requires.properties`                                                          | `pom.xml`                                                                       |
| `MavenRepositoryStaging`   | Per-module inventory walker that stages the contents of every `inventory.properties` it sees into a Maven-repository tree under `context.next()`. For each main module it parses `prefix.pom` for `groupId`/`artifactId`/`version`, validates that `prefix.artifacts` lists exactly one `.jar` and `prefix.sources`/`prefix.documentation` each list at most one `.jar`, then hardlinks the binary plus the (optional) sources/documentation jars plus the pom as `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>.<ext>`. For each test module (`prefix.test=<main-artifactId>`) it routes the jars onto the named main's coordinate with a `-tests` classifier; the test module's POM is parsed for additional dependencies and merged into the staged main POM with `<scope>test</scope>`. Refuses duplicate main artifactIds and multiple test modules pointing at the same main. | every `inventory.properties` reachable through the predecessors                                                                       | `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].{jar,pom}` |
| `ModularStaging`           | Per-module inventory walker that stages Java-module-named artifacts. For each inventory it reads `prefix.module` (the Java module system module name) and optional `prefix.version`, validates that `prefix.artifacts` lists exactly one `.jar` and `prefix.sources`/`prefix.documentation` each list at most one, then hardlinks them under `<moduleName>/[<version>/]<moduleName>{,-sources,-javadoc}.jar`. Test modules (`prefix.test` set) are skipped by default and emitted under their own Java module system name when `includeTests` is enabled. | every `inventory.properties` reachable through the predecessors                                                                       | `<moduleName>/[<version>/]<moduleName>{,-sources,-javadoc}.jar`                  |
| `MavenRepositoryExport`    | Publishes a staged Maven-repository tree to an external target path (default `~/.m2/repository`, overridable via the `MAVEN_REPOSITORY_LOCAL` environment variable). Always re-runs (`shouldRun = true`) since the destination is outside the executor's control. Walks each predecessor for `.pom` files, copies every sibling in the version directory into the matching target path with `REPLACE_EXISTING`, then writes the `mvn install`-equivalent metadata: a `maven-metadata-local.xml` per artifact (`<release>` set to the highest non-SNAPSHOT version by Maven semantics, `<versions>` sorted ascending, `<lastUpdated>` timestamp), an `_remote.repositories` marker per version directory, and a `modelVersion="1.1.0"` `maven-metadata-local.xml` inside each `-SNAPSHOT` version directory listing per-extension/classifier `<snapshotVersions>`. | a staged Maven-repository tree (typically `MavenRepositoryStaging`'s output)                                                          | files copied under the configured target path; nothing is written under `context.next()` |
| `PinPom`                   | Reads each predecessor's `versions.properties` and `requires.properties`, filters entries by a configured prefix (typically `maven`), and rewrites the configured `pom.xml` source file(s) so that the `<dependencyManagement>` block lists every entry as a `<dependency>` (with `<type>`/`<classifier>` when present) plus a `<!--Checksum/<algorithm>/<hex>-->` comment when the value carries one. Replaces the existing block in place if present, inserts one before `<dependencies>` (or before `</project>`) if absent. Also strips any `<!--Checksum/...-->` comments from direct `<dependency>` entries outside `<dependencyManagement>`, since the rewritten BOM is the single source of truth for those checksums. Accepts either a single `Path` or a `List<Path>` of pom.xml files to update. Always re-runs (`shouldRun = true`) and writes back to the source file outside `context.next()`. | `versions.properties` and/or `requires.properties` (resolved with-version coords) from each predecessor                              | none under `context.next()`; mutates the configured `pom.xml` file(s)            |
| `PinModuleInfo`            | Reads each predecessor's `versions.properties` and `requires.properties`, filters entries by a configured prefix (typically `module`), and rewrites the configured `module-info.java` source file(s) so that the preceding Javadoc block contains `@jenesis.pin <module> <version>[ <algorithm>/<hex>]` tags for every entry. Replaces the existing `@jenesis.pin` lines in place if present (preserving other block tags like `@jenesis.release`, `@jenesis.test`); inserts a fresh Javadoc block above the module declaration if none exists. Accepts either a single `Path` or a `List<Path>` of module-info.java files. Always re-runs (`shouldRun = true`) and writes back to the source file outside `context.next()`. | `versions.properties` and/or `requires.properties` (resolved with-version coords) from each predecessor                              | none under `context.next()`; mutates the configured `module-info.java` file(s)   |

`ProcessBuildStep` and `Java` are abstract bases (used by `Javac`, `Jar`, `Javadoc`, and the inner `executed`
step that `TestModule` registers); `Java.of(...)` gives an ad-hoc command runner. `DependencyTransformingBuildStep`
is the shared base for `Resolve`, `Download`, and `Translate`; they all parse `requires.properties` into
`(prefix, coordinate)` groups, transform them, and write `requires.properties` back.

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

`MavenRepositoryExport` is the one step that intentionally breaks two of the conventions that every other step
holds to. Its job is to publish a build's staged outputs outside the `target/` tree (typically into the user's
local Maven repository), so it cannot honour the "immutable, content-hashed output folder" invariant that drives
incremental builds:

- **Writes outside `context.next()`.** The destination is supplied as a `Path` to the constructor and lives
  wherever the user wants it - `~/.m2/repository` by default, otherwise a network share, an existing distribution
  layout, or any other target. Files are copied (not hard-linked, since the target may be on a different
  filesystem) and `REPLACE_EXISTING` always overwrites whatever is at the destination. `context.next()` itself is
  left empty.
- **Always re-runs.** `shouldRun(...)` returns `true`, so even if all inputs are unchanged the export is performed
  again. The reason is that the destination is outside the executor's control - anything could have edited or
  removed those files between builds - so the only safe assumption is that the export needs redoing every time.
  The step's serialized form is still hashed (config-aware cache invalidation still applies), but consistent
  predecessor checksums just shorten the diff the step sees, not whether it runs.

`MavenRepositoryExport` consumes a tree already shaped by `MavenRepositoryStaging`. It walks each predecessor for
every `.pom` file, takes the file's parent directory as the version directory of one artifact, and copies every
sibling there (`<artifactId>-<version>[-<classifier>].{jar,pom}`) into the matching target path. After copying,
the same step writes the `mvn install`-equivalent metadata next to each artifact: a `maven-metadata-local.xml`
per artifact (`<release>` set to the highest non-SNAPSHOT version by Maven semantics, `<versions>` sorted
ascending, `<lastUpdated>` timestamp), an `_remote.repositories` marker per version directory, and a
`modelVersion="1.1.0"` `maven-metadata-local.xml` inside each `-SNAPSHOT` version directory listing per-extension
/classifier `<snapshotVersions>`. Unhandled today: checksum sidecars (`.sha1`/`.md5`), GPG signatures, and
`<parent>` version inheritance - the `Pom` step always emits an explicit `<version>`, so the last is fine in
practice for artifacts produced by this build.

Build executor modules
----------------------

The modules listed here are pre-implemented for convenience; the build tool itself does not depend on any of them, and a build is free to ignore them and supply its own `BuildExecutorModule` implementations.

In every diagram below, blue rounded nodes are inputs (folders or files), yellow rectangles are steps, and
purple rectangles are nested sub-modules.

### `JavaModule`

Used for compiling and packaging a single Java module from its sources and its resolved dependencies. Between
compilation and packaging it runs a `Versions` step that consults the compile-scope `requires.properties` and
rewrites every `module-info.class` to embed the resolved versions on each `requires` directive, so the produced
jar carries the same versions that were used to assemble its module path. The record's single `process` flag
chooses between the in-process tool APIs (`Javac.tool()` / `Jar.tool(...)`) and out-of-process invocations
(`Javac.process()` / `Jar.process(...)`); the latter is what `JavaMultiProjectAssembler` selects when
`-Djenesis.java.process=true` is set so the build can run under a stricter sandbox. Running the compiled tests
is not part of `JavaModule` itself - that is wired separately by `JavaMultiProjectAssembler` as a sibling
`TestModule` when the project enables tests and the module is flagged as a test variant (see *`TestModule`*
below).

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  src(["sources/"]):::input
  arts(["dependency artifacts/"]):::input
  req(["dependency requires.properties"]):::input
  classes["compiled<br/>(Javac)"]:::step
  versions["classes<br/>(Versions)"]:::step
  artifacts["artifacts<br/>(Jar, Sort.CLASSES)"]:::step
  src --> classes
  arts --> classes
  classes --> versions
  req --> versions
  versions --> artifacts
  arts --> artifacts
```

### `TestModule`

A `BuildExecutorModule` that runs a configured `TestEngine` (e.g. JUnit 5) against the compiled tests of its
predecessors. Construction requires `repositories` and `resolvers` maps; the runner is fetched on the side via
an inlined `Resolve` → `Download` pipeline, so the user never has to declare it as a compile-time `requires`
of their test module. Empty maps are valid when the runner is already present on the inherited class- or
module-path - the `Requires` step then writes an empty `requires.properties` and nothing is fetched:

- `resolved` (`TestModule.Requires`) writes the runner's coordinate to `requires.properties`, picking the
  first entry in `TestEngine.coordinates()` whose `<prefix>` is served by one of the configured resolvers -
  `JUnit5` ships both `module/org.junit.platform.console` and
  `maven/org.junit.platform/junit-platform-console/<version>`, so the same engine works across `Modular`,
  `ModularToMaven`, and `Manual`-style builds. Versions baked into the coordinate act as defaults; upstream
  `versions.properties` (project `dependencyManagement` / `module-info` pins) wins for any matching managed
  key during the downstream resolve. If the runner is already visible on an input folder
  (`TestEngine.hasRunner(...)`), nothing is written.
- `required` (`Resolve`) takes the runner coordinate *together* with every upstream
  `requires.properties` (the project's already-transitively-resolved compile/runtime deps) and runs the
  resolve a second time across the combined set. The resolver dedups by coordinate key and negotiates a
  single version per key, so a transitive dependency the runner pulls in that the project already resolved
  collapses to one entry rather than producing two clashing module-path entries downstream.
- `artifacts` (`Download`) fetches the unified resolved set into `artifacts/`, validating checksums when
  present and hard-linking from the local cache when available so the second resolve doesn't re-fetch jars
  the project's own resolve already brought down.
- `executed` (`TestModule.Run` extends `Java`) accepts a `filter` argument: a comma-separated list of Java
  regex entries, each `<classRegex>` or `<classRegex>#<methodName>`. When wired by
  `JavaMultiProjectAssembler`, the filter is sourced from the `-Djenesis.java.test=<patterns>` system property;
  callers that construct `TestModule` directly pass the filter explicitly. Class entries are emitted via the
  engine's `prefix()` (e.g. JUnit 5's `-select-class=`); method entries via `methodPrefix()` (e.g.
  `-select-method=`). The filter is part of the step's serialized state (so changing it invalidates the cache);
  when set, the step is also forced to re-run regardless of cache consistency. `Java` scans each argument's
  `artifacts/` for jars and dispatches them to `--module-path` or `--class-path` based on its own `modular`
  flag.

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  arts(["inherited classes/<br/>+ artifacts/<br/>+ requires.properties"]):::input
  resolved["resolved<br/>(Requires)"]:::step
  required["required<br/>(Resolve)"]:::step
  artifacts["artifacts<br/>(Download)"]:::step
  executed["executed<br/>(Java/Run)"]:::step
  arts --> resolved
  resolved --> required
  arts --> required
  required --> artifacts
  artifacts --> executed
  arts --> executed
```

### `DependenciesModule`

Used for resolving and downloading external dependencies declared in `requires.properties`. The pipeline is
`Resolve` (transitive closure) → `Download` (jars). `Download` validates each downloaded jar against the
checksum recorded in `requires.properties` when one is present (sourced from POM/`module-info` pins, see
the [`requires.properties`](#conventional-folders-and-files) row); coordinates without a pinned checksum
are downloaded without integrity validation.

```mermaid
flowchart LR
  classDef input fill:#dbeafe,stroke:#1e40af,color:#1e3a8a;
  classDef step fill:#fef3c7,stroke:#92400e,color:#78350f;
  deps(["requires.properties"]):::input
  resolved["resolved<br/>(Resolve)"]:::step
  artifacts["artifacts<br/>(Download)"]:::step
  deps --> resolved
  resolved --> artifacts
```

### `MultiProjectModule`

Used as the generic shape behind multi-project layouts. An *identifier* sub-module discovers the projects in a
source tree and writes their coordinates and dependencies; a `Group` step partitions the cross-project
dependency graph; a *factory* then assembles one sub-module per discovered project, wiring cross-project edges
between them. Each per-project closure receives a `ModuleDescriptor` exposing `name()` and `dependencies()`; the
concrete subtype (`ModularModuleDescriptor` or `MavenModuleDescriptor`) also exposes the standardised inherited
keys as helpers (`sources()`, `manifests()`, `coordinates()`, `artifacts(DependencyScope)`, `resolved(DependencyScope)`), so a closure doesn't need to know how
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
which `ProcessBuildStep` forwards to `javac`. A `<!--Checksum/<algorithm>/<hex>-->` comment placed inside any
`<dependency>` element is parsed as an optional pre-pinned content checksum for that artifact and lands in
`requires.properties` as the dependency's value (instead of empty); the same comment placed inside a
`<dependencyManagement>` `<dependency>` lands as the optional `version checksum` suffix in
`versions.properties` and is propagated by the resolver to whichever transitive resolves to that coordinate.
`Download` validates non-empty `requires.properties` values against the downloaded bytes and fails the build on
mismatch; coordinates without a pinned checksum are downloaded without integrity validation. There is no
on-the-fly hash computation in the build - validation is opt-in by declaring hashes in source. The same
comment may also be placed inside a `<parent>` element or a `<dependencyManagement>` `<dependency>` with
`<scope>import</scope>` (a BOM import); when the resolver downloads that referenced POM during resolution,
it streams the bytes through a digest and fails the build if they do not match the pinned hash, so the
integrity story extends to POMs the build pulls in for reference, not just to artifact jars.
`MavenProject.make(...)` returns the full wrapped `MultiProjectModule` whose factory runs
`prepare` (`MultiProjectDependencies`), `dependencies` (`DependenciesModule`: `Resolve` then `Download`),
`build` (caller-supplied, typically `JavaModule`), `assign` (`Assign`), and `inventory` (`Inventory`,
producing the per-module `inventory.properties` consumed by `Execute`) for each project.

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.11.3</version>
    <scope>test</scope>
    <!--Checksum/SHA256/abcdef0123...-->
</dependency>
```

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
    pBinv["inventory<br/>(Inventory)"]:::step
    pBprep --> pBdeps --> pBbuild --> pBassn --> pBinv
  end
  subgraph "A (per project, requires B)"
    direction LR
    pAprep["prepare<br/>(MultiProjectDependencies)"]:::step
    pAdeps["dependencies<br/>(DependenciesModule)"]:::module
    pAbuild["build<br/>(caller-supplied)"]:::module
    pAassn["assign<br/>(Assign)"]:::step
    pAinv["inventory<br/>(Inventory)"]:::step
    pAprep --> pAdeps --> pAbuild --> pAassn --> pAinv
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
`requires.properties` from the Java `requires` directives. Javadoc tags of the form `@jenesis.pin <module> <version>`
on the module declaration are captured into the same manifests step's `versions.properties` as a BOM-style pin
map - the tag does not have to name a directly-required module, so a transitive can be pinned the same way:

```java
/**
 * @jenesis.release 25
 * @jenesis.pin org.junit.jupiter 5.11.3
 * @jenesis.pin org.junit.platform.commons 1.11.4
 */
open module build.jenesis.test {
    requires org.junit.jupiter;
}
```

An `@jenesis.release <V>` tag on the module declaration (independent of the BOM pins above) is captured by the
manifests step into a `process/javac.properties` sidecar containing `--release=<V>`, which `ProcessBuildStep`
forwards to `javac` when compiling the module.

An optional space-separated `<algorithm>/<hex>` after the version on a `@jenesis.pin` Javadoc tag
(e.g. `@jenesis.pin org.junit.jupiter 5.11.3 SHA256/abcdef0123...`) is captured into the same
`versions.properties` value (as `version checksum`); the resolver propagates it to whichever transitive
resolves to that bare module name, so `Download` validates the bytes against it. There is no on-the-fly
hash computation in the build - validation is opt-in by declaring hashes in source.

`ModularProject.make(...)` returns the full wrapped `MultiProjectModule` whose factory runs `prepare`
(`MultiProjectDependencies`), `dependencies` (`DependenciesModule`: `Resolve` then `Download`),
`build` (caller-supplied, typically `JavaModule`), `assign` (`Assign`), and `inventory` (`Inventory`,
producing the per-module `inventory.properties` consumed by `Execute`) for each project.

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
    pBinv["inventory<br/>(Inventory)"]:::step
    pBprep --> pBdeps --> pBbuild --> pBassn --> pBinv
  end
  subgraph "A (per project, requires B)"
    direction LR
    pAprep["prepare<br/>(MultiProjectDependencies)"]:::step
    pAdeps["dependencies<br/>(DependenciesModule)"]:::module
    pAbuild["build<br/>(caller-supplied)"]:::module
    pAassn["assign<br/>(Assign)"]:::step
    pAinv["inventory<br/>(Inventory)"]:::step
    pAprep --> pAdeps --> pAbuild --> pAassn --> pAinv
  end
  tree --> idA
  tree --> idB
  idA --> pAprep
  idB --> pBprep
  pBassn --> pAprep
```

### `ExternalModule`

Used for loading a `BuildExecutorModule` from a published modular jar at build time, so a build can pull in
plug-in modules from a repository instead of vendoring them as source. The plug-in must ship as a Java
module that declares `provides build.jenesis.BuildExecutorModule with ...;` in its `module-info.java`.

Given a `<prefix>/<coordinate>` string, a map of `Repository` instances, and a map of `Resolver` instances,
`ExternalModule` registers three internal nodes:

- `coordinate` writes the requested coordinate (plus any added via `withDependencies(...)`) into a fresh
  `requires.properties`.
- `dependencies` is an embedded `DependenciesModule` that resolves + downloads the coordinate's transitive
  closure using the supplied repositories and resolvers. The plug-in's own `requires build.jenesis;` is
  followed by the resolver, so its copy of Jenesis is fetched alongside its other dependencies.
- `delegate` builds a fresh `ModuleLayer` over the downloaded jars and runs the plug-in's
  `BuildExecutorModule.accept(...)` against the same inherited folders `ExternalModule` itself received
  (see *Plug-in isolation* below).

`ExternalModule.resolve(...)` hides the three internal nodes from the published output map and strips the
`delegate/` prefix from the delegated module's outputs, so the plug-in's nodes surface under
`ExternalModule`'s registered name. The hidden steps still execute and participate in the cache; only
their published names disappear.

```java
new ExternalModule("module/com.example.plugin", repositories, resolvers)
        .withDependencies("module/com.example.extra");
```

### `InternalModule`

Used for loading a `BuildExecutorModule` from a local source folder, so a plug-in can be developed
alongside the project that consumes it without first publishing it to a repository. The source folder
must contain a `module-info.java` that `provides build.jenesis.BuildExecutorModule with ...;` - the
plug-in still has to ship as a Java module, just one built from source instead of pulled from a
repository.

`InternalModule` takes a coordinate `prefix`, a `Path` to the source folder, a `Repository` map, and a
`Resolver` map, and registers:

- `source` binds the source folder as Java sources via `Bind.asSources()`.
- `compile-requires` and `runtime-requires` each parse the source's `module-info.java` and write a
  `requires.properties` for the corresponding scope (`requires` for compile, `requires` minus `static` for
  runtime). Each entry is keyed `<prefix>/<module-name>`, so the resolver under `prefix` can look it up.
  Both steps re-run only when `sources/module-info.java` actually changes.
- `compile` and `runtime` are scope-specific `DependenciesModule` instances that download the two
  classpaths separately.
- `java` is a `JavaModule` that compiles the source against the compile classpath.
- `delegate` builds a `ModuleLayer` over the compiled jar plus the runtime classpath and runs the
  plug-in.

`withDependencies(...)` adds extra coordinates (written verbatim, no prefix added) to both
`requires.properties` files, for plug-ins that need modules not declared in their own `module-info.java`.
The source must declare `requires build.jenesis;` (plus whatever else it uses from Jenesis); the resolver
fetches Jenesis like any other module dependency. `InternalModule` errors at build time if the source
lacks `module-info.java`.

### Plug-in isolation

Both modules load the plug-in into a fresh `ModuleLayer` whose ClassLoader parent is the platform loader,
not the host's application loader. As a result:

- The host's `build.jenesis` classes are invisible to the plug-in. The plug-in pulls in its own copy of
  Jenesis via its `requires build.jenesis;` declaration; the resolver downloads it like any other
  module. The two copies are different `Class` instances in different loaders.
- Two plug-ins with conflicting transitive dependencies do not clash, because they each get their own
  layer with their own copy of every non-platform module.

Because the host can't simply call methods on the loaded plug-in (the types live in a different loader),
`JenesisClassLoaderBridge` mediates: it creates a `java.lang.reflect.Proxy` implementing the *foreign*
`BuildExecutor` and hands that proxy to the plug-in's `accept(...)`. As the plug-in calls `addStep`,
`addModule`, etc. on the proxy, the bridge:

- Forwards default-method calls through `InvocationHandler.invokeDefault`, so the abstract overloads are
  all the bridge has to special-case.
- Wraps each `BuildStep` argument as a host `BuildStep` that, on `apply(...)`, translates the host's
  `BuildStepContext` / `BuildStepArgument` into foreign records (via `MethodHandle`s bound to the foreign
  record constructors), invokes the foreign step's `apply` via `MethodHandle`, and translates the foreign
  `BuildStepResult` back. `ChecksumStatus` enum values are mapped across loaders by name.
- Wraps each `BuildExecutorModule` argument as a host `BuildExecutorModule` that recursively re-enters
  the same bridge when invoked, so plug-ins can register nested modules.
- Passes everything else (Strings, `Path`s, `SequencedMap<String, String>`,
  `Function<String, Optional<String>>`) through unchanged - those types live in `java.base` and are
  shared across loaders.

The reflective calls use `MethodHandles`, and the bridge uses `ModuleLayer.Controller.addOpens` plus
`MethodHandles.privateLookupIn` to obtain a lookup whose accessing class is in the foreign loader. That
lookup is required so the JVM's loader-constraint check (which would otherwise complain that the host and
foreign loaders define different `BuildExecutor` `Class` objects for the same name) does not trip.

### Picking a specific plug-in: `@BuildModuleName` and `withBuildModuleName(...)`

The plug-in's layer must resolve to **exactly one** `BuildExecutorModule` service provider. By default
(no `withBuildModuleName(...)` call), only providers without a `@build.jenesis.BuildModuleName` annotation
qualify. Annotated providers are selected explicitly:

```java
package com.example;

@BuildModuleName("publish")
public class Publish implements BuildExecutorModule { ... }
```

```java
new ExternalModule("module/com.example.plugin", repositories, resolvers)
        .withBuildModuleName("publish");
```

`InternalModule` exposes the same `withBuildModuleName(String)` factory. If zero or more than one provider
match the (possibly null) requested name, the build fails at `delegate` time with a descriptive error.
This makes it possible to ship multiple entry points in a single plug-in jar and let the consumer pick
which one to run per `ExternalModule` / `InternalModule` instance.

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
  paths).

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
  (`-Djenesis.executor.rebuild=true` achieves the same thing globally, but discards every other step's cache too.)

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
| `jenesis.executor.digest`  | system property | Algorithm passed to `HashDigestFunction` and `BuildStepHashFunction.ofSerializationDigest(...)` by `BuildExecutor.of(Path)` for both the per-file content checksums (used to compute step input/output diffs) and the per-step serialization hash (used to detect config changes). Any `MessageDigest` algorithm name is accepted. Default `MD5`. |
| `jenesis.verbose`          | system property | When `true`, the default `BuildExecutorCallback` prints per-step verbose output (input/output checksum diffs, decisions to skip or re-run) instead of just the high-level status lines. The same flag also enables verbose logging in `Repository`, `MavenDefaultRepository`, `Project`, and `Execute`.                                              |
| `jenesis.executor.timeout` | system property | ISO-8601 duration (e.g. `PT5M`, `PT30S`) applied to every `BuildStep` by `BuildExecutor.of(Path)`. Each step's returned `CompletionStage` is wrapped with `orTimeout`, so the build fails fast with a `TimeoutException` (surfaced as a `BuildExecutorException`) when a step exceeds the limit. Note that the future is only completed exceptionally; the underlying virtual thread is not interrupted and only winds down when the surrounding `ExecutorService` closes at the end of the build. Default `PT0S` disables the timeout. |
| `jenesis.executor.rebuild` | system property | When `true`, `BuildExecutor.of(Path)` recursively deletes the target folder before constructing the executor, forcing a full rebuild from a clean tree. Equivalent to `rm -rf target/` ahead of the build. The explicit 6-arg `of(target, timeout, hash, stepHash, callback, rebuild)` overload accepts the flag directly; the 5-arg overload defaults it to `false`. Default `false`. |
| `jenesis.java.test`     | system property     | Read by `JavaMultiProjectAssembler` and passed to its `TestModule` as the test filter: a comma-separated list of `<classRegex>[#<method>]` entries. When set, `TestModule.executed` only emits selectors for classes (and optionally methods) matching those patterns. The value becomes part of the step's serialized state, and the step is forced to re-run regardless of cache consistency. Callers that construct `TestModule` directly pass the filter explicitly through the `String filter` constructor; the module itself no longer reads any system property. |
| `jenesis.project.strictPinning` | system property | When `true`, every `Download` step the project wires (through the layouts' `MavenProject.make`/`ModularProject.make`, and through `JavaMultiProjectAssembler`'s `TestModule`) fails the build with an `IllegalStateException` for any resolved coordinate that has no checksum pinned in `requires.properties`. Use this to lock the build down so every artifact has to come with a SHA pin from a `pom.xml` `<!--Checksum/...-->` comment or a `@jenesis.pin <module> <version> <algorithm>/<hex>` Javadoc tag. The flag is also exposed programmatically via `Project.strictPinning(true)`. `Download` itself no longer reads any system property; callers that construct `Download` directly pass the flag explicitly through `new Download(repositories, true)`. |
| `jenesis.project.pinAlgorithm` | system property | Algorithm used by `PinPom` / `PinModuleInfo` to recompute checksums over the resolved jar artifacts during the `pin` step (default `SHA-256`). Pin always rehashes whatever is sitting in the upstream `artifacts/` folders, so the pinned `<!--Checksum/...-->` / `@jenesis.pin` lines always reflect the bytes the build actually used. Any `MessageDigest` algorithm name is accepted (`SHA-512`, `SHA-1`, etc.). |
| `jenesis.project.version` | system property   | When set, stamps the version onto every artifact this build produces. It is appended last into every per-module `metadata.properties` (after the framework defaults and the project-root override file), so it overrides the `version` from either layer. `Javac` passes `--module-version <V>` when compiling a `module-info.java`, so the produced `module-info.class` carries it as `Module.version` (and downstream consumers automatically pick it up as `compiledVersion` on their `requires` directives). `Pom` writes it into `<version>`; dependency versions are unaffected. `MavenRepositoryStaging` reads coordinates from the produced `pom.xml`, so the staged folder path, artifact filenames and `MavenRepositoryExport`'s `maven-metadata-local.xml` follow along. |
| `jenesis.project.layout`        | system property | Read by `Project.Builder` (the canonical entry point) to force a `Layout` regardless of auto-detection or any in-code `.layout(...)`. Accepts `auto`, `maven`, `modular`, `modular_to_maven` (case-insensitive). Unknown values throw on `resolveProperties()`. |
| `jenesis.project.skipTests`     | system property | When set (any value, including the empty string from a bare `-Djenesis.project.skipTests`), `Project.Builder` resolves the project-level `tests` flag to `false`. The flag is carried on `ProjectModuleDescriptor.test()`, and `JavaMultiProjectAssembler` skips wiring the sibling `TestModule` sub-module when it is `false`, so test sources and test dependencies are not wired into the graph. |
| `jenesis.project.stageTests`    | system property | When set to `true`, the `STAGE` step includes test-variant artifacts. For `MAVEN` and `MODULAR_TO_MAVEN` that means the `-tests.jar` (plus `-tests-sources.jar` / `-tests-javadoc.jar` when those flags are on) and the test module's dependencies merged into the main `pom.xml` with `<scope>test</scope>`. For `MODULAR` it means the test module is staged as its own `<module>/<module>.jar` directory. Default `false`: tests still run during the build but their artifacts are not placed into the staging tree. |
| `jenesis.project.root`          | system property | Overrides the project root that `Project.Builder` scans for `module-info.java` / `pom.xml` (default `.`). |
| `jenesis.project.target`        | system property | Overrides the per-build output folder passed to `BuildExecutor.of(...)` (default `target`). Safe to delete to force a clean build. |
| `jenesis.project.cache`         | system property | Overrides the cross-build cache folder (default `.jenesis/cache`) under which the `MODULAR` layout stores `<encoded-coordinate>.jar` for each downloaded module jar (see *The `.jenesis/cache/` folder*). Effectively ignored by `MAVEN` and `MODULAR_TO_MAVEN` since they cache through `~/.m2/repository` instead. |
| `MAVEN_REPOSITORY_URI`  | environment variable| Overrides the default `MavenDefaultRepository` upstream URL (`https://repo1.maven.org/maven2/`). Useful for pointing at an internal mirror; a trailing slash is added automatically if missing.                                       |
| `MAVEN_REPOSITORY_LOCAL`| environment variable| Overrides the local Maven repository directory (default `~/.m2/repository`) for both reads and writes: `MavenDefaultRepository` reads from and hardlinks downloaded jars into it; `MavenRepositoryExport` publishes the staged tree into it. On the read side, an explicit override must point at an existing directory or `MavenDefaultRepository` throws on construction (unset is permissive: a missing default silently disables the local cache and every fetch streams from upstream). On the write side, the directory is created on demand. |
| `MAVEN_REPOSITORY_TOKEN`| environment variable| When set, `MavenDefaultRepository` sends the value verbatim as an `Authorization` header on every HTTP fetch (artifact bytes and `.sha1` sidecars). Useful for upstreams that require token-based auth: set the full header value, e.g. `Bearer <token>` for OAuth-style endpoints or `Basic <base64(user:pass)>` for HTTP Basic. Ignored for `file://` URIs and any non-HTTP scheme. |
| `JENESIS_REPOSITORY_URI` | environment variable| Overrides the upstream URL that `JenesisModuleRepository`'s no-arg constructor points at (default `https://repo.jenesis.build/modules/`, the public overlay; see *Jenesis Repository layout for Java modules*). A trailing slash is added automatically if missing once the URI becomes a repository root. Useful for pointing at a mirror or a privately hosted publication of the same on-disk shape. The explicit `URI`-arg constructors bypass this variable. |
| `JENESIS_REPOSITORY_LOCAL` | environment variable| Overrides the local Jenesis module repository directory (default `~/.jenesis`) used by `JenesisModuleRepositoryExport` (writes the staged module tree into it; directory created on demand) and by the static factory `JenesisModuleRepository.ofLocal()` (reads module jars back from it). `JenesisModuleRepository`'s no-arg constructor does **not** consult this variable; the remote overlay is governed by `JENESIS_REPOSITORY_URI` / `JENESIS_REPOSITORY_TOKEN` instead. |
| `JENESIS_REPOSITORY_TOKEN` | environment variable| When set, `JenesisModuleRepository`'s no-arg constructor sends the value verbatim as an `Authorization` header on every HTTP fetch (e.g. `Bearer <token>` or `Basic <base64(user:pass)>`). Ignored for `file://` roots and any non-HTTP scheme. The `(URI, String token)` constructor takes an explicit token instead, bypassing this variable. The set of three `JENESIS_REPOSITORY_*` variables mirrors the `MAVEN_REPOSITORY_*` set above. |
| `JAVA_HOME`             | environment variable| Consulted by `ProcessBuildStep`/`ProcessHandler` to locate the `java`/`javac`/`javadoc` binaries when the `java.home` system property is not set (typical when launching from a non-JDK runtime).                                     |

Properties are passed on the JVM command line, e.g.

    java -Djenesis.executor.rebuild=true build/Modules.java

### Selectors on the command line

The build script forwards its `String[] args` to `BuildExecutor.execute(...)` (e.g. `root.execute(args)` in
`build/Modular.java`), so any positional arguments after the build file are interpreted as selectors. With no
arguments, the full graph runs.

| Invocation                                | What runs                                                                                                                                                       |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `java build/Modular.java`                 | Whole graph. On a warm cache, every step is `[SKIPPED]`.                                                                                                        |
| `java build/Modular.java ::/test`         | Every `test` sub-module at any depth, plus its transitive preliminary closure. Modules along the path have their step preliminaries cache-checked; sibling sub-graphs that happen to be scheduled by `::` lenient-skip. |
| `java build/Modular.java build/::/test`   | Same, but anchored under the top-level `build` module. Top-level entries that aren't on the path to `build` (e.g. the `stage` step that depends on `build`) are not scheduled at all.                  |
| `java -Djenesis.java.test='.*FooTest' build/Modular.java ::/test` | Same selector, but `TestModule.executed` re-runs unconditionally and only selects classes matching the regex; upstream `classes`/`artifacts` etc. stay cached. |

Literal selectors that don't resolve throw `Unknown selector: …`. Wildcards (`:` for one segment, `::` for any
depth) silently skip non-matching branches, but over-schedule sibling subtrees: each such module's `accept(...)`
runs and its declared step preliminaries are pinned, guaranteeing the predecessor folders exist wherever the
wildcard lands. Prefer literal paths when you know them.

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

### Repositories and resolvers

`Repository` and `Resolver` are the two pluggable surfaces for dependency lookup: a `Repository` goes from a
coordinate to bytes, a `Resolver` goes from a root set of coordinates to a transitive closure. Layouts wire
defaults; user code overrides them per prefix.

`Repository` is a `@FunctionalInterface` with a single method `fetch(Executor, String coordinate) -> Optional<RepositoryItem>`,
where `RepositoryItem` is a thin wrapper exposing an `InputStream` and optionally a `Path` (so consumers can
hardlink instead of copy). Two default methods compose: `repo.prepend(other)` tries `other` first and falls
back to `this`; `repo.cached(folder)` wraps `this` with an on-disk hardlink cache that names each fetched
artifact `folder/<BuildExecutorModule.encode(coordinate)>.jar`.

Static factories on the interface itself:

- **`Repository.empty()`** - never resolves anything. Useful as the default for a prefix with no associated
  repository.
- **`Repository.ofUris(Map<String, URI>)`** and the overload **`Repository.ofUris(uris, versionResolver)`** -
  coordinate-to-URI lookup. The optional `versionResolver` (a `Serializable BiFunction<URI, String, Optional<URI>>`)
  rewrites the registered URL for a versioned coordinate; when omitted, the lookup is strict-literal.
  `MavenDefaultRepository.versionResolver()` is the canonical implementation: it parses the registered URL as
  a Maven-layout path and substitutes the requested version into both the directory segment and the filename,
  so a single-URL registry can satisfy arbitrary version pins. `file://` URIs are returned as
  `RepositoryItem.ofFile(...)` so the framework can hardlink the existing on-disk bytes instead of streaming.
- **`Repository.ofFiles(Map<String, Path>)`** and **`Repository.files()`** - coordinate-to-`Path` lookups (the
  second interprets the coordinate string itself as a filesystem path). Used when bytes are produced locally.
- **`Repository.ofProperties(suffix, folders, resolver, [versionResolver,] cache)`** - bulk-loads a
  `Map<String, Repository>` keyed by prefix from line-based `<prefix>/<key>=<location>` registries found in
  `folders`. Each prefix's URIs become `Repository.ofUris(..., versionResolver).cached(cache)`. The
  standalone example script `build/Modular.java` uses this to convert the `uris.properties` output of
  `DownloadModuleUris` into a per-prefix repository map with `.jenesis/cache/` as the cross-build hardlink
  cache. The shipped `Layout.MODULAR` instead points its `module` prefix at the public Jenesis overlay
  directly (see *Jenesis Repository layout for Java modules* and the `JenesisModuleRepository` paragraph
  above).
- **`Repository.prepend(left, right)`** - per-prefix `prepend` of two repository maps (the right map's entries
  are tried first, falling back to the left's for the same prefix).

`MavenRepository` extends `Repository` with a structured `fetch(executor, groupId, artifactId, version, type,
classifier, checksum)` and an optional `fetchMetadata(executor, groupId, artifactId, checksum)` returning the
artifact's `maven-metadata.xml`. `MavenRepository.of(Repository)` adapts any plain `Repository` by serialising
the parts into a `groupId/artifactId[/type[/classifier]]/version` coordinate string.

`MavenDefaultRepository` is the concrete implementation: it talks HTTP to the upstream Maven repository
(default `https://repo1.maven.org/maven2/`, overridable via the `MAVEN_REPOSITORY_URI` environment variable),
and hardlinks fetched bytes through the user's **local Maven repository**, defaulting to `~/.m2/repository`
and overridable via the `MAVEN_REPOSITORY_LOCAL` environment variable (this is **not** the project's
`.jenesis/cache/` folder). When `MAVEN_REPOSITORY_LOCAL` is set explicitly, the directory must exist or the
constructor throws; the default `~/.m2/repository` is permissive in the other direction, silently bypassing
the local cache layer when absent. For upstreams that require authentication, `MAVEN_REPOSITORY_TOKEN` is
sent verbatim as the `Authorization` header on every HTTP fetch (set the full value including the scheme,
e.g. `Bearer <token>` or `Basic <base64(user:pass)>`); the token is also threaded through to `.sha1` sidecar
fetches and ignored for `file://` URIs. Each download is validated against its `.sha1` sidecar; a mismatch
deletes the cached file and any cached digests so the next request re-downloads from upstream.

`JenesisModuleRepository` is the read-side counterpart to `JenesisModuleRepositoryExport`: it fetches module
jars from a tree shaped by the export step (`<root>/<module>[/<version>]/<module>.jar`). The no-arg
constructor defaults to the public overlay at `https://repo.jenesis.build/modules/` (see
*Jenesis Repository layout for Java modules* below for what that URL serves) and consults two environment
variables in line with the Maven-side trio: `JENESIS_REPOSITORY_URI` overrides the upstream root, and
`JENESIS_REPOSITORY_TOKEN` is sent verbatim as an `Authorization` header on every HTTP fetch (e.g. `Bearer
<token>` or `Basic <base64(user:pass)>`, ignored for `file://` and any non-HTTP scheme). The `URI`
constructor accepts any root explicitly, so a `file://`, `http://`, or `https://` URL pointing at a
publication of the same shape works the same way; the `(URI, String token)` overload additionally accepts
an explicit auth token, bypassing `JENESIS_REPOSITORY_TOKEN`. The static factory
`JenesisModuleRepository.ofLocal()` returns a repository rooted at `~/.jenesis` (overridable via
`JENESIS_REPOSITORY_LOCAL`, the same variable the export honours). `ofLocal()` does not consult the
`JENESIS_REPOSITORY_TOKEN` variable - tokens only apply to the remote overlay or to a custom
`JenesisModuleRepository` wired explicitly through `Project.repositories(...)`. A bare-name coordinate
(`<module>`) fetches the unversioned `<root>/<module>/<module>.jar`; a coordinate with a version
(`<module>/<version>`) fetches `<root>/<module>/<version>/<module>.jar`. For `file://` URIs the returned
`RepositoryItem` exposes the underlying `Path` so downstream caches can hardlink instead of copy; for HTTP
URIs the stream is opened eagerly and an HTTP 404 surfaces as `Optional.empty()` so resolvers can fall
back cleanly. The `MODULAR` layout wires the `module` prefix as the no-arg `JenesisModuleRepository` (the
public overlay, wrapped with `.cached(.jenesis/cache/)`) with `JenesisModuleRepository.ofLocal()`
prepended, so a local hit at `~/.jenesis` short-circuits the network fetch and a miss falls back to the
overlay transparently.

`https://repo.jenesis.build/modules/` is a publicly hosted instance of the Jenesis module repository
layout, run as a thin redirect layer in front of Maven Central: a request for
`<module>[/<version>]/<module>.jar` is answered by looking the module up in the
[sormuras/modules](https://github.com/sormuras/modules) catalogue and 302-ing to the matching jar at
`repo.maven.apache.org`, with the version segment in the resolved URL rewritten when the request carried
one. It is the no-arg default so that out-of-the-box every Jenesis project can resolve standard Java
modules without any per-project configuration. The local repository at `~/.jenesis` (returned by
`JenesisModuleRepository.ofLocal()`) is **not** a cache equivalent of Maven's `~/.m2/repository` - it is a
*supplement* to the remote map, used to publish modules locally so a project can deviate from whatever
version the overlay would resolve. A module exported there via `JenesisModuleRepositoryExport` (or
hardlinked in by hand) takes precedence over the overlay because the layout prepends it; remove or empty
that entry and the overlay's mapping wins again. Caching of remote fetches is a separate concern handled
per project under `.jenesis/cache/` (see *The `.jenesis/cache/` folder* below), so even a fresh `~/.jenesis`
does not trigger a re-download on the next build.

`Resolver` is a `Serializable @FunctionalInterface` whose method takes the root coordinates, the
`Map<String, Repository>` to fetch from, a `versions` pin map (with optional space-separated `<algorithm>/<hex>`
checksum suffix), and a `compile`/`runtime` flag, and returns the resolved closure as a
`SequencedMap<String, String>`. The value carries the chosen version and/or checksum that the downstream
`Download` step validates against. The default method `resolver.translated(targetPrefix, translator)`
rewrites both coordinates and the prefix before delegating; this is how `MODULAR_TO_MAVEN` plugs a
`MavenPomResolver` into `ModularJarResolver` as a fallback, mapping `module/<name>` coordinates through
`MavenUriParser` into the `maven/<groupId/artifactId>` form a Maven resolver understands.

Static factories: **`Resolver.identity()`** emits its inputs unchanged under the supplied prefix without any
transitive walk; **`Resolver.of(translator)`** flat-maps each coordinate through a
`Function<String, SequencedCollection<String>>` (useful for static, non-network resolution). The two
graph-walking implementations live in the per-layer sections below: `ModularJarResolver` under *Java support*
and `MavenPomResolver` under *Maven support*.

**Per-prefix dispatch.** Every `requires.properties` line is prefixed `<prefix>/<coordinate>`, and the
framework routes each line to `resolvers.get(prefix)` with the entire repository map attached (resolvers may
read from sibling prefixes when chaining). The two built-in prefixes are `maven` and `module`. Users define
new prefixes - or override the layout's defaults on the same prefix - by passing
`Map<String, Repository>` and `Map<String, Resolver>` through `Project.repositories(...)` /
`Project.resolvers(...)`; the user maps are `putAll`-merged *after* the layout defaults, so a user entry
under the same key wins, and the merged maps are forwarded through `JavaMultiProjectAssembler` into every
per-module `JavaModule` / `TestModule`.

### Jenesis Repository layout for Java modules

The Jenesis Repository for Java modules is the on-disk format that `JenesisModuleRepositoryExport` writes
and `JenesisModuleRepository` reads - the persistent, cross-project home for built modules, analogous to
`~/.m2/repository` for the Maven layout. `JenesisModuleRepositoryExport` and the static factory
`JenesisModuleRepository.ofLocal()` both default to `~/.jenesis` (overridable via the
`JENESIS_REPOSITORY_LOCAL` environment variable); `JenesisModuleRepository`'s no-arg constructor instead
defaults to the public overlay at `https://repo.jenesis.build/modules/` (see *The public overlay* at the
end of this section). A `URI`-arg constructor on `JenesisModuleRepository` accepts any root, so a
`file://`, `http://`, or `https://` URL pointing at a publication of the same shape works equally well.

**On-disk shape.** Each module owns a directory at the root named after its Java module name. Versioned
builds live under one extra path segment named after the version; unversioned builds live directly at the
module root. Inside either, the produced jars are named after the module, with optional `-sources.jar`
and `-javadoc.jar` siblings when the build emits them:

```
<root>/
  com.example.foo/                   # unversioned export
    com.example.foo.jar
    com.example.foo-sources.jar
  com.example.bar/                   # versioned export
    com.example.bar.jar              # root mirror of the most recently built bar
    com.example.bar-sources.jar
    1.0.0/
      com.example.bar.jar            # immutable version slot
      com.example.bar-sources.jar
    2.0.0/
      com.example.bar.jar
      com.example.bar-sources.jar
```

**Writes.** `JenesisModuleRepositoryExport` walks the `ModularStaging` output tree and hardlinks every
file into the matching slot under the root (copy fallback when the filesystem refuses the link). Each
target directory (module root or version subdirectory) is cleaned of pre-existing regular files on the
first write to it per run, so a build that no longer produces a `-javadoc.jar` does not leave a stale one
behind. Sibling version directories under the same module root are non-recursively untouched: exporting
`<module>/2.0.0/` cleans `<module>/` and `<module>/2.0.0/` but leaves `<module>/0.9/`, `<module>/1.0.0/`,
etc. exactly as they were. When the module is versioned, the export *also* mirrors each jar to the module
root, so `<root>/<module>/<module>.jar` always reflects the most recently built version regardless of
which version that was.

**Reads.** `JenesisModuleRepository.fetch(executor, coordinate)` parses the coordinate as
`<module>[/<version>]` and maps it to the file path:

- `<module>` (no version) -> `<root>/<module>/<module>.jar` (the root mirror).
- `<module>/<version>` -> `<root>/<module>/<version>/<module>.jar`.

A missing file produces `Optional.empty()` so resolvers can fall back. For `file://` roots the returned
`RepositoryItem` exposes the underlying `Path`, letting downstream caches hardlink instead of copy; for
HTTP roots the stream is opened eagerly and an HTTP 404 surfaces as `Optional.empty()` the same way.

**Versioned versus unversioned interplay.** Version subdirectories are *immutable points in history*:
once `<module>/<version>/` is populated, exporting any other version of the same module never touches
those files. A consumer that pins `@jenesis.pin <module> <version>` always reads from the versioned subdirectory
and is therefore unaffected by later re-exports of the same module at different versions. The root
mirror, by contrast, is *rolling latest*: every export of the module overwrites it, so an unversioned
fetch returns whichever build was most recently exported. The resolver pipeline funnels coordinates
through the versioned path after pinning - once a `@jenesis.pin <module> <version>` lands in source, downstream
`Resolve` and `Download` always ask for `<module>/<version>`, never the bare name - so the root mirror's
volatility cannot leak into a checksum-pinned build.

The shape is identical to the tree `ModularStaging` produces in `target/`, so the export is a straight
hardlink mirror with no format transformation. Republishing the local repository elsewhere (uploading it
to an HTTP server, copying it onto another machine, mounting a network share) is also a straight
file-tree mirror; the resulting URL or path is a valid `JenesisModuleRepository` root.

**The public overlay.** The default no-arg root - `https://repo.jenesis.build/modules/` - is a
publicly-served redirect layer that maps the same `<module>[/<version>]/<module>.jar` request shape onto
Maven Central. It is a thin Cloudflare Worker that looks the requested module up in the
[sormuras/modules](https://github.com/sormuras/modules) registry and 302s to the matching upstream jar;
when a version segment is supplied, the Worker rewrites the version inside the resolved URL before
redirecting, so any version that exists at the upstream coordinate is reachable - not just the one the
registry pins. The catalogue itself is refreshed manually, so brand-new versions may not be reachable
through the overlay until the next sormuras refresh; a project that needs a missing module/version can
layer its own `JenesisModuleRepository(URI.create(...))` on top, or feed `DownloadModuleUris` an updated
registry URL. The overlay holds no jar bytes of its own and performs no authentication of its own; cache
and transport behaviour are whatever Maven Central serves at the redirect target.

### The `.jenesis/cache/` folder

`.jenesis/cache/` is the project-root home for caches that should outlive a single build but stay local to the
project tree. It sits between `target/` (incremental per-run state, deletable to force a clean rebuild) and
`~/.m2/repository` (shared across every project on the user's machine). The path defaults to `.jenesis/cache/`
at the project root and can be relocated via `Project.cache(Path)` or `-Djenesis.project.cache=<path>`.

What lives there today: hardlinked jars fetched by the `MODULAR` layout's `JenesisModuleRepository`
overlay wrapped with `.cached(.jenesis/cache/)` (wired in `Project.Layout.MODULAR`). Each entry is
named `<BuildExecutorModule.encode(coordinate)>.jar`; the encoded coordinate is a content-stable function
of the coordinate string, so two builds asking for the same coordinate map to the same filename and the
second build hardlinks from `.jenesis/cache/` instead of going to the network. `MAVEN` and `MODULAR_TO_MAVEN`
do not populate this folder: their canonical `MavenDefaultRepository` already caches into
`~/.m2/repository`, so for those layouts `.jenesis/cache/` is typically empty.

Properties of the cache layer:

- **Content-addressable by coordinate.** Filenames derive from the coordinate, not the build run, so replays
  are filesystem lookups and the folder can be moved between machines without rewriting.
- **Refresh on demand only.** No TTL, no automatic invalidation: entries persist until something deletes
  them. The assumption is that jars at versioned coordinates are immutable by contract; force a refresh by
  deleting the file (or the whole folder) and re-running.
- **Safe to delete.** Nothing in `target/` references `.jenesis/cache/` directly and no build identity hashes
  through cache contents, so a wiped `.jenesis/cache/` only costs the next build's downloads. Conversely,
  deleting `target/` while keeping `.jenesis/cache/` is the fastest path to a clean rebuild that does not
  re-fetch anything.
- **Hardlinks, not copies.** Both the cache write (`Files.createLink` in `Repository.cached`) and downstream
  consumption use hardlinks where the filesystem allows, so a populated `.jenesis/cache/` does not multiply
  disk usage when its contents flow into `target/`.

### Java support

Generic infrastructure (`BuildExecutor`, `BuildStep`, `BuildExecutorModule`) doesn't know anything about Java. The
Java-specific classes are a thin layer of `BuildStep`/`BuildExecutorModule` implementations that plug into it.

- **`ProcessBuildStep`** is the abstract base for every step that shells out to an external command. Subclasses
  return their command-line via `process(...)`; the base class assembles the process, captures stdout/stderr,
  validates the exit code, and reports a `BuildStepResult`. It also defines the `process/<name>.properties`
  convention used by upstream steps to inject command-line arguments (see `JavaMultiProjectAssembler.Prepare`,
  which writes `process/jar.properties` with `--main-class=…` and `process/javac.properties` with
  `--module-version=…`).
- **`JdkProcessBuildStep`** extends `ProcessBuildStep` with a single twist: it serializes `Runtime.version()`
  into its config hash so a JDK upgrade invalidates every cached `javac`/`java`/`javadoc` output without any
  per-step opt-in.
- **`ProcessHandler`** wraps the actual invocation (forked `Process` or in-process `ToolProvider` call). The
  factory function passed to a step's constructor decides which: `Javac.tool()` runs `javac` in-process via
  `java.util.spi.ToolProvider`; `Javac.process()` forks. The same split exists for `Jar` and `Javadoc`.
- **`Javac`, `Jar`, `Javadoc`, `Java`** are the concrete tool drivers. They consume the conventional folders
  (`sources/`, `classes/`, `resources/`, `artifacts/`) and produce the conventional outputs documented in the
  *Conventional folders and files* section.
- **`TestModule`** is a `BuildExecutorModule` that wires `Java` into a runner. `TestEngine` plus the built-in
  `JUnit4`, `JUnit5`, and `TestNG` records encode per-framework metadata (main class, marker class used to
  detect the framework on the classpath, optional Maven coordinates of the runner) and each implements
  `commands(classes, methods)` to shape the CLI arguments for picking tests to run: `JUnit4` emits class
  names positionally and throws `IllegalArgumentException` if individual methods are requested, `JUnit5`
  emits `--select-class=` / `--select-method=` per entry, and `TestNG` joins everything into the single
  comma-separated `-testclass` / `-methods` arguments the `org.testng.TestNG` runner expects. `JUnit5` takes
  an explicit `jupiterVersion` and `platformVersion`; when no engine is passed, `TestEngine.of(...)` infers
  both from the `Implementation-Version` manifest entries of the Jupiter API and Platform Commons jars
  discovered on the inherited paths. New frameworks slot in by implementing `TestEngine` and choosing
  whatever argument shape the runner needs.
- **`JavaModule`** is the canonical `BuildExecutorModule` for "compile sources, version-stamp `module-info.class`,
  package as a jar". It delegates to `Javac`, `Versions`, and `Jar`. Build scripts that don't have multi-project
  structure (`Minimal.java`, `Manual.java`) wire it directly; test execution is a separate `TestModule` that
  `JavaMultiProjectAssembler` wires as a sibling when the project enables tests.
- **`ModuleInfoParser` / `ModuleInfo`** parse `module-info.java` via the `javax.tools` / `com.sun.source` APIs
  and surface the module name, its `requires` set (including `requires transitive`, `requires static`, and
  `requires static transitive`), and a `versions` map extracted from `@jenesis.pin <module> <version>` Javadoc tags
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

- **`MavenRepository`** extends the generic `Repository` with a structured
  `fetch(executor, groupId, artifactId, version, type, classifier, checksum)` and an optional
  `fetchMetadata(executor, groupId, artifactId, checksum)` returning the artifact's `maven-metadata.xml`.
  Implementations: `MavenDefaultRepository` (HTTP, with on-disk cache in the user's local Maven repository,
  default `~/.m2/repository`; the project's `.jenesis/cache/` folder is **not** used here) and any user-supplied
  subclass (e.g. an internal Nexus mirror). The `MAVEN_REPOSITORY_URI` environment variable overrides the
  default upstream URL; `MAVEN_REPOSITORY_LOCAL` overrides the local repository directory; and
  `MAVEN_REPOSITORY_TOKEN`, when set, is sent verbatim as an `Authorization` header on every HTTP fetch
  (e.g. `Bearer <token>`). See the *Repositories and resolvers* section above for the generic interface
  and its factories.
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
  coordinates that the downstream `Download` step consumes.
- **`MavenDependencyKey` / `MavenDependencyName` / `MavenDependencyValue` / `MavenDependencyScope`** are the
  data records the resolver operates on. `Key` is the conflict-resolution identity (groupId+artifactId+type
  +classifier, version excluded so the resolver can pick one); `Name` is `groupId+artifactId` for BOM/parent
  lookups; `Value` carries everything else (version, scope, optional, exclusions).
- **`MavenLocalPom`** captures a parsed POM (coordinates, parent, dependencies, dependency-management,
  properties). The resolver expands these lazily.
- **`MavenVersionNegotiator` / `MavenDefaultVersionNegotiator`** handle Maven's version-range syntax
  (`[1.0,2.0)`, `LATEST`, `RELEASE`, etc.) - picking a concrete version from a candidate list per the rules
  described in the Maven version comparison spec.
- **`MavenRepositoryStaging`** is the `BuildStep` that materialises the Maven repository layout under
  `target/stage/output/`. It walks every per-module `inventory.properties`, parses `prefix.pom` for
  `groupId`/`artifactId`/`version`, validates that `prefix.artifacts` lists exactly one `.jar` and
  `prefix.sources`/`prefix.documentation` each list at most one, then hardlinks the binary plus the (optional)
  sources/documentation jars plus the pom as
  `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>.{jar,pom}`. Modules carrying
  `prefix.test=<main-artifactId>` are routed onto the named main's coordinate with a `-tests` classifier, and
  their POMs are parsed for additional dependencies that get merged into the staged main POM with
  `<scope>test</scope>`.
- **`MavenRepositoryExport`** is the `BuildStep` that publishes the staged tree to an external Maven repository
  directory (default `~/.m2/repository`, overridable via the `MAVEN_REPOSITORY_LOCAL` environment variable).
  It always re-runs, walks each predecessor for `.pom` files, copies
  every sibling in the version directory into the target, and writes the `mvn install`-equivalent metadata
  (`maven-metadata-local.xml` per artifact, `_remote.repositories` markers per version dir, per-version
  snapshot metadata for `-SNAPSHOT` versions).
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
`DependenciesModule` (or to `TestModule` for the test runner side-channel), and the generic infrastructure
dispatches coordinates to the right resolver by prefix.

Project metadata
----------------

Jenesis carries POM descriptive metadata (name, description, url, license, developer, SCM) through a single
hash-tracked channel keyed off a properties file named by convention `metadata.properties`. The same channel is
fed by per-layout defaults extracted from the project's own sources (module-info or pom.xml), and overridden
key-by-key by the user-supplied file.

### Pointing Jenesis at the file

The file path is set in one of two equivalent ways:

- System property: `-Djenesis.project.metadata=project.properties` (path resolved relative to the project root).
- Programmatic API: `Project.builder().metadata(Path.of("project.properties"))`.

A `null` (unset) value means no project-level metadata file; Jenesis still emits POMs that contain only the
fields the active layout supplies. When the value is set, the assembler binds the file in through the
top-level `metadata` module so its entries reach every per-module `Manifests` step as upstream input and are
folded into each per-module `metadata.properties` last (winning on any overlapping key). Because the file's
content participates in the build hash chain, any edit to it invalidates downstream `pom` and `stage` outputs
the same way a source change would.

### Recognised keys

```properties
# Maven coordinates. The framework writes these into every per-module
# metadata.properties from the project's own source (pom.xml for MAVEN, the
# Java module system module name for MODULAR). Placing them in the project-root override
# file is rarely needed; the most common reason is overriding the version
# from outside the project (typically via -Djenesis.project.version=<v>,
# which appends a `version` entry to this same channel as the last layer).
project=build.jenesis
artifact=build.jenesis
version=0-SNAPSHOT

# Descriptive metadata - usually supplied by the layout (see below) and only
# placed here when overriding. Emitted as <name>, <description>, <url>.
name=Jenesis
description=A build tool for Java projects, written and configured in Java itself.
url=https://github.com/raphw/jenesis

# One or more <license> entries, keyed by id. The `<id>` segment is the
# license name lowercased with spaces and dots replaced by `_` (so the key
# segment itself can never contain a dot, which is what makes the
# `license.<id>.<attribute>` parse unambiguous). Add another license by
# using a different id segment.
license.apache-2_0.name=Apache-2.0
license.apache-2_0.url=https://www.apache.org/licenses/LICENSE-2.0.txt

# One or more <developer> entries, keyed by id. The key after `developer.`
# names the developer's id (used verbatim as <id>); the remaining suffix
# selects the attribute (`name`, `email`). Add another developer by using
# a different id segment.
developer.raphw.name=Rafael Winterhalter
developer.raphw.email=rafael.wth@gmail.com

# <scm> block. <developerConnection> is omitted-then-derived: when
# scm.developerConnection is missing, the emitter writes scm.connection
# into <developerConnection> as well, so most projects only need the two
# keys below.
scm.connection=scm:git:https://github.com/raphw/jenesis.git
scm.url=https://github.com/raphw/jenesis
```

Jenesis writes per-module descriptive metadata to a separate file from per-module graph state:

- **`module.properties`** (constant `BuildStep.MODULE`) carries graph-coordination keys only - `path`,
  `test`, `main`, and (on `MODULAR`) `module=<java-module-name>`. The framework writes it; the user never
  edits it. `Project.PinModule` reads `path` from it to locate source files; `Inventory` mirrors `module`,
  `test`, and `main` into the per-module `inventory.properties` so the staging steps (`MavenRepositoryStaging`,
  `ModularStaging`) and the launcher (`Execute`) see them through a single, self-anchored channel.
- **`metadata.properties`** (constant `BuildStep.METADATA`) carries Maven coordinates and POM-descriptive
  keys - `project`, `artifact`, `version`, `name`, `description`, `url`, `license.<id>.*`, `developer.<id>.*`,
  `scm.*`. Each layout's `Manifests` step writes per-module defaults: coordinates come straight from `pom.xml`
  for MAVEN and are derived from the Java module system module name for MODULAR (with `0-SNAPSHOT` as the default version);
  descriptive keys are extracted from the module-info Javadoc or the source `pom.xml`. The project-root
  override file (conventionally `project.properties`, pointed at by `-Djenesis.project.metadata`) overlays
  project-wide values on top in `Manifests` (later puts win), so a user-supplied entry beats both the
  framework default and the source-extracted value.

### Per-layout defaults

Each layout's `Manifests` step writes its own `metadata.properties` into the per-module manifests folder
(omitted when no descriptive keys apply), so defaults travel through the same predecessor channel as the
user-supplied override file. The user's file is iterated last, so user keys override layout-derived keys
on a key-by-key basis.

`MODULAR` extracts `name` from the module-info's javadoc first sentence (trailing `.` stripped) and
`description` from the rest of the body. The same parser pass that already reads `@jenesis.release` and
`@jenesis.pin` reads the description, so the cost is essentially free:

```java
/**
 * Jenesis.
 *
 * A build tool for Java projects, written and configured in Java itself.
 *
 * @jenesis.release 25
 */
module build.jenesis { ... }
```

contributes `name=Jenesis` and `description=A build tool for Java projects, ...` automatically.
A javadoc with no body produces neither key. A single sentence with no trailing body produces only
`name`.

`MAVEN` (and `MODULAR_TO_MAVEN`) parses each module's source `pom.xml` for `<name>`, `<description>`,
`<url>`, every `<license>`, every `<developer>`, and the `<scm>` block, and writes the same property
keys into `metadata.properties`. The extraction is a direct DOM read - property expansion (`${var}`) and
parent POM inheritance are deliberately not applied to these specific fields. A project that needs
`${project.url}` substituted, or a value inherited from a parent POM, must put the resolved value into
the project-root `metadata.properties` so it overrides the literal default.

### The Pom step's predecessor order

`Pom` runs once per module and emits exactly one `pom.xml` from the merged metadata. It reads each predecessor
folder for `identity.properties`, `metadata.properties`, `requires.properties`, and `scopes.properties`, and
parses `groupId`/`artifactId`/`version` and the descriptive fields from the merged `metadata.properties`. The
override chain for the file's values is:

1. The framework defaults (`project`/`artifact`/`version`) written by the layout's `Manifests` step land first.
2. The descriptive metadata extracted from the source (`pom.xml` for MAVEN, module-info Javadoc for MODULAR) is
   merged on top of those defaults inside the same `Manifests` step.
3. The project-root override file (the file `-Djenesis.project.metadata` points at, conventionally
   `project.properties`) is merged last via the executor's `metadata` module, so user-supplied entries win on
   any overlapping key.
4. `-Djenesis.project.version=<v>` is appended after all of the above and overrides any `version` from any
   layer, so a release build can pin a single version on the command line without editing any file.

Releasing to Maven Central
--------------------------

This section describes the release pipeline that any project using Jenesis can adopt. The next section
documents how this repository wires those mechanisms together for its own releases.

### The stage step

Each of the three `Project.Layout` constants wires a `stage` step that depends directly on `BUILD`. The Maven-side
layouts use `MavenRepositoryStaging`, which combines Maven repository placement with test-aware POM merging in
one pass; `MODULAR` uses the simpler `ModularStaging`:

```java
executor.addStep("stage", new MavenRepositoryStaging(project.stageTests()), BUILD); // MAVEN, MODULAR_TO_MAVEN
executor.addStep("stage", new ModularStaging(project.stageTests()),         BUILD); // MODULAR
```

Both staging steps walk every per-module `inventory.properties` reachable through the `BUILD` predecessor and
resolve its self-anchored paths against the inventory file's own folder. The step writes its tree under
`target/stage/output/`. Files are hard-linked rather than copied; like every other Jenesis step, its output is
content-hashed and skipped on re-runs when inputs are unchanged.

Under `MavenRepositoryStaging` (used by `MAVEN`, `MODULAR_TO_MAVEN`), each inventory is classified by reading its
`prefix.test` value, then staged differently:

- **Main modules** (no `prefix.test` in the inventory) have their `prefix.artifacts` jar (plus the optional
  `prefix.sources` and `prefix.documentation` jars) hard-linked at the standard Maven repository path
  `<groupId-as-path>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<ext>`. The staging step
  validates that `prefix.artifacts` lists exactly one `.jar` and that each of `prefix.sources` /
  `prefix.documentation` lists at most one. Coordinates come from parsing `prefix.pom` (the staged POM emitted
  by the `Pom` step). The pom is either hard-linked as-is, or - when at least one test variant points at this
  main - written out via DOM merge: the main POM's `<dependencies>` gains one `<dependency>` per test-variant
  dep, each carrying `<scope>test</scope>`. Test deps that point back at any main artifact (a Java module
  system test module's `requires <main>;` becomes a `<dependency>` on `<main>`) are dropped from the merge to
  avoid self-references.
- **Test variants** (`prefix.test=<main-artifactId>` in the inventory) have their jars hard-linked under the
  **main's** Maven coordinate (not their own) with a `-tests` classifier suffix: `<main>-<version>-tests.jar`,
  `<main>-<version>-tests-sources.jar`, `<main>-<version>-tests-javadoc.jar`. No separate POM is staged for the
  test variant; the merged main POM is the single canonical POM for the coordinate. Duplicate main artifactIds
  and multiple test modules naming the same main fail loudly.

The `test` key on the inventory mirrors the same key in `module.properties`, set from existing metadata:
`MavenProject.Manifests` flags any per-module variant whose generated coordinate carries the `tests` classifier,
and `ModularProject.Manifests` flags any module whose `module-info.java` declares an `@jenesis.test` Javadoc tag
(parsed by `ModuleInfoParser` into `ModuleInfo.testOf()`). Its value is the `artifactId` of the main module the
tests cover (or empty for the deprecated bare `@jenesis.test` form, which only resolves when exactly one main module
is present). It does not refer to a Maven `<parent>` POM relationship.

Under `ModularStaging` (used by `MODULAR`), each inventory's `prefix.module` (the Java module system module
name) becomes the staging directory and jar prefix; no POM is required or written. The `test` marker on the
inventory is **ignored** by `ModularStaging` when `-Djenesis.project.stageTests=true`; test modules are then
staged under their own Java-module-named directory with no `-tests` suffix and no merging. When the flag is
unset (default), test modules are simply omitted from the staging tree. When `prefix.version` is present (set
on the inventory from `metadata.properties`' `version` key, which both layouts always populate today), it is
inserted as an additional path segment between the module name and the jar files.

The resulting trees under `target/stage/output/` (with `<module>=build.jenesis`, `<v>=1.0.0`,
`-Djenesis.project.sources=true`, `-Djenesis.project.documentation=true`):

`MAVEN` and `MODULAR_TO_MAVEN` (Maven repository layout, identical for the Jenesis project):

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

`MODULAR` with `-Djenesis.project.version=1.0.0`:

```
target/stage/output/
└── build.jenesis/
    └── 1.0.0/
        ├── build.jenesis.jar
        ├── build.jenesis-sources.jar
        └── build.jenesis-javadoc.jar
```

`MODULAR` with the project's default `0-SNAPSHOT` version (still produces a version segment, since
`metadata.properties` always carries a `version`):

```
target/stage/output/
└── build.jenesis/
    └── 0-SNAPSHOT/
        ├── build.jenesis.jar
        ├── build.jenesis-sources.jar
        └── build.jenesis-javadoc.jar
```

(`MAVEN` and `MODULAR_TO_MAVEN` route the test module's jars onto the main artifact's coordinate with a
`-tests` classifier suffix and merge the test-variant dependencies into the main POM with
`<scope>test</scope>`; the per-module `prefix.test=<main-artifactId>` marker on the inventory triggers this
in `MavenRepositoryStaging`. `MODULAR` ignores that marker and stages every discovered Java module under its
own Java-module-named directory at the same level when `-Djenesis.project.stageTests=true`.)

The `MAVEN` and `MODULAR_TO_MAVEN` layouts additionally wire a `MavenRepositoryExport` step after `stage`,
which copies the staged tree into the user's local Maven repository (default `~/.m2/repository`) and writes the
`mvn install`-equivalent metadata. Run `java build/jenesis/Project.java export` to perform both `stage` and
the local-repository copy in one invocation.

A release build is therefore typically:

```
java -Djenesis.project.version=<version> \
     -Djenesis.project.sources=true \
     -Djenesis.project.documentation=true \
     -Djenesis.project.metadata=project.properties \
     build/jenesis/Project.java stage
```

`-Djenesis.project.version=<v>` is what `Javac` stamps as `--module-version` and what the `Pom` step writes into
`<version>`; the staged paths use the same value to form `<artifactId>-<version>[.<classifier>].<ext>`.

### Handing off to JReleaser

[JReleaser](https://jreleaser.org) consumes the staged directory directly. A `jreleaser.yml` at the project
root with `deploy.maven.mavenCentral.sonatype.stagingRepositories` pointing at `target/stage/output/`, plus the
standard `JRELEASER_MAVEN_CENTRAL_SONATYPE_USERNAME`/`_TOKEN` and `JRELEASER_GPG_*` environment variables, lets
a single `jreleaser deploy` (or `full-release` from `jreleaser/release-action@v2` in CI) sign and upload the
staged artifacts to Maven Central. `JRELEASER_PROJECT_VERSION` should be set to the same value passed as
`jenesis.project.version` so JReleaser and the emitted POM agree on the coordinate.

How Jenesis itself releases
---------------------------

The release configuration that this repo actually uses lives in three files: `metadata.properties` at the root,
`jreleaser.yml` at the root, and `.github/workflows/release.yml`.

`metadata.properties` carries the SCM, license, developer, url, and the `module=build.jenesis` filter
that limits the staged tree to the main artifact (excluding `module-tests`). `name` and
`description` are not in this file because the `MODULAR` layout reads them from the module-info's
javadoc, and `module-info.java` has:

```java
/**
 * Jenesis.
 *
 * A build tool for Java projects, written and configured in Java itself.
 *
 * @jenesis.release 25
 */
module build.jenesis { ... }
```

`pom.xml` at the root mirrors the same metadata for IDE/Maven consumers and for Jenesis itself if anyone forces
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

The workflow then runs the release build (the canonical command above, with `jenesis.project.version` set to the
resolved version) and hands off to `jreleaser/release-action@v2` with `full-release`. JReleaser signs, uploads,
and (because `release.github.skipRelease: false`) cuts a matching `v<version>` git tag, which the next
`[release]` commit will pick up to compute its own auto-incremented version.

Using Jenesis as a CLI
----------------------

Alongside the Maven Central artifacts, every release produces a self-contained CLI distribution
(`jenesis-<version>.zip`) that bundles the published jar with a small set of launcher scripts.
This zip is attached to the matching GitHub release and is the same artifact served by SDKMAN.
Once unpacked it looks like:

    jenesis-<version>/
      bin/
        jenesis,          jenesis.bat
        jenesis-exec,     jenesis-exec.bat
        jenesis-init,     jenesis-init.bat
        jenesis-validate, jenesis-validate.bat
        jenesis-version,  jenesis-version.bat
        jenesis-switch,   jenesis-switch.bat
      lib/
        build.jenesis-<version>.jar
      sources/
        build.jenesis-<version>-sources.jar
      LICENSE

Each script comes in two flavours: a POSIX bash script (no extension) and a Windows batch file
(`.bat`); the batch versions mirror the bash logic exactly. Every script derives `JENESIS_HOME`
from its own location, so the unpacked tree is fully relocatable.

### Shell scripts

- **`jenesis`** is a thin launcher around the bundled jar. It locates a Java 25+ runtime
  (`JAVA_HOME` first, then `java` on `PATH`), verifies the major version, and runs
  `java -p <home>/lib -m build.jenesis "$@"`. All arguments pass through, so `jenesis +sources`
  is the SDK equivalent of `java build/jenesis/Project.java +sources` from a project root.
  `JAVA_OPTS` is honoured.

- **`jenesis-exec`** is the companion launcher around `Execute` (see
  [Running a module's main entry](#running-a-modules-main-entry)). Same runtime resolution and
  `JAVA_OPTS` handling as `jenesis`, but runs `java -p <home>/lib -m build.jenesis/build.jenesis.Execute "$@"`,
  so `jenesis-exec arg1 arg2` is the SDK equivalent of
  `java build/jenesis/Execute.java arg1 arg2` from a project root. The
  `jenesis.execute.module`, `jenesis.execute.mainClass`, `jenesis.execute.docker`, and
  `jenesis.execute.docker.image` system properties apply.

  Trailing arguments to `jenesis` and `jenesis-exec` pass straight through as selectors (for
  `jenesis`) or as program arguments to the launched main (for `jenesis-exec`); they reach
  `Project.main` / `Execute.main` as `String... args`, not the JVM. Set JVM-level flags such as
  the `jenesis.project.*` and `jenesis.execute.*` system properties via `JAVA_OPTS`, which the
  scripts splice in before `-m`:

      JAVA_OPTS="-Djenesis.project.layout=maven -Djenesis.project.skipTests=" jenesis
      JAVA_OPTS="-Djenesis.execute.module=tools -Djenesis.execute.docker=true" jenesis-exec arg1 arg2

  Multiple `-D…` (or `-X…`) flags can be chained inside the single `JAVA_OPTS` string.

- **`jenesis-init`** extracts the bundled `*-sources.jar` into each target's `build/jenesis`
  directory, deleting any existing `build/jenesis` first and writing the SDK's version into
  `build/jenesis/jenesis.version`. The bundled `module-info.java` is dropped, since the
  consuming project carries its own. With no arguments the current directory is the target;
  with one or more arguments each path is processed in turn and lines are prefixed with the
  target name. This is the no-submodule analog of the `ln -s` step from
  [Installing](#installing).

- **`jenesis-validate`** extracts the bundled sources to a temporary directory and
  SHA-256-compares every file against the matching file in the target's `build/jenesis`. It
  reports per-file `differs` / `missing` / `additional` lines and a final summary with counts,
  and emits a `version differs` line when `build/jenesis/jenesis.version` does not match the
  SDK's version. As with `jenesis-init`, the current directory is the default target.

- **`jenesis-version`** prints the SDK version and the version recorded in
  `build/jenesis/jenesis.version` for each target. It exits 0 only when every target's recorded
  version matches the SDK version, and 1 otherwise (including when `build/jenesis` or the
  version file is missing). It is intended as a CI check that a submodule update has been
  propagated to all consuming projects.

- **`jenesis-switch`** discovers the version recorded in `build/jenesis/jenesis.version` across
  the given targets and switches the current shell to that version via SDKMAN, installing it on
  the fly if it is not already present locally. Unlike the other scripts, it **must be sourced**,
  so that `sdk use` modifies the calling shell rather than a subprocess:

      . jenesis-switch                # source from current directory
      . jenesis-switch project-a project-b

  Every target must agree on the recorded version; a mismatch aborts the switch with exit code 1.
  A version that is not known to SDKMAN (i.e. `sdk install jenesis <version>` does not produce a
  candidate directory) also exits 1. The install runs non-interactively and **does not** set the
  installed version as the SDKMAN default - the switch only affects the current shell. Run
  `sdk default jenesis <version>` separately if you want new shells to start on that version as
  well. The script ships only as a POSIX shell script; the `.bat` stub errors out because native
  Windows is not a supported SDKMAN target (use WSL instead).

  Because `jenesis-switch` modifies the current shell's `PATH`, a chained call resolves to the
  newly-switched SDK's binaries. A useful "align then verify" idiom for CI is:

      . jenesis-switch && jenesis-validate

  This installs the project's pinned version on the fly (if needed), activates it for the shell,
  and then SHA-256-checks the checked-in `build/jenesis` against the bundled sources of that
  exact version. The combined exit code is non-zero if either step fails, so the chain is safe
  to drop into a CI pipeline as a single verification command.

### Installing via SDKMAN

[SDKMAN](https://sdkman.io) packages Jenesis as the `jenesis` candidate. On any system where
SDKMAN runs (Linux, macOS, WSL), installation is a single command:

    sdk install jenesis           # install the latest version
    sdk install jenesis 0.0.2     # pin a specific version
    sdk use jenesis 0.0.2         # switch the active version in this shell
    sdk current jenesis           # show the active version

SDKMAN unpacks the zip under `~/.sdkman/candidates/jenesis/<version>/` and adds its `bin/`
directory to `PATH`, so `jenesis`, `jenesis-init`, `jenesis-validate`, and `jenesis-version`
become immediately available. From a project root that contains a `module-info.java` or
`pom.xml`, the usual entry point reduces to:

    jenesis

On systems without SDKMAN (notably Windows without WSL), the same zip can be downloaded from
the matching GitHub release and unpacked anywhere on disk; adding the unpacked `bin/` directory
to `PATH` makes the scripts available with no further setup.
