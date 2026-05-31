ExternalModule demo
====================

`ExternalModule` resolves a *build module* from a repository **coordinate**,
downloads its jar, loads the `BuildExecutorModule` service provider, and runs it
as part of the host build graph. It is the published-artifact counterpart to the
`InternalModule` demo (which compiles the plugin from local source instead).

Layout
------

    demo/external-module
    |-- build/jenesis            symlink to ../../../sources/build/jenesis
    |-- build/Demo.java          the launcher (programmatic BuildExecutor)
    |-- plugin-src/
    |   |-- module-info.java     module demo.external.plugin { requires build.jenesis;
    |   |                                provides build.jenesis.BuildExecutorModule
    |   |                                with demo.plugin.StampModule; }
    |   `-- demo/plugin/StampModule.java

Run it
------

From this directory:

    java build/Demo.java

You should see the build graph resolve and run the plugin, then:

    ExternalModule resolved and ran the plugin. It produced:
      Hello from an ExternalModule plugin, resolved from a repository coordinate!

What the launcher does
----------------------

To keep the demo self-contained, `Demo.java` first pre-builds the two module
jars it then serves "from a repository":

1. `build.jenesis` (compiled from the sources behind the `build/jenesis`
   symlink) - the plugin's declared dependency.
2. `demo.external.plugin` (compiled from `plugin-src/` against that jar) - the
   published build module.

It exposes both through a small `Repository` that maps a module name to its jar,
then constructs the module:

        new ExternalModule(
                "module/demo.external.plugin",            // the coordinate to resolve
                "tool",                                   // qualifier -> independent trail
                Map.of("module", repository),
                Map.of("module", new ModularJarResolver(true)))

`ExternalModule` writes the coordinate, runs a `DependenciesModule`
(resolve + download) over it - which also pulls the plugin's transitive
`requires build.jenesis` from the same repository - then loads and runs the
service provider. The second constructor argument is the **qualifier**
(nullable, but an explicit choice); `"tool"` places the plugin's dependency
closure on an independent resolution trail (`module@tool/...`).

In a real setup the repository would be a published Jenesis module repository
(or a Maven one via `MavenModuleResolver`), and the coordinate would name an
artifact you did not build yourself.
