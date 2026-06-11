Custom build demo
=================

Run a build step that the templates do not model, like generating a Java source
during the build and compiling it alongside the hand-written ones. You wire the
whole build graph **by hand** in one readable `main` method: sources and steps are
added directly, with no `Project`, no layout, no assembler, and no `pom.xml` or
`module-info.java` - just the steps you choose to add. When a build needs something
off the beaten path (code generation, a bespoke packaging step, an unusual
dependency wiring), you step down to the `BuildExecutor` primitives and build
exactly the graph you want.

Run it
------

From this directory:

    java build/Demo.java

The build emits a classpath jar at
`target/jar/output/artifacts/classes.jar`. Run the result with:

    java -cp target/jar/output/artifacts/classes.jar sample.Sample

which prints:

    Hello from a generated source, compiled by a hand-wired BuildExecutor!

Re-running `java build/Demo.java` reuses the cache: `generate` has no inputs, so
once its output exists it is not rewritten, and `compile`/`jar` are skipped while
their predecessors' checksums are unchanged.

What justifies skipping the template
-------------------------------------

Every other demo goes through `Project` - a layout auto-detects the modules, a
multi-project assembler wires the conventional compile/jar/test flow, and the
build is configured by selecting among the choices the template offers. This demo
drops all of that because of one step. The build includes a `generate` step that
**synthesizes a Java source on the fly** and compiles it alongside the hand-written
one. The templated `Project` flow assumes every source already exists on disk and
follows a fixed shape, so there is no natural place to hang code generation. A
hand-wired graph splices it in trivially: `generate` writes a `sources/` folder
like any source, and the compiler - which reads the `sources/` of every
predecessor - picks it up next to the real sources.

This is the point of the demo: when a build needs something the templates do not
model (code generation, a bespoke packaging step, an unusual dependency wiring),
you can always step down to the `BuildExecutor` primitives and build exactly the
graph you want.

Layout
------

    demo/demo-28-custom-build
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java     the hand-wired BuildExecutor
    `-- sources/
        `-- sample/Sample.java   prints Generated.MESSAGE (Generated is synthesized)

`Sample.java` references `sample.Generated`, which is **not on disk** - the
`generate` step produces it during the build.

The graph
---------

    sources ----\
                 +--> compile --> jar
    generate ---/

      sources    binds sources/ from disk
      generate   writes sources/sample/Generated.java into its own output
      compile    javac over the sources/ of both predecessors
      jar        packages the compiled classes into a classpath jar
