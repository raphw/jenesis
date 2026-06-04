Executable Java (POM-based) demo
================================

Take a Maven-layout Java app and ship it as a self-contained native application
image - a launcher with its own bundled Java runtime that a user can run without
installing a JDK. The project is just a `pom.xml` plus a single Java source with a
`main` method and one real Maven dependency (`commons-lang3`), and Jenesis packages
it with the JDK's `jpackage`. It is the executable counterpart of
`../demo-01-java-pom`, and its modular sibling is `../demo-06-java-modular-executable`.

Run it
------

From this directory, pass the arguments you want the packaged application to
receive on its command line:

    java build/Demo.java ada lovelace

`Demo.java` configures packaging directly on the assembler -
`new JavaMultiProjectAssembler().packaging("app-image")`, the in-code equivalent of
`-Djenesis.java.jpackage=app-image` with no system property - builds the `stage` goal,
then reads the image folder from the `stage/packages` entry of the map that
`build("stage")` returns (a fixed build target) and launches the produced platform
launcher with your arguments. The packaged app prints:

    Hello, Ada lovelace, from a packaged Maven project built by Jenesis!

(`commons-lang3`'s `StringUtils.capitalize` upper-cased the leading `a`, proving the
bundled dependency is on the launched app's classpath.) With no arguments it greets
`World`. Building the plain `java build/jenesis/Project.java` (no `package` property)
compiles and jars the project exactly as `../demo-01-java-pom` does, without producing an image.

Declaring the entry point
--------------------------

For the build to package a runnable image it first has to know the main class. In
the modular layout that comes from a `@jenesis.main` Javadoc tag on
`module-info.java` (see `../demo-06-java-modular-executable`). A POM project has no
`module-info.java`, so the Maven-layout equivalent is a `<mainClass>` property in
the POM:

    <properties>
        <mainClass>sample.Sample</mainClass>
    </properties>

Either way the build records `main=sample.Sample` in the module's
`module.properties`. That single field is what both the `Execute` launcher and
the `package` step key off to treat the module as runnable.

Layout
------

    demo/demo-05-java-pom-executable
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java       stages an app-image with jpackage, then runs it
    |-- build/DemoNative.java stages a fully bundled native installer (deb/exe/dmg) and reports it
    |-- pom.xml              Maven coordinates, <sourceDirectory>, <mainClass>, one <dependency> (pinned)
    `-- sources/sample/Sample.java   main(String[] args); uses commons-lang3 StringUtils

`Sample.java` calls `org.apache.commons.lang3.StringUtils`, so the produced image
bundles `commons-lang3.jar` next to the application jar - jpackage packages the
whole runtime closure, not just your own code.

How packaging fits the build
----------------------------

Packaging is opt-in through a single system property, `-Djenesis.java.jpackage`.
When it is set, `JavaMultiProjectAssembler` wires a per-module `package` step that
runs `jpackage` for every module declaring a main class (modules without one are
skipped). The property's value is the `jpackage --type`; a bare flag defaults to
`app-image`, a self-contained launcher plus bundled runtime that needs no
platform-native tooling. `--name` / `--main-jar` / `--main-class` are derived
automatically (the name from the artifactId, here `java-pom-executable`).

Each produced image is then collected by the `STAGE` module's `packages` step
into `stage/packages/`, the staging analogue of `stage/maven` and `stage/modular`:

    target/stage/
    |-- maven/output/...                 the published jar + pom
    `-- packages/output/java-pom-executable/
        |-- bin/java-pom-executable      the launcher
        `-- lib/                          app jars + bundled runtime

Bundle the jars for a JRE-based image
-------------------------------------

`jpackage` bundles a whole runtime into the image. The lighter alternative is to ship
only your jars onto an off-the-shelf JRE base. For that, `-Djenesis.java.bundle=true`
wires a per-module `bundle` step that writes a single `bundle/bundle.zip` for every
module with a main class:

    java -Djenesis.java.bundle=true build/jenesis/Project.java

    bundle.zip
    |-- application.properties     mainClass=sample.Sample
    `-- classpath/                 the app jar and commons-lang3

Because this is a classpath (non-modular) project, every jar goes under `classpath/`
and `application.properties` carries just `mainClass`. Unzipped onto a JRE base, it
needs no JDK and no jpackage:

    FROM eclipse-temurin:25-jre
    COPY bundle/ /opt/app/
    ENTRYPOINT ["java", "-cp", "/opt/app/classpath/*", "sample.Sample"]

The modular sibling `../demo-06-java-modular-executable` splits its jars into
`modulepath/` and `classpath/` and adds a `mainModule` entry.

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
      java-pom-executable_1.0.0_amd64.deb (38 MiB)
    Unlike the app-image, this is a deliverable to install with the platform's package manager, not a directory to launch in place.

The installer carries the whole bundled runtime, which is why it is tens of megabytes.
Because this is a classpath (non-modular) application, jpackage bundles a full runtime;
the modular sibling `../demo-06-java-modular-executable` produces a much smaller package, since
there jpackage's internal `jlink` can trim the runtime to the module graph.

Producing a native installer needs the platform's packaging tooling on the PATH (Linux:
`dpkg-deb`/`fakeroot` for `deb`, `rpmbuild` for `rpm`; Windows: the WiX Toolset; macOS:
the bundled `productbuild`/`hdiutil`). For that reason it is run locally rather than in
CI, where `Demo.java`'s app-image - which needs no native tooling - covers the packaging
path.

Where to go from here?
----------------------

The `app-image` is self-contained - it bundles its own Java runtime - so a
deployable container needs no JDK, only a minimal base with a C library. Stage a
**Linux** app-image (run `java build/Demo.java` on Linux or in CI), then copy it
into an image with a small `Dockerfile` (Podman reads the same file):

    FROM debian:stable-slim
    COPY target/stage/packages/output/java-pom-executable /opt/app
    ENTRYPOINT ["/opt/app/bin/java-pom-executable"]

From this directory, build and run it with Docker:

    docker build -t java-pom-executable .
    docker run --rm java-pom-executable ada lovelace

or, identically, with Podman:

    podman build -t java-pom-executable .
    podman run --rm java-pom-executable ada lovelace

Either prints the same greeting the local launcher does.

Because this is a **classpath** application, the bundled runtime is a *full* Java
runtime: jpackage cannot prove which standard-library modules you do not use, so
it ships them all. Measured with Temurin 25.0.3 that runtime is about 136 MB and
the whole app-image about 138 MB, so the container carries a JVM the size of an
off-the-shelf JRE. The modular sibling `../demo-06-java-modular-executable` is far
smaller, because it bundles only the runtime its module graph resolves - see its
note for the numbers.

Whichever way you size it, this self-contained image carries one guarantee a
shared base image gives up: jpackage builds the bundled runtime from the very JDK
that compiled the code and ran the tests, so the app ships on **exactly the same
JVM** it was built and verified against - not whatever patch version or
distribution a base image happens to provide. The flip side - that a self-contained
image cannot share its JVM layer across *different* services, where a common
`eclipse-temurin:<version>-jre` base can (deduplicated on disk and in the page
cache) - applies here too; see `../demo-06-java-modular-executable` for that
trade-off in full.
