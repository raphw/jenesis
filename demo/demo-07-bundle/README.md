Bundle demo
===========

Ship a modular Java app as a single `bundle.zip`: its runtime launch closure (the
app jar plus its dependencies) split into a `modulepath/` (and, for a non-modular
app, a `classpath/`), with an `application.properties` naming the entry point. It is
the lightweight counterpart of the jpackage app-image in
`../demo-06-java-modular-executable`: where jpackage bundles a `jlink`-trimmed Java
runtime into a self-contained launcher, the bundle carries *only your jars*, meant to
be dropped onto a stock JRE base image (`eclipse-temurin:25-jre`) that supplies the
JVM. No native tooling, no bundled runtime, no launch script generated.

Run it
------

From this directory, pass the arguments you want the app to receive:

    java build/Demo.java Ada Lovelace

`Demo.java` runs the build with the stock
`new Project().assembler(new InferredMultiProjectAssembler())` - the bundle target is
selected by the committed `packaging.properties` at this demo's root, which sets
`bundle=true` - then unpacks the produced `bundle.zip` and launches the app out of it
on this JDK's own `java`, exactly as a JRE-based deployment would. It prints:

    Hello, Ada Lovelace, from a Jenesis bundle.zip on a stock JRE!

(Because no SLF4J backend is bundled, SLF4J prints a one-time "no providers" notice
and uses a no-op logger; `slf4j-api` is still part of the closure on the module
path.) With no arguments it greets `world`.

What the bundle contains
------------------------

The `bundle` step writes one `bundle.zip` per module that declares a main class:

    bundle.zip
    |-- application.properties     mainClass=sample.Sample, mainModule=demo.bundle, selfContainedModuleGraph=true
    |-- modulepath/                jars that are modules (here the app jar and slf4j-api)
    `-- classpath/                 any non-modular (plain) jars

It carries exactly the runtime closure the `Execute` launcher would run, split the
same way: real and automatic modules under `modulepath/`, plain jars under
`classpath/`. `application.properties` is plain `key=value` lines describing the
launch:

- `mainClass` - the entry point, always present;
- `mainModule` - present only when the launcher is modular;
- `selfContainedModuleGraph` - present whenever `modulepath/` is non-empty. `true`
  means the module path resolves entirely from the main module's `requires`, so a
  consumer launches it as-is; `false` (an automatic module or a `classpath/` jar is
  present) means the consumer must add `--add-modules ALL-MODULE-PATH` to root the
  whole module path. This demo's closure is `demo.bundle` + `org.slf4j`, both
  explicit modules, so the flag is `true`.

`Demo.java` reads those three fields and reconstructs the launch command -
`java --module-path modulepath -m demo.bundle/sample.Sample` here - the same command
the `Dockerfile` below bakes in.

How it is wired
---------------

Bundling is opt-in through a `bundle` key in a `packaging.properties` file, read from
the configuration location (a module's `META-INF/build.jenesis/` folder - or
`build.jenesis/` in a Maven layout - falling back to the project-wide configuration
directory, `build.jenesis/` under the project root by default; the first match wins, so a module-local file
overrides a project-wide one). When `bundle=true`, `InferredMultiProjectAssembler`
wires a `bundle` step into the package phase - the cross-module level that runs after
every module's build - producing a `bundle.zip` for every module declaring a main
class (a `@jenesis.main` Javadoc tag, or a `<mainClass>` POM property); modules
without one are skipped. Like the `launcher` jar, the archive is a per-module
artifact left in the build tree (`.../package/bundle/output/bundle/bundle.zip`)
rather than collected into `stage/`, so `Demo.java` locates it by walking `target/`.

Layout
------

    demo/demo-07-bundle
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java       builds the bundle, unpacks it, and runs the app from it
    `-- sources/
        |-- module-info.java     module demo.bundle (@jenesis.main, requires org.slf4j, pinned)
        `-- sample/Sample.java   main(String[] args); uses org.slf4j via `import module org.slf4j;`

With a `module-info.java` and no `pom.xml`, Jenesis auto-detects the
MODULAR_TO_MAVEN layout, exactly as `../demo-02-java-modular` does, and resolves the
declared `org.slf4j` module through the Jenesis module repository. Plain
`java build/jenesis/Project.java` (no bundle target) compiles and jars the module
without producing a bundle.

Deploying onto a JRE base
-------------------------

The bundle's whole point is a JRE-based image: layer your jars over an off-the-shelf
runtime instead of carrying your own. Unzipped onto a `-jre` base it needs no JDK and
no jpackage:

    FROM eclipse-temurin:25-jre
    COPY bundle/ /opt/app/
    ENTRYPOINT ["java", "--module-path", "/opt/app/modulepath", "-m", "demo.bundle/sample.Sample"]

The trade against the self-contained app-image (`../demo-06-java-modular-executable`)
is the classic shared-base one: an app-image bundles its own `jlink`-trimmed runtime
(smallest and runtime-faithful per artifact, but each service duplicates the JVM),
while a JRE-base bundle is tiny (only your jars) and shares one content-addressed JVM
layer across every image built on it - leaner in aggregate when you run many distinct
services, at the cost of coupling to that base's JVM version. A single executable jar
(selected with `launcher=true` in `packaging.properties`, see
`../demo-06-java-modular-executable`) is a third point: the same split closure plus a
shaded launcher, so it runs with a bare `java -jar` and no launch command at all.
