Custom modular build demo
=========================

The modular counterpart of `../demo-17-custom-maven`: a multi-module **modular** project
built **without going through `Project`** - no layout, no goals - yet **without
wiring every step by hand** either. The launcher `build/Demo.java` uses the
convenience `ModularProject.make(root, assembler)` overload, which discovers the
`module-info.java` modules under the root and supplies sane defaults for the
repositories, resolvers, and digest a normal modular build configures.

It sits between `../demo-04-java-modular-multi` (the same shape of project driven by the
full `Project` entry point) and `../demo-19-custom-build` (a build wired entirely by
hand): a "custom but not so custom" build that reuses the stock toolchain through
one convenience call.

Layout
------

    demo/demo-18-custom-modular
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java      the launcher: BuildExecutor + ModularProject.make(...)
    |-- greeter/             the library module
    |   |-- module-info.java     module demo.greeter { exports sample.greeter; }
    |   `-- sample/greeter/Greeter.java
    `-- app/                 the consumer module
        |-- module-info.java     module demo.app { requires demo.greeter; }
        `-- sample/app/App.java   uses Greeter

Run it
------

From this directory:

    java build/Demo.java

Jenesis discovers the two `module-info.java` modules, builds `greeter` first, and
resolves it from within the build when compiling `app` (which `requires
demo.greeter`). Each module produces a modular jar under `target/`.

How the convenience make is wired
---------------------------------

`Demo.java` creates a `BuildExecutor`, adds the result of
`ModularProject.make(root, assembler)` as a module, and executes it:

    BuildExecutor root = BuildExecutor.of(Path.of("target"));
    root.addModule("modules", ModularProject.make(Path.of("."),
            (descriptor, repositories, resolvers) -> new JavaMultiProjectAssembler().apply(
                    new ProjectModuleDescriptor(descriptor, true, false, false, null, PathPlacement.MODULE_PATH),
                    repositories,
                    resolvers)));
    root.execute(args);

The two-argument `make` is the convenience form: it discovers the modules under
the root and fills in the Jenesis module repository, a modular jar resolver, and
a digest, so the only thing left to provide is the assembler. The assembler here
is the stock `JavaMultiProjectAssembler`; each discovered module arrives as a
`ModularModuleDescriptor`, which is wrapped in a `ProjectModuleDescriptor` -
with `PathPlacement.MODULE_PATH`, since these are genuine modules - so the
standard compile/jar/test flow runs unchanged.

The convenience form builds pure modules (a modular jar, no generated POM). To
also emit a generated POM and stage to a Maven repository (the MODULAR_TO_MAVEN
behavior), or to use a custom repository or strict pinning, switch to the full
`make(root, prefix, filter, repositories, resolvers, pinning, modular,
digest, assembler)` overload that `Project` uses. To drop the templates
entirely and wire the build graph by hand, see `../demo-19-custom-build`.
