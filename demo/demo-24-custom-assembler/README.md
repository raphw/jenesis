Custom assembler demo
=====================

Run a preprocessing pass over your Java sources before they are compiled, so the
code that lands in the jar is the transformed version rather than what you wrote
on disk. Here the transformation is a simple textual substitution: the
`${greeting}` placeholder in `Sample.java` is rewritten to a real message before
the regular compile, jar, and test flow runs, so the value compiled into the jar
is the substituted one. Built with the stock build, the placeholder would survive
verbatim.

Run it
------

From this directory:

    java build/Demo.java

You should see the build graph resolve and run, with a line reporting the
substitution as the `preprocess` step rewrites the source:

    custom-assembler: substituted ${greeting} in sample/Sample.java

To confirm the substituted value is what ended up compiled into the jar, run the
produced modular jar yourself:

    java --module-path \
        target/build/modules/compose/module/module-sources/produce/assemble/binary/artifacts/jar/output/artifacts/classes.jar \
        --module demo.custom

which prints:

    Hello from a source preprocessed by a custom assembler!

Built with the stock assembler instead, the same command would print the
unsubstituted `${greeting}` placeholder.

Layout
------

    demo/demo-24-custom-assembler
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java    the launcher: wires the wrapping assembler, builds, runs
    `-- sources/
        |-- module-info.java     module demo.custom { exports sample; } (@jenesis.main)
        `-- sample/Sample.java    defines GREETING = "${greeting}", prints its substituted value

With a `module-info.java` and no `pom.xml`, Jenesis auto-detects the
MODULAR_TO_MAVEN layout and emits a modular jar (plus a generated POM), exactly
as the `java-modular` demo does. The only difference is the assembler.

How the wrapping works
----------------------

The preprocessing is delivered by a custom assembler that wraps the stock one.
`Project.assembler(...)` accepts any
`MultiProjectAssembler<? super ProjectModuleDescriptor>`. The default is
`InferredMultiProjectAssembler`; this demo passes a `PreprocessingAssembler` that
holds the stock assembler as a delegate:

        new Project()
                .assembler(new PreprocessingAssembler(new InferredMultiProjectAssembler()))

For each module the wrapper does three things:

1. Adds a `preprocess` build step that reads the module's original `sources/`
   tree, copies it into its own output, and rewrites `${greeting}` in every
   `.java` file (other files are linked through unchanged).
2. Hands the stock assembler a `ProjectModuleDescriptor` whose `sources()` is
   redirected to the `preprocess` step instead of the original source folder.
   `ProjectModuleDescriptor` is immutable with a wither per property, so this is
   a one-liner: `descriptor.sources("preprocess")`. Every reference
   accessor (`dependencies`, `sources`, `resources`, `manifests`, `coordinates`,
   `artifacts`, `content`) returns a `SequencedSet<String>`, so a customizer can add folders
   as readily as replace them.
3. Runs the stock assembler's module unchanged. Because its `sources()` now
   points at the preprocessed tree, `javac`, the jar step, and (when present)
   the test step all consume the substituted sources, and the rest of the build
   - dependency resolution, staging, pinning - is untouched.

That redirection is the whole trick: the custom assembler never reimplements the
Java toolchain, it only interposes a source transformation in front of it. Any
preprocessing that produces a `sources/` tree (template expansion, code
generation, license-header stamping) fits the same shape.

`Demo.java` only runs the build; running the produced jar is left to you (see
above). Note that the companion `build/jenesis/Execute.java` launcher would
build with the *stock* `Project` configuration rather than this custom one, so
it would not apply the preprocessing.
