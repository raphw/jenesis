Custom Maven build demo
=======================

Build a multi-module Maven project from your own launcher instead of the standard
entry point, while still reusing Jenesis's stock toolchain. You write a small
`build/Demo.java` that points Jenesis at the project root, and one convenience call
discovers the modules, builds the library first, and resolves it when compiling the
consumer - so you get a custom launcher without wiring every step by hand.

Run it
------

From this directory:

    java build/Demo.java

Jenesis discovers the aggregator and its two sub-modules, builds `greeter` first,
and resolves it from within the build when compiling `app`. Each module's jar is
produced under `target/`.

Layout
------

    demo/demo-27-custom-maven
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java      the launcher: BuildExecutor + MavenProject.make(...)
    |-- pom.xml              aggregator (packaging pom); lists the two modules
    |-- greeter/             the library module
    |   |-- pom.xml
    |   `-- sources/sample/greeter/Greeter.java
    `-- app/                 the consumer module
        |-- pom.xml          depends on greeter
        `-- sources/sample/app/App.java   uses Greeter

Where this demo sits
--------------------

It sits between `../demo-03-java-pom-multi` (the same shape of project driven by the full
`Project` entry point) and `../demo-29-custom-build` (a build wired entirely by hand): a
"custom but not so custom" build that reuses the stock toolchain through one
convenience call. The launcher avoids going through `Project` - no layout, no goals,
no `java build/jenesis/Project.java` - yet without wiring every step by hand either,
because `MavenProject.make(root, assembler)` supplies sane defaults for the
repositories, resolvers, and digest a normal build would configure.

How the convenience make is wired
---------------------------------

`Demo.java` creates a `BuildExecutor`, adds the result of
`MavenProject.make(root, assembler)` as a module, and executes it:

    BuildExecutor root = BuildExecutor.of(Path.of("target"));
    root.addModule("maven", MavenProject.make(Path.of("."),
            (descriptor, repositories, resolvers) -> new InferredMultiProjectAssembler().apply(
                    new ProjectModuleDescriptor(descriptor, Path.of("."), true, false, false, null, PathPlacement.CLASS_PATH),
                    repositories,
                    resolvers)));
    root.execute(args);

The two-argument `make` is the convenience form: it discovers the Maven modules
under the root and fills in a Maven Central repository, a Maven POM resolver, and
a digest, so the only thing left to provide is the assembler. The assembler here
is the stock `InferredMultiProjectAssembler`; each discovered module arrives as a
`MavenModuleDescriptor`, which is wrapped in a `ProjectModuleDescriptor` (tests
on, no sources or javadoc jar, lenient pinning, class-path compilation) so the
standard compile/jar/test flow runs unchanged.

To take full control - a custom repository, strict pinning, a different digest -
switch to the full `make(root, prefix, repositories, resolvers, pinning,
digest, assembler)` overload that `Project` itself uses. To drop the templates
entirely and wire the build graph by hand, see `../demo-29-custom-build`.
