Custom jmod + jlink + jpackage demo
===================================

A custom assembler that packages **extra, non-class content** into a module's
`.jmod`, links it into a **runtime image** with `jlink`, and wraps that runtime in
a self-contained **application image** with `jpackage` - the full chain by which a
jmod's content reaches a packaged, runnable app, and the case where the jmod form
is worth more than a jar.

A jar can only hold classes and resources; an embedded resource stays inside the
jar at runtime. A `.jmod` additionally has dedicated sections for native
libraries (`--libs`), commands (`--cmds`), and config (`--config`), and when
`jlink` links a `.jmod` it lays that content into the produced runtime's `lib/`,
`bin/`, and `conf/` directories. `jpackage` then bundles that runtime into the app
image, so the content rides all the way into the shipped application. This demo
uses the `config` section: the bundled `app.properties` ends up in the runtime's
`conf/`, and the packaged app reads it back from its own `<java.home>/conf/`.

The module also `requires org.slf4j`, to show the other side of the picture. Only
the module's *own* archive becomes a `.jmod`; its dependencies reach `jlink` as
their resolved jars. `jlink` happily links jar-form modules onto the same module
path as the `.jmod`, so the produced runtime ends up holding exactly three
modules - the content-bearing `.jmod`, the dependency jar, and `java.base`:

    $ .../demo.config/lib/runtime/bin/java --list-modules
    demo.config@1-SNAPSHOT
    java.base@25.0.3
    org.slf4j@2.0.16

A dependency only gains from being a `.jmod` if it carries native content of its
own; a plain library jar has none, so a jar is the right form for it. (Feeding a
*sibling* module's content-bearing `.jmod` to a downstream consumer is the
separate, opt-in `<coordinate>:jmod` resolution, which the stock assembler does
not request.)

Layout
------

    demo/demo-12-custom-jmod
    |-- build/jenesis        symlink to ../../../sources/build/jenesis
    |-- build/Demo.java      the launcher: wraps the assembler, builds, runs the packaged app
    `-- sources/
        |-- module-info.java     module demo.config { requires org.slf4j; exports sample; }
        `-- sample/Sample.java   reads <java.home>/conf/app.properties, logs via slf4j, prints it

How the wrapping works
----------------------

`Demo.java` hands `Project` a `ConfigJmodAssembler` that wraps a stock
`JavaMultiProjectAssembler` with `jmod`, `jlink`, and packaging enabled:

    new ConfigJmodAssembler(new JavaMultiProjectAssembler()
            .jmod(true).jlink(true).packaging("app-image"))

With those flags the stock assembler already adds the `jmod`, `jlink`, and
`jpackage` steps, wires `jlink` to read the `.jmod`, and wires `jpackage` to bundle
the `jlink` runtime via `--runtime-image`. The wrapper does not duplicate any of
that. It adds exactly one thing, the extra input, and lets the stock pipeline
consume it:

    sub.addStep("config", new GenerateConfig());   // produces jmodconfig/app.properties
    inner.accept(sub, inherited);                   // the stock java -> jmod -> jlink -> jpackage pipeline

The link between the two is the module descriptor's `content` set. The wrapper
calls `descriptor.withContent("config")` before delegating, and the stock `jmod`
step depends on every step named in `content` in addition to `java`. The only
other framework knowledge involved is a folder convention: the `JMod` step routes
a predecessor's `jmodconfig/` directory to `jmod --config` (and likewise
`jmodlibs/` to `--libs`, `jmodcmds/` to `--cmds`), exactly as it routes `classes/`
to `--class-path`. So the custom `config` step just has to *produce* a
`jmodconfig/` directory; the stock `jmod`, `jlink`, and `jpackage` steps do the
rest. `jlink` reads the module from the `.jmod` (not the jar) and gets its
`--add-modules` root from the `prepare` step's `jlink.properties`; `jpackage`,
seeing a `jlink` runtime among its inputs, wraps it with `--runtime-image` rather
than linking its own, so the jmod content carries through into the image.

Run it
------

From this directory:

    java build/Demo.java

It builds the module, packs `classes/` plus `jmodconfig/` into `demo.config.jmod`,
links that jmod into a runtime image, wraps the runtime into a `demo.config`
application image, and then launches the packaged app. The app reads its config
back from the runtime that `jpackage` bundled into it:

    The packaged app read its bundled config from .../demo.config/lib/runtime/conf/app.properties:
    Configured in a .jmod, linked into the runtime by jlink

Built from the jar instead, that file would be stranded inside the jar rather
than sitting in the runtime's `conf/` where the launched app can read it from
`<java.home>/conf/app.properties`.
