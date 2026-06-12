Executable Java (modular) demo
==============================

Take a modular Java app and ship it as a self-contained native application image -
a launcher with its own bundled Java runtime that a user can run without installing
a JDK, and one that stays small because the runtime is trimmed to the module graph.
The project is just a `module-info.java` plus a single Java source with a `main`
method and one real module dependency (`org.slf4j`), and Jenesis packages it with
the JDK's `jpackage`. It is the executable counterpart of `../demo-02-java-modular`,
and its POM-based sibling is `../demo-05-java-pom-executable`.

Run it
------

From this directory, pass the arguments you want the packaged application to
receive on its command line:

    java build/Demo.java Ada Lovelace

`Demo.java` configures packaging directly on the assembler -
`new InferredMultiProjectAssembler().packaging("app-image")`, the in-code equivalent of
`-Djenesis.java.jpackage=app-image` with no system property - builds the `stage` goal,
then reads the image folder from the `stage/packages` entry of the map that
`build("stage")` returns (a fixed build target) and launches the produced platform
launcher with your arguments. The packaged app prints:

    Hello, Ada Lovelace, from a packaged Java module built by Jenesis!

(Because no SLF4J backend is bundled, SLF4J prints a one-time "no providers" notice
and uses a no-op logger - the `slf4j-api` jar is still bundled and on the app's
classpath.) With no arguments it greets `world`. Building the plain
`java build/jenesis/Project.java` (no `package` property) compiles and jars the
module exactly as `../demo-02-java-modular` does, without producing an image.

Declaring the entry point with `@jenesis.main`
----------------------------------------------

For the build to package a runnable image it first has to know the main class. In
the modular layout that is declared with a `@jenesis.main` Javadoc tag on
`module-info.java`:

    /**
     * @jenesis.release 25
     * @jenesis.main sample.Sample
     */
    module demo.modular.executable {
        exports sample;
    }

The build parses that tag and records `main=sample.Sample` in the module's
`module.properties`. That single field is what both the `Execute` launcher and
the `package` step key off to treat the module as runnable. (A POM project has
no `module-info.java`; its equivalent is a `<mainClass>` POM property, shown in
`../demo-05-java-pom-executable`. Both layouts converge on the same `module.properties`
`main` field.)

Layout
------

    demo/demo-06-java-modular-executable
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java       stages an app-image with jpackage, then runs it
    |-- build/DemoNative.java stages a fully bundled native installer (deb/exe/dmg) and reports it
    `-- sources/
        |-- module-info.java     module demo.modular.executable (@jenesis.main, requires org.slf4j, pinned)
        `-- sample/Sample.java   main(String[] args); uses org.slf4j via `import module org.slf4j;`

With a `module-info.java` and no `pom.xml`, Jenesis auto-detects the
MODULAR_TO_MAVEN layout, exactly as `../demo-02-java-modular` does. Because `org.slf4j`
is a real module on the module path, `Sample.java` pulls it in with a single
module import - `import module org.slf4j;` - the same style the project's own
test sources use (`import module org.junit.jupiter.api;`). The produced image
bundles `slf4j-api.jar` next to the application jar: jpackage packages the whole
runtime closure, not just your own code.

How packaging fits the build
----------------------------

