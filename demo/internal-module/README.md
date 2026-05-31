InternalModule demo
====================

`InternalModule` loads a *build module* (a `BuildExecutorModule` service
provider) straight from local source, compiles it, and runs it as part of the
host build graph. This is how a project consumes an in-repo build extension
without publishing it first.

Layout
------

    demo/internal-module
    |-- build/jenesis            symlink to ../../../sources/build/jenesis
    |-- build/Demo.java          the launcher (programmatic BuildExecutor)
    |-- plugin/
    |   |-- module-info.java     module demo.plugin { requires build.jenesis;
    |   |                                provides build.jenesis.BuildExecutorModule
    |   |                                with demo.plugin.GreetingModule; }
    |   `-- demo/plugin/GreetingModule.java

The plugin is an ordinary modular project. Its `module-info.java` declares a
`provides build.jenesis.BuildExecutorModule`, which is what `InternalModule`
discovers and runs.

Run it
------

From this directory:

    java build/Demo.java

You should see the build graph run and then:

    InternalModule ran the plugin. It produced:
      Hello from an InternalModule plugin, compiled from local sources!

What the launcher does
----------------------

The plugin's `module-info` says `requires build.jenesis`, so to compile and run
it `InternalModule` needs a `build.jenesis` module jar. `Demo.java`:

1. Compiles the Jenesis sources (reached through the `build/jenesis` symlink)
   into a `build.jenesis` module jar with the JDK's `javac`/`jar` tools.
2. Exposes that jar through a one-line `Repository` that resolves the
   `build.jenesis` module name to it. (A real project would resolve the
   published module from a repository instead.)
3. Constructs the module:

        new InternalModule(
                "module",                                 // resolution prefix
                "tool",                                   // qualifier -> independent trail
                Path.of("plugin"),                        // plugin source directory
                Map.of("module", jenesisRepository),
                Map.of("module", new ModularJarResolver(true)))

The second constructor argument is the **qualifier** (nullable, but an explicit
choice). Passing `"tool"` puts the plugin's own dependency closure on an
independent resolution trail (`module@tool/...`), keeping it separate from the
host project's dependencies when pinning - exactly like the Kotlin/Scala
compilers are qualified `kotlin` / `scala`. Pass `null` for the unqualified
trail.

The plugin's `greeting` step is reachable in the result map as
`plugin/greeting` (the delegated module's steps surface directly under the
module name).
