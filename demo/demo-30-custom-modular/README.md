Custom modular build demo
=========================

Build a multi-module Java module system project from your own launcher instead of
the standard entry point, while still reusing Jenesis's stock toolchain. You write a
small `build/Demo.java` that points Jenesis at the project root, and one convenience
call discovers the `module-info.java` modules, builds the library module first, and
resolves it when compiling the consumer module - a custom launcher without wiring
every step by hand. This is the modular counterpart of `../demo-29-custom-maven`.

Run it
------

From this directory:

    java build/Demo.java

Jenesis discovers the two `module-info.java` modules, builds `greeter` first, and
resolves it from within the build when compiling `app` (which `requires
demo.greeter`). Each module produces a modular jar under `target/`.

Layout
------

    demo/demo-30-custom-modular
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java      the launcher: BuildExecutor + ModularProject.make(...)
    |-- greeter/             the library module
    |   |-- module-info.java     module demo.greeter { exports sample.greeter; }
    |   `-- sample/greeter/Greeter.java
    `-- app/                 the consumer module
        |-- module-info.java     module demo.app { requires demo.greeter; }
        `-- sample/app/App.java   uses Greeter

Where this demo sits
--------------------

It sits between `../demo-04-java-modular-multi` (the same shape of project driven by the
full `Project` entry point) and `../demo-31-custom-build` (a build wired entirely by
hand): a "custom but not so custom" build that reuses the stock toolchain through
one convenience call. The launcher avoids going through `Project` - no layout, no
goals - yet without wiring every step by hand either, because
`ModularProject.make(root, assembler)` supplies sane defaults for the repositories,
resolvers, and digest a normal modular build configures.

How the convenience make is wired
---------------------------------

`Demo.java` creates a `BuildExecutor`, adds the result of
`ModularProject.make(root, assembler)` as a module, and executes it:

    BuildExecutor root = BuildExecutor.of(Path.of("target"));
    root.addModule("modules", ModularProject.make(Path.of("."),
            (descriptor, repositories, resolvers) -> new InferredMultiProjectAssembler().apply(
                    new ProjectModuleDescriptor(descriptor, Path.of("."), true, false, false, null, PathPlacement.MODULE_PATH),
                    repositories,
                    resolvers)));
    root.execute(args);

The two-argument `make` is the convenience form: it discovers the modules under
the root and fills in the Jenesis module repository, a modular jar resolver, and
a digest, so the only thing left to provide is the assembler. The assembler here
is the stock `InferredMultiProjectAssembler`; each discovered module arrives as a
`ModularModuleDescriptor`, which is wrapped in a `ProjectModuleDescriptor` -
with `PathPlacement.MODULE_PATH`, since these are genuine modules - so the
standard compile/jar/test flow runs unchanged.

The convenience form builds pure modules (a modular jar, no generated POM). To
also emit a generated POM and stage to a Maven repository (the MODULAR_TO_MAVEN
behavior), or to use a custom repository or strict pinning, switch to the full
`make(root, prefix, filter, repositories, resolvers, pinning, modular,
digest, assembler)` overload that `Project` uses. To drop the templates
entirely and wire the build graph by hand, see `../demo-31-custom-build`.