Packaging is opt-in through a single system property, `-Djenesis.java.jpackage`.
When it is set, `InferredMultiProjectAssembler` wires a per-module `package` step that
runs `jpackage` for every module declaring a main class (modules without one are
skipped). The property's value is the `jpackage --type`; a bare flag defaults to
`app-image`, a self-contained launcher plus bundled runtime that needs no
platform-native tooling. `--name` / `--main-jar` / `--main-class` are derived
automatically (the name from the module's coordinate, here `demo.modular.executable`).

Each produced image is then collected by the `STAGE` module's `packages` step
into `stage/packages/`, the staging analogue of `stage/maven` and `stage/modular`:

    target/stage/
    |-- modular/output/...                          the published modular jar
    `-- packages/output/demo.modular.executable/
        |-- bin/demo.modular.executable             the launcher
        `-- lib/                                     app jars + bundled runtime

Stage a `.jmod` and a `jlink` runtime image
-------------------------------------------

`jpackage` produces that app-image by running `jlink` internally over the module
graph. You can also produce the lower-level artifacts on their own - the `.jmod`
and the linked runtime image - with two boolean properties. Both are modular-only:
a `.jmod` and a custom runtime are built from *modules*, so a classpath project
(`../demo-05-java-pom-executable`) has nothing to pack or link.

    java -Djenesis.java.jmod=true -Djenesis.java.jlink=true build/jenesis/Project.java stage

`-Djenesis.java.jmod=true` wires a `jmod` step that packs the module into a `.jmod`,
the modular-package format that - unlike a jar - can also carry native libraries,
legal files, and `bin/`/`conf/` content. It is staged beside the modular jar in the
module-repository layout:

    target/stage/modular/output/demo.modular.executable/1-SNAPSHOT/
    |-- demo.modular.executable.jar
    `-- demo.modular.executable.jmod

`-Djenesis.java.jlink=true` wires a `jlink` step that links a **custom runtime
image** holding only the modules this app needs, staged under `stage/runtime` (the
analogue of `stage/packages`):

    target/stage/runtime/output/
    |-- bin/java          the runtime's own launcher
    `-- lib/ conf/ ...    a standard, trimmed JDK runtime layout

The image is trimmed to exactly `demo.modular.executable`, `org.slf4j`, and
`java.base` (about 61 MB here, against ~300 MB for a full JDK), and it runs straight
from its own `bin/java` with no JDK installed:

    target/stage/runtime/output/bin/java -m demo.modular.executable/sample.Sample Ada Lovelace
    # Hello, Ada Lovelace, from a packaged Java module built by Jenesis!

The difference from the app-image is only the wrapper: `jpackage` adds a native
launcher (`bin/demo.modular.executable`) and an installer-friendly layout around the
same kind of trimmed runtime, while `jlink` leaves you the bare runtime image to
launch with `java -m`. `../demo-25-custom-jmod` chains all three steps -
`jmod -> jlink -> jpackage` - and shows extra content packed into the `.jmod` riding
through the linked runtime into the final app.

Bundle the jars for a JRE-based image
-------------------------------------

`jpackage` bundles a trimmed runtime into the image. The lighter alternative is to
ship only your jars onto an off-the-shelf JRE base (the shared-base trade discussed
at the end of this page). For that, `-Djenesis.java.bundle=true` wires a per-module
`bundle` step that writes a single `bundle/bundle.zip` for every module with a main
class:

    java -Djenesis.java.bundle=true build/jenesis/Project.java

    bundle.zip
    |-- application.properties     mainClass=sample.Sample, mainModule=demo.modular.executable
    |-- modulepath/                jars that are modules (here the app jar and slf4j-api)
    `-- classpath/                 any non-modular jars

The zip carries exactly the runtime closure `Execute` would launch, split the same
way: real and automatic modules under `modulepath/`, the rest under `classpath/`,
with `application.properties` naming the entry point (`mainModule` only when the
launcher is modular). Unzipped onto a JRE base, it needs no JDK and no jpackage:

    FROM eclipse-temurin:25-jre
    COPY bundle/ /opt/app/
    ENTRYPOINT ["java", "--module-path", "/opt/app/modulepath", "-m", "demo.modular.executable/sample.Sample"]

For a non-modular project the zip holds only `classpath/` and an `application.properties`
with just `mainClass`, launched with `java -cp 'classpath/*' sample.Sample` (see
`../demo-05-java-pom-executable`).

A single executable jar with the launcher
-----------------------------------------

The `bundle.zip` still needs a launch command. `-Djenesis.java.launcher=true` goes one
step further and produces a **single executable jar** you run with `java -jar foo.jar` -
without flattening the dependencies into a fat jar, so modularity survives. The target
resolves the published `build.jenesis:build.jenesis.launcher` from Maven Central and
shades it into the jar:

    java -Djenesis.java.launcher=true build/jenesis/Project.java

    demo.modular.executable.jar
    |-- META-INF/MANIFEST.MF                  Main-Class: build.jenesis.launcher.Launcher
    |-- build/jenesis/launcher/*.class        the launcher (the jar's own unnamed module at run time)
    |-- application.properties                mainClass, mainModule, classpath order
    |-- modulepath/<dep>.jar/...              each modular/automatic dependency, exploded
    `-- classpath/<dep>.jar/...               each plain dependency, exploded

The launcher's `Main-Class` reads `application.properties`, resolves the `modulepath/`
subfolders into a fresh `ModuleLayer` and the `classpath/` ones into the unnamed module
of the same loader, and invokes the entry point - reconstructing what
`java -p modulepath -cp classpath -m demo.modular.executable/sample.Sample` would do,
all in process. Because each dependency keeps its own subfolder nothing is merged, so
`module-info`s and `META-INF/services` do not collide. `build/DemoLauncher.java` builds
this and runs the produced jar for you:

    java build/DemoLauncher.java Ada Lovelace

Because the launcher is shaded into the artifact you ship, it is pinned like any
other dependency - this module's `module-info.java` carries a
`@jenesis.pin launcher/maven/build.jenesis/build.jenesis.launcher <version> SHA-256/...`
tag (in its own `launcher` group), so the exact launcher bytes are verified and the
build is reproducible, and `pin` refreshes it the same way it pins everything else.

Unlike `jpackage` and `bundle`, this carries no JVM and no `jlink` runtime - it is a
plain jar that runs on any JDK 25 - and unlike the `bundle.zip` it needs no launch
script. (A bundle with no `mainClass` is instead a self-contained Java agent; see the
launcher's own documentation.)

Fully bundled native installer
------------------------------

`Demo.java` builds an `app-image` - a directory you launch in place. Its sibling
`build/DemoNative.java` instead builds the platform's **native installer**: the single
artifact you hand to a user to install. It follows the same shape as `Demo.java` - the
packaging type set explicitly on the assembler, the fixed `stage` target built, the
result read from the fixed `stage/packages` key - and only the last step differs: a
native installer is a deliverable to install, not a program to launch in place, so it
reports the produced package rather than running it.

    java build/DemoNative.java

The packaging type is chosen for the host - `deb` on Linux, `exe` on Windows, `dmg` on
macOS. On Linux it prints:

    Built a fully bundled deb installer under target/stage/packages/output:
      demo.modular.executable_0_amd64.deb (13 MiB)
    Unlike the app-image, this is a deliverable to install with the platform's package manager, not a directory to launch in place.

This package is much smaller than the classpath sibling `../demo-05-java-pom-executable`
produces (tens of megabytes): because this is a modular application, jpackage's internal
`jlink` trims the bundled runtime down to the module graph (`demo.modular.executable`,
`org.slf4j`, `java.base`), rather than bundling a full runtime.

Producing a native installer needs the platform's packaging tooling on the PATH (Linux:
`dpkg-deb`/`fakeroot` for `deb`, `rpmbuild` for `rpm`; Windows: the WiX Toolset; macOS:
the bundled `productbuild`/`hdiutil`). For that reason it is run locally rather than in
CI, where `Demo.java`'s app-image - which needs no native tooling - covers the packaging
path.

Where to go from here?
----------------------

The `app-image` is self-contained - it bundles its own (`jlink`-trimmed) Java
runtime - so a deployable container needs no JDK, only a minimal base with a C
library. Stage a **Linux** app-image (run `java build/Demo.java` on Linux or in
CI), then copy it into an image with a small `Dockerfile` (Podman reads the same
file):

    FROM debian:stable-slim
    COPY target/stage/packages/output/demo.modular.executable /opt/app
    ENTRYPOINT ["/opt/app/bin/demo.modular.executable"]

From this directory, build and run it with Docker:

    docker build -t demo-modular-executable .
    docker run --rm demo-modular-executable Ada Lovelace

or, identically, with Podman:

    podman build -t demo-modular-executable .
    podman run --rm demo-modular-executable Ada Lovelace

Either prints the same greeting the local launcher does, and because the bundled
runtime is trimmed to the module graph (`demo.modular.executable`, `org.slf4j`,
`java.base`) the resulting image stays small.

This is where a modular project pays off for deployment. jpackage runs `jlink`
over the resolved module graph, so it ships only the part of the standard library
those modules actually need. Measured with Temurin 25.0.3, this app-image is about
57 MB (its trimmed runtime about 56 MB), against about 138 MB for the classpath
sibling `../demo-05-java-pom-executable`, which has to bundle a full runtime - less
than half the size, and the gap is almost entirely the JVM. For reference, the
full Temurin 25.0.3 JDK is about 303 MB and `java.base` alone links to about
60 MB, so a modular runtime sits near that floor. So a modular project tends to
produce a markedly smaller deployment image than one that ships plain jars against
an off-the-shelf JVM.

One caveat, since these app-images bundle the JVM inside the application layer:
that "smaller" is per artifact. Two *different* services packaged this way share
only the OS base layer - each carries its own runtime - so at scale you duplicate
the JVM across services. The alternative is a common `eclipse-temurin:<version>-jre`
base with only your jars layered on top: image layers are content-addressed, so
that one JVM layer is stored and pulled once and shared by every image built on
it, and at run time containers sharing it also share its read-only pages in the
host page cache (per-process heap and metaspace stay private either way). None of this is
Docker-specific: it is OCI-image and Linux-kernel behaviour, so Podman shares base
layers and their page cache the same way - rootless Podman on `fuse-overlayfs`
still deduplicates the layer on disk, with a thin FUSE indirection on top. The
trade is a larger but shared runtime and coupling to that base's JVM version. The
self-contained image avoids that coupling in the strongest way: jpackage links the
bundled runtime with `jlink` from the very JDK that compiled the code and ran the
tests, so the application is shipped on **exactly the same JVM** it was built and
verified against - not whatever patch version or distribution a shared base happens
to provide. So a trimmed self-contained image is smallest, simplest, and
runtime-faithful as a single deliverable,
while a shared base is often leaner in aggregate when you run many distinct
services; replicas of one service share the JVM regardless.
