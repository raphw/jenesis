Pure modular layout demo
========================

Build a modular Java project whose artifacts are consumed only as Java modules:
dependencies are resolved by Java module name and the build produces a modular
jar with no `pom.xml` at all. This is the same kind of project as
`../demo-02-java-modular`, but with the pure MODULAR layout selected explicitly
instead of the default MODULAR_TO_MAVEN, so the build stays free of Maven
coordinates end to end.

Run it
------

    java build/Demo.java

`requires org.slf4j` is satisfied by fetching `org.slf4j` as a named module from
the module repository. Building leaves **no `pom.xml`** anywhere under `target/`
(contrast `../demo-02-java-modular`, the same project under MODULAR_TO_MAVEN, which
emits one). Staging shows the same split:

    java build/Demo.java stage      # target/stage/modular only - no target/stage/maven

`export` then publishes the modular jar (and any `.jmod`) into the local Jenesis
module repository (`~/.jenesis`), keyed by module name and version, with no Maven
coordinate anywhere in the pipeline.

Selecting the layout in code
----------------------------

A `module-info.java` with no `pom.xml` auto-detects MODULAR_TO_MAVEN; the pure
MODULAR layout is never chosen automatically, so you ask for it. `build/Demo.java`
does that on the `Project` builder (the in-code equivalent of
`-Djenesis.project.layout=modular`):

    new Project()
            .layout(Project.Layout.MODULAR)
            .build(args);

Layout
------

    demo-18-module-layout
    |-- build/jenesis            symlink to ../../../sources/build/jenesis
    |-- build/Demo.java          builds with Project.Layout.MODULAR
    `-- sources
        |-- module-info.java     module demo.modulelayout, requires org.slf4j (pinned by module name)
        `-- sample/Sample.java   uses org.slf4j

MODULAR vs MODULAR_TO_MAVEN
---------------------------

Both layouts take a `module-info.java` and produce a genuine modular jar. They
differ in how dependencies are resolved and what else is emitted:

- **MODULAR_TO_MAVEN** (the default) translates each `requires` into the declaring
  module's **Maven coordinate**, then resolves the transitive closure through
  Maven - nearest-wins versions, `<dependencyManagement>` from `@jenesis.pin`,
  Maven scopes. It emits the modular jar **plus a generated `pom.xml`**, so the
  artifact is publishable to Maven Central and consumable by Maven projects. It can
  also pull in plain-classpath and automatic-module dependencies, since Maven
  coordinates do not require a module name.

- **MODULAR** resolves dependencies **purely by Java module name** against the
  Jenesis module repository (`https://repo.jenesis.build/module/`, with the local
  `~/.jenesis` prepended) - no Maven coordinates are involved. It emits **only the
  modular jar; no `pom.xml`**. Because every dependency is resolved as a named
  module, the resolved closure is guaranteed to be consumable on the module path.

  The trade-off is that this **restricts the usable dependencies to named (explicit)
  modules**: a library that ships only as an *automatic* module (a plain jar whose
  module name is inferred from its filename or `Automatic-Module-Name`) or as a
  classpath-only jar cannot be required here, because it has no stable module name
  to resolve against. Such a dependency works under MODULAR_TO_MAVEN, which reaches
  it by Maven coordinate, but not under MODULAR - which is also why `AUTO` never
  selects MODULAR for you. So picking this layout narrows what you can depend on to
  the subset of the ecosystem published as proper Java modules.

Seeing the difference in the tree
---------------------------------

`-Djenesis.print.dependencies=true` prints each module's resolved dependency tree as it
resolves (a verbose toggle, not a build step), and it makes the layout distinction
concrete. The same `requires org.slf4j` shows up differently under each layout.

Under **MODULAR** (this demo) the node is a Java module name resolved from the
module repository - no Maven coordinate, no Maven scope:

    java -Djenesis.print.dependencies=true build/Demo.java

    Dependency tree:
    module/org.slf4j 2.0.16

Under **MODULAR_TO_MAVEN** (the same project in `../demo-02-java-modular`) the
module is translated to its Maven coordinate and resolved through Maven, so the
node is a Maven artifact carrying a Maven scope:

    Dependency tree:
    maven/org.slf4j/slf4j-api 2.0.16 [compile]

In a project with transitive dependencies the Maven side expands the full
nearest-wins closure (resolved versions, scopes, and duplicates dimmed and marked
`(*)`), while the modular side stays a graph of named modules.

When to use it
--------------

Reach for MODULAR when your artifacts are only ever consumed as Java modules and
you want a build that is provably module-path-clean and free of Maven entirely.
Keep the default MODULAR_TO_MAVEN when you also want a `pom.xml` - to publish to
Maven Central, or to depend on libraries that are only available as Maven
coordinates or automatic modules.
