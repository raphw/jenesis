Custom jmod + jlink demo
========================

A custom assembler that packages **extra, non-class content** into a module's
`.jmod` and links it into a **runtime image** with `jlink` - the case where the
jmod form is worth more than a jar.

A jar can only hold classes and resources; an embedded resource stays inside the
jar at runtime. A `.jmod` additionally has dedicated sections for native
libraries (`--libs`), commands (`--cmds`), and config (`--config`), and when
`jlink` links a `.jmod` it lays that content into the produced runtime's `lib/`,
`bin/`, and `conf/` directories. This demo uses the `config` section: the bundled
`app.properties` ends up in the runtime's `conf/`.

Layout
------

    demo/custom-jmod
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java      the launcher: wraps the assembler, builds, prints the result
    `-- sources/
        |-- module-info.java     module demo.config { exports sample; }
        `-- sample/Sample.java

How the wrapping works
----------------------

`Demo.java` hands `Project` a `ConfigJmodAssembler` that wraps a stock
`JavaMultiProjectAssembler` with `jmod` and `jlink` enabled:

    new ConfigJmodAssembler(new JavaMultiProjectAssembler().jmod(true).jlink(true))

With those flags the stock assembler already adds the `jmod` and `jlink` steps and
wires `jlink` to read the `.jmod`. The wrapper does not duplicate any of that. It
adds exactly one thing, the extra input, and lets the stock pipeline consume it:

    sub.addStep("config", new GenerateConfig());   // produces jmodconfig/app.properties
    inner.accept(sub, inherited);                   // the stock java -> jmod -> jlink pipeline

The link between the two is the module descriptor's `content` set. The wrapper
calls `descriptor.withContent("config")` before delegating, and the stock `jmod`
step depends on every step named in `content` in addition to `java`. The only
other framework knowledge involved is a folder convention: the `JMod` step routes
a predecessor's `jmodconfig/` directory to `jmod --config` (and likewise
`jmodlibs/` to `--libs`, `jmodcmds/` to `--cmds`), exactly as it routes `classes/`
to `--class-path`. So the custom `config` step just has to *produce* a
`jmodconfig/` directory; the stock `jmod` and `jlink` steps do the rest. `jlink`
reads the module from the `.jmod` (not the jar) and gets its `--add-modules` root
from the `prepare` step's `jlink.properties`.

Run it
------

From this directory:

    java build/Demo.java

It builds the module, packs `classes/` plus `jmodconfig/` into `demo.config.jmod`,
links that jmod into a runtime image, and prints the config as `jlink` placed it
into the runtime:

    jlink placed the jmod's config into the runtime at .../runtime/conf/app.properties:
    greeting=Configured in a .jmod, linked into the runtime by jlink

Built from the jar instead, that file would be stranded inside the jar rather
than sitting in the runtime's `conf/`. A real module would read it at run time
from `<java.home>/conf/app.properties`.
