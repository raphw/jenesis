Publishing demo
===============

Publishing to Maven Central is two jobs: **produce a correct, complete bundle**,
and **upload it**. Jenesis owns the first; the upload (with credentials and GPG
signing) is left to a dedicated release tool. This demo does the part Jenesis
owns - end to end, offline - and explains the last mile rather than performing
it, so nothing here ever touches the network or needs a secret.

What Central requires of every artifact is exactly what trips people up: a POM
carrying `name`, `description`, `url`, `<licenses>`, `<developers>`, and `<scm>`,
plus a `-sources.jar` and a `-javadoc.jar` beside the main jar. This demo shows
Jenesis assembling all of that from a `module-info.java` and a `project.properties`.

Layout
------

    demo/demo-20-publishing
    |-- build/jenesis            symlink to ../../../sources/build/jenesis
    |-- build/Demo.java          stages the release bundle, then resolves it back to prove it is consumable
    |-- project.properties       only what a module declaration cannot express: url, license, developer, scm
    `-- sources
        |-- module-info.java     module build.jenesis.demo.publishing (MODULAR_TO_MAVEN: a POM is generated)
        `-- sample/Sample.java   the library being published

Where the POM metadata comes from
---------------------------------

Jenesis folds two channels into the per-module `metadata.properties` it feeds to
the POM emitter, so the descriptor stays the source of truth and nothing is
duplicated:

- **The `module-info.java`** supplies everything it can. The module *name* derives
  the Maven coordinate - groupId from the first two dotted segments, artifactId
  from the full name - so `module build.jenesis.demo.publishing` becomes
  `build.jenesis : build.jenesis.demo.publishing`. The module declaration's
  Javadoc supplies `<name>` (its first sentence) and `<description>` (its body).
- **`project.properties`** (bound via `Project.metadata(...)`) carries only what a
  module declaration cannot express: `url`, every `license.<id>.name|url`, every
  `developer.<id>.name|email`, and the `scm.connection|developerConnection|url`
  block.

The version is stamped by `Project.version("1.0.0")` (otherwise it defaults to
`1-SNAPSHOT`). If you ever need a coordinate that does not follow from the module
name, `project=...` / `artifact=...` in `project.properties` override the derived
values - but the point here is that you usually do not have to.

Run it
------

    java build/Demo.java

`build/Demo.java` configures publication explicitly on the `Project` builder -
`new Project().target(...).sources(true).documentation(true).version("1.0.0").metadata(Path.of("project.properties"))` -
and runs the `stage` target. That materializes the release tree in Maven
repository layout, and `build` hands back the staging step's folder under the
`stage/maven` key:

    build/jenesis/build.jenesis.demo.publishing/1.0.0/build.jenesis.demo.publishing-1.0.0.jar
    build/jenesis/build.jenesis.demo.publishing/1.0.0/build.jenesis.demo.publishing-1.0.0.pom
    build/jenesis/build.jenesis.demo.publishing/1.0.0/build.jenesis.demo.publishing-1.0.0-sources.jar
    build/jenesis/build.jenesis.demo.publishing/1.0.0/build.jenesis.demo.publishing-1.0.0-javadoc.jar

The generated `.pom` carries the full Central-required metadata, assembled from the
two channels above:

    <groupId>build.jenesis</groupId>
    <artifactId>build.jenesis.demo.publishing</artifactId>
    <version>1.0.0</version>
    <name>Jenesis Publishing Demo</name>
    <description>A sample library whose name and description are taken from this Javadoc ...</description>
    <url>https://github.com/raphw/jenesis</url>
    <licenses>...</licenses>
    <developers>...</developers>
    <scm>...</scm>

Then `Demo.java` proves the bundle is real: the staged tree is itself a Maven
repository, so it recovers the coordinate from the staged layout (hard-coding
nothing) and resolves it straight back out, reporting each artifact:

    Resolving build.jenesis:build.jenesis.demo.publishing:1.0.0 from the staged repository:
      [resolved] build.jenesis.demo.publishing-1.0.0.jar
      [resolved] build.jenesis.demo.publishing-1.0.0.pom
      [resolved] build.jenesis.demo.publishing-1.0.0-sources.jar
      [resolved] build.jenesis.demo.publishing-1.0.0-javadoc.jar

That is the whole bundle a release would carry, validated without publishing
anything.

Reproducibility
---------------

A published artifact should be reproducible: anyone rebuilding the same sources
should get the same bytes. To show this, `build/Demo.java` actually stages the
project **twice**, into two independent target trees (`target/first` and
`target/second`), then SHA-256-compares every staged file:

    Reproducibility: SHA-256 of the staged artifacts from two independent builds:
      [identical] d91d7fdbca6dd47d  .../build.jenesis.demo.publishing-1.0.0-javadoc.jar
      [identical] 3e724f5745988205  .../build.jenesis.demo.publishing-1.0.0-sources.jar
      [identical] 98b7cebd090a24d8  .../build.jenesis.demo.publishing-1.0.0.jar
      [identical] 34b4535a7753db42  .../build.jenesis.demo.publishing-1.0.0.pom
      -> both builds are bit-for-bit identical

The two builds share no output directory, yet every artifact - jar, POM, sources,
javadoc - hashes identically. This is not luck: Jenesis content-hashes each step
and writes deterministic outputs (jar entries carry a fixed timestamp, javadoc is
generated with `-notimestamp`), so identical inputs always produce identical
artifacts. A consumer can therefore verify that the bytes on Central were built
from the published sources.

Publishing for real
-------------------

Two more steps turn the staged bundle into a Central release, and Jenesis hands
both to tools that own them:

- **A local publish** is built in. `java build/jenesis/Project.java export` copies
  the staged tree into your local Maven repository (`~/.m2`, or
  `MAVEN_REPOSITORY_LOCAL`) with the `maven-metadata-local.xml` / `_remote.repositories`
  markers - a genuine publish, just to a local repository, so a project on the
  same machine can resolve it immediately.

- **The remote upload and GPG signing** are deliberately *not* Jenesis's job. The
  recommended tool is **[JReleaser](https://jreleaser.org/)**: point it at
  `target/stage/maven/output/` and it signs every artifact and uploads the bundle
  to Maven Central. This is exactly how Jenesis itself releases - see the
  repository's `jreleaser.yml`. Central requires a detached GPG signature (`.asc`)
  for each file, which JReleaser produces; Jenesis stops at the unsigned, validated
  bundle so credentials and signing keys never enter the build.

So the division of labour is: Jenesis guarantees *what* you publish is complete
and correct, and JReleaser handles *getting it there* safely.

This separation is deliberate, not just convenient. Most people building the
project never release it at all - releasing is usually a job for CI or another
hardened environment that holds the credentials and signing keys, run rarely and
under tight control, while everyone else just builds. And the way you release
changes independently of the way you publish a build: you might switch release
tools, registries, or signing setups without touching the build, or change how the
build produces artifacts without touching the release pipeline. Keeping the
validated bundle (Jenesis) separate from the upload (the releaser) lets each side
evolve on its own.
