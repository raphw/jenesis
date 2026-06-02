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

`Demo.java` hands `Project` a `ConfigJmodAssembler` that wraps the stock
`JavaMultiProjectAssembler`. The stock assembler compiles and jars the module
unchanged; the wrapper then adds three steps as siblings:

    sub.addStep("config", new GenerateConfig());          // produces config/app.properties
    sub.addStep("jmod", JMod.tool(), "java", "config");    // classes/ + config/  -> <module>.jmod
    sub.addStep("jlink", JLink.tool(), "prepare", "jmod"); // <module>.jmod       -> runtime/

The only framework knowledge the wrapper relies on is a folder convention: the
`JMod` step routes a predecessor's `config/` directory to `jmod --config` (and
likewise `libs/` to `--libs`, `cmds/` to `--cmds`), exactly as it routes
`classes/` to `--class-path`. So the custom `config` step just has to *produce* a
`config/` directory and depend the `jmod` step on it; everything else is the stock
`JMod` and `JLink` steps. `jlink` reads the module from the `.jmod` (not the jar)
and gets its `--add-modules` root from the `prepare` step's `jlink.properties`.

Run it
------

From this directory:

    java build/Demo.java

It builds the module, packs `classes/` plus `config/` into `demo.config.jmod`,
links that jmod into a runtime image, and prints the config as `jlink` placed it
into the runtime:

    jlink placed the jmod's config into the runtime at .../runtime/conf/app.properties:
    greeting=Configured in a .jmod, linked into the runtime by jlink

Built from the jar instead, that file would be stranded inside the jar rather
than sitting in the runtime's `conf/`. A real module would read it at run time
from `<java.home>/conf/app.properties`.
