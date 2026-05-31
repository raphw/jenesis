Jenesis demos
=============

Self-contained showcases, one per directory. Each has its own `build/jenesis`
symlink into this repository's `sources/build/jenesis`, so every demo runs in
isolation from inside its own directory - no installation step.

| Demo                | Shows                                                                 | Run from the demo dir              |
| ------------------- | -------------------------------------------------------------------- | ---------------------------------- |
| `java-pom`          | POM layout: plain `javac` + a real Maven dependency, pinned          | `java build/jenesis/Project.java`  |
| `java-modular`      | MODULAR layout: a `module-info.java`, no `pom.xml`; a named-module dependency, pinned; emits a modular jar | `java build/jenesis/Project.java`  |
| `java-pom-multi`    | Multi-module POM layout: a library module + a consumer module depending on it and on a real Maven dependency, pinned | `java build/jenesis/Project.java`  |
| `kotlin`            | MODULAR layout: Java + Kotlin, no `pom.xml`; Kotlin compiler pinned (qualified) | `java build/jenesis/Project.java`  |
| `scala`             | POM layout: Java + Scala 3 in one project; Scala compiler pinned (qualified) | `java build/jenesis/Project.java`  |
| `groovy`            | POM layout: a Groovy source; Groovy compiler pinned (qualified)     | `java build/jenesis/Project.java`  |
| `internal-module`   | Wrap the assembler to preprocess sources from a build module loaded with `InternalModule`, using an external dependency | `java build/Demo.java`             |
| `external-module`   | Same as `internal-module`, but stages the build module to a jar and resolves it as an `ExternalModule` coordinate | `java build/Demo.java`             |
| `custom-assembler`  | Wrap `JavaMultiProjectAssembler` to preprocess sources before the regular flow | `java build/Custom.java`           |
| `custom-build`      | No `Project` at all: steps wired directly to a `BuildExecutor`, with a code-generation step | `java build/Build.java`            |

The `java-pom`, `scala`, and `groovy` demos are Maven-layout projects (a
`pom.xml`) driven by the shipped `Project` entry point. The `java-modular` and
`kotlin` demos have no `pom.xml` - only a `module-info.java` - so Jenesis
auto-detects a modular layout and emits a modular jar. The `scala` demo mixes a
`.java` source with `Sample.scala`, and `kotlin` mixes one with `Sample.kt`, so
`javac` participates in the inferred compiler chain; `groovy` ships a single
`.groovy` source (see its `README.md` for why it does not add a Java companion).
Scala and Groovy stay on a `pom.xml` because their standard libraries are not
published as single named Java modules; see those demos' `README.md` for the
detail. The `java-pom-multi` demo extends the POM layout to more than one module:
a library module and a consumer module that depends on it, where the consumer
also pulls one real external dependency, so a single build resolves both an
intra-project sibling and an external artifact. The
`custom-assembler` demo keeps a standard modular layout but swaps the assembler:
its `build/Custom.java` wraps the stock `JavaMultiProjectAssembler` so each
module's sources pass through a preprocessing step before the regular compile,
jar, and test flow runs unchanged. The `internal-module` demo is the same idea,
but the preprocessing lives in a build module that `InternalModule` loads from
local source and that uses an external dependency (`org.json`) for the rewrite.
The `external-module` demo is identical to `internal-module` except that its
`build/Demo.java` first stages that build module to a jar in a nested target
folder and then resolves it as an `ExternalModule` coordinate instead of
compiling it in place. Both `internal-module` and `external-module` currently
fail at the build-module load step until a matching `build.jenesis` is released
(see their `README.md`s). The `custom-build` demo goes the other direction
entirely: it uses no `Project`, layout, or assembler, wiring sources and steps
directly to a `BuildExecutor` - including a code-generation step that synthesizes
a source the compiler then sees - to show that a build can be assembled fully by
hand when the templates do not fit.

Each demo writes to a local `target/` directory; delete it to rebuild from
scratch. See the per-demo `README.md` for details.

Pinning and qualifiers
----------------------

The demos are committed in their pinned state, at **stable** dependency
versions, and each demonstrates how Jenesis records dependencies in source:

- `java-pom` has an ordinary Maven dependency (`commons-lang3 3.14.0`) pinned
  with a content checksum into `<dependencyManagement>`. `java-modular` declares
  a *named module* dependency (`requires org.slf4j`, version `2.0.16`), pinned
  with an `@jenesis.pin org.slf4j 2.0.16` Javadoc tag on the module declaration.
  Neither pure-Java project has a resolved compiler, so neither has a qualified
  trail - their pins use the plain `maven` / `module` prefix.
- The `java-pom-multi` demo pins the same way across two modules. Only the
  external dependency is pinned (`commons-lang3`); the intra-project sibling
  dependency is left unpinned, since coordinates produced within the project
  carry no stable version or checksum to pin against.
- The `scala` and `groovy` demos pin their compiler closures on an independent
  *qualified* trail, keyed `<prefix>@<qualifier>/<coordinate>` (`@scala/...`,
  `@groovy/...`), stored in a Maven-ignored `<!--jenesis.pin-->` comment in
  `pom.xml` so they never collide with the project's own dependencies. The
  compilers are qualified `scala` / `groovy` automatically.
- The `kotlin` demo is modular (a `module-info.java`, no `pom.xml`), so it pins
  in source with `@jenesis.pin` Javadoc tags on the module declaration: its
  `kotlin.stdlib` dependency on the plain `module` trail, and - like `scala` and
  `groovy` - its Kotlin compiler is qualified `kotlin` automatically.
- The `internal-module` / `external-module` demos pass an explicit `"tool"`
  qualifier as the module's constructor argument, putting the loaded build
  module's dependency closure on its own trail.
