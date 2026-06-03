Executable Java (POM-based) demo
================================

A minimal **Maven-layout** project that is also **runnable**: a `pom.xml` plus a
single Java source with a `main` method and one real Maven dependency
(`commons-lang3`), packaged into a native application image by the JDK's
`jpackage`. It is the executable counterpart of `../demo-01-java-pom`, and its modular
sibling is `../demo-06-java-modular-executable`.

Declaring the entry point
--------------------------

A module becomes runnable when the build knows its main class. In the modular
layout that comes from a `@jenesis.main` Javadoc tag on `module-info.java` (see
`../demo-06-java-modular-executable`). A POM project has no `module-info.java`, so the
Maven-layout equivalent is a `<mainClass>` property in the POM:

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

Packaging is opt-in through a single system property, `-Djenesis.java.package`.
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

Run it
------

From this directory, pass the arguments you want the packaged application to
receive on its command line:

    java build/Demo.java ada lovelace

`Demo.java` configures packaging directly on the assembler -
`new JavaMultiProjectAssembler().packaging("app-image")`, the in-code equivalent of
`-Djenesis.java.package=app-image` with no system property - builds the `stage` goal,
then reads the image folder from the `stage/packages` entry of the map that
`build("stage")` returns (a fixed build target) and launches the produced platform
launcher with your arguments. The packaged app prints:

    Hello, Ada lovelace, from a packaged Maven project built by Jenesis!

(`commons-lang3`'s `StringUtils.capitalize` upper-cased the leading `a`, proving the
bundled dependency is on the launched app's classpath.) With no arguments it greets
`World`. Building the plain `java build/jenesis/Project.java` (no `package` property)
compiles and jars the project exactly as `../demo-01-java-pom` does, without producing an image.

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
