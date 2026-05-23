package build.jenesis;

import module java.base;
import build.jenesis.docker.DockerizedJava;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenProject;
import build.jenesis.maven.MavenRepositoryExport;
import build.jenesis.maven.MavenRepositoryStaging;
import build.jenesis.maven.MavenUriParser;
import build.jenesis.maven.PinPom;
import build.jenesis.maven.Pom;
import build.jenesis.module.DownloadModuleUris;
import build.jenesis.module.JenesisModuleRepository;
import build.jenesis.module.JenesisModuleRepositoryExport;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularStaging;
import build.jenesis.module.ModularProject;
import build.jenesis.module.PinModuleInfo;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.project.ProjectModuleDescriptor;
import build.jenesis.step.Bind;

public record Project(
        Path root,
        Path target,
        Path cache,
        Layout layout,
        boolean tests,
        boolean sources,
        boolean documentation,
        boolean stageTests,
        boolean strictPinning,
        List<Path> metadata,
        String version,
        SequencedSet<String> defaultTarget,
        MultiProjectAssembler<? super ProjectModuleDescriptor> assembler,
        Map<String, Repository> repositories,
        Map<String, Resolver> resolvers) {

    public static final String BUILD = "build",
            STAGE = "stage",
            EXPORT = "export",
            PIN = "pin",
            METADATA = "metadata",
            HELP = "help",
            SKILL = "skill";

    @FunctionalInterface
    public interface Layout {

        Function<String, String> apply(BuildExecutor executor,
                                       Project project,
                                       MultiProjectAssembler<? super ProjectModuleDescriptor> assembler) throws IOException;

        Layout MAVEN = (executor, project, assembler) -> {
            executor.addModule(HELP, new HelpModule("maven", assembler.getClass().getName()));
            executor.addModule(SKILL, new SkillModule(project.target()));
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            MultiProjectAssembler<? super ProjectModuleDescriptor> pomAware = new PomAwareAssembler(assembler);
            executor.addModule(BUILD, (sub, inherited) -> {
                SequencedSet<String> mavenDeps = new LinkedHashSet<>();
                inherited.sequencedKeySet().stream()
                        .filter(key -> key.startsWith(BuildExecutorModule.PREVIOUS + METADATA + "/"))
                        .forEach(mavenDeps::add);
                sub.addModule("maven", MavenProject.make(project.root(),
                                "maven",
                                new MavenDefaultRepository()
                                        .cached(project.cache() == null ? null : Files.createDirectories(project.cache())),
                                new MavenPomResolver(),
                                project.strictPinning(),
                                (descriptor, repositories, resolvers) -> pomAware.apply(
                                        new ProjectModuleDescriptor(descriptor,
                                                project.tests(),
                                                project.sources(),
                                                project.documentation(),
                                                project.strictPinning()),
                                        repositories, resolvers)),
                        mavenDeps);
            }, METADATA);
            executor.addStep(STAGE, new MavenRepositoryStaging(project.stageTests()), BUILD);
            executor.addStep(EXPORT, new MavenRepositoryExport(), STAGE);
            String prefix = BUILD + "/maven/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            HashDigestFunction hashFunction = new HashDigestFunction(
                    System.getProperty("jenesis.project.pinAlgorithm", "SHA-256"));
            executor.addModule(PIN, new PinModule(project.root(), "pom.xml",
                    file -> new PinPom("maven", file, hashFunction)), BUILD);
            return name -> {
                int slash = name.indexOf('/');
                return slash == -1
                        ? prefix + "/module-" + BuildExecutorModule.encode(name)
                        : prefix + "/module-" + BuildExecutorModule.encode(name.substring(0, slash))
                                + "/" + name.substring(slash + 1);
            };
        };

        Layout MODULAR = (executor, project, assembler) -> {
            executor.addModule(HELP, new HelpModule("modular", assembler.getClass().getName()));
            executor.addModule(SKILL, new SkillModule(project.target()));
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            executor.addModule(BUILD, (sub, inherited) -> {
                Map<String, Repository> repositories = new LinkedHashMap<>();
                repositories.put("module",
                        new JenesisModuleRepository()
                                .cached(project.cache() == null ? null : Files.createDirectories(project.cache()))
                                .prepend(JenesisModuleRepository.ofLocal()));
                repositories.putAll(project.repositories());
                Map<String, Resolver> resolvers = new LinkedHashMap<>();
                resolvers.put("module", new ModularJarResolver(true));
                resolvers.putAll(project.resolvers());
                SequencedSet<String> modulesDeps = new LinkedHashSet<>();
                inherited.sequencedKeySet().stream()
                        .filter(key -> key.startsWith(BuildExecutorModule.PREVIOUS + METADATA + "/"))
                        .forEach(modulesDeps::add);
                sub.addModule("modules", ModularProject.make(project.root(),
                        Collections.unmodifiableMap(repositories),
                        Collections.unmodifiableMap(resolvers),
                        project.strictPinning(),
                        (descriptor, mergedRepos, mergedResolvers) -> assembler.apply(
                                new ProjectModuleDescriptor(descriptor,
                                        project.tests(),
                                        project.sources(),
                                        project.documentation(),
                                        project.strictPinning()),
                                mergedRepos,
                                mergedResolvers)),
                        modulesDeps);
            }, METADATA);
            executor.addStep(STAGE, new ModularStaging(project.stageTests()), BUILD);
            executor.addStep(EXPORT, new JenesisModuleRepositoryExport(), STAGE);
            String prefix = BUILD + "/modules/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            HashDigestFunction hashFunction = new HashDigestFunction(
                    System.getProperty("jenesis.project.pinAlgorithm", "SHA-256"));
            executor.addModule(PIN, new PinModule(project.root(), "module-info.java",
                    file -> new PinModuleInfo("module", file, hashFunction)), BUILD);
            return name -> {
                int slash = name.indexOf('/');
                return slash == -1
                        ? prefix + "/module-" + BuildExecutorModule.encode(name)
                        : prefix + "/module-" + BuildExecutorModule.encode(name.substring(0, slash))
                                + "/" + name.substring(slash + 1);
            };
        };

        Layout MODULAR_TO_MAVEN = (executor, project, assembler) -> {
            executor.addModule(HELP, new HelpModule("modular_to_maven", assembler.getClass().getName()));
            executor.addModule(SKILL, new SkillModule(project.target()));
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            MavenPomResolver resolver = new MavenPomResolver();
            MultiProjectAssembler<? super ProjectModuleDescriptor> pomAware = new PomAwareAssembler(assembler);
            executor.addStep("download", new DownloadModuleUris(null));
            executor.addModule(BUILD, (sub, inherited) -> {
                Function<String, String> parser = MavenUriParser.ofUris(new MavenUriParser(),
                        DownloadModuleUris.URIS,
                        inherited.values());
                Map<String, Repository> repositories = new LinkedHashMap<>();
                repositories.put("maven",
                        new MavenDefaultRepository()
                                .cached(project.cache() == null ? null : Files.createDirectories(project.cache())));
                repositories.putAll(project.repositories());
                Map<String, Resolver> resolvers = new LinkedHashMap<>();
                resolvers.put("module", new ModularJarResolver(false,
                        resolver.translated("maven",
                                (_, coordinate) -> parser.apply(coordinate))));
                resolvers.put("maven", resolver);
                resolvers.putAll(project.resolvers());
                SequencedSet<String> modulesDeps = new LinkedHashSet<>();
                inherited.sequencedKeySet().stream()
                        .filter(key -> key.startsWith(BuildExecutorModule.PREVIOUS + METADATA + "/"))
                        .forEach(modulesDeps::add);
                sub.addModule("modules", ModularProject.make(project.root(),
                                Collections.unmodifiableMap(repositories),
                                Collections.unmodifiableMap(resolvers),
                                project.strictPinning(),
                                false,
                                (descriptor, mergedRepos, mergedResolvers) -> pomAware.apply(
                                        new ProjectModuleDescriptor(descriptor,
                                                project.tests(),
                                                project.sources(),
                                                project.documentation(),
                                                project.strictPinning()),
                                        mergedRepos, mergedResolvers)),
                        modulesDeps);
            }, "download", METADATA);
            executor.addStep(STAGE, new MavenRepositoryStaging(project.stageTests()), BUILD);
            executor.addStep(EXPORT, new MavenRepositoryExport(), STAGE);
            String prefix = BUILD + "/modules/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            HashDigestFunction hashFunction = new HashDigestFunction(
                    System.getProperty("jenesis.project.pinAlgorithm", "SHA-256"));
            executor.addModule(PIN, new PinModule(project.root(), "module-info.java",
                    file -> new PinModuleInfo("module", file, true, hashFunction)), BUILD);
            return name -> {
                int slash = name.indexOf('/');
                return slash == -1
                        ? prefix + "/module-" + BuildExecutorModule.encode(name)
                        : prefix + "/module-" + BuildExecutorModule.encode(name.substring(0, slash))
                                + "/" + name.substring(slash + 1);
            };
        };

        Layout AUTO = (executor, project, assembler) -> of(project.root()).apply(executor, project, assembler);

        static Layout of(Path root) throws IOException {
            List<Path> moduleInfos = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root)
                            && Files.exists(directory.resolve(BuildExecutor.BUILD_MARKER))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    Path name = file.getFileName();
                    if (name != null && "module-info.java".equals(name.toString())) {
                        moduleInfos.add(file);
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Files.isRegularFile(root.resolve("pom.xml"))) {
                return MAVEN;
            }
            if (!moduleInfos.isEmpty()) {
                return MODULAR;
            }
            throw new IllegalStateException(
                    "No build descriptor found under " + root.toAbsolutePath()
                            + " (expected a module-info.java or a pom.xml)");
        }
    }

    private static final class MetadataModule implements BuildExecutorModule {

        private final SequencedMap<String, Path> files;
        private final String version;

        private MetadataModule(SequencedMap<String, Path> files, String version) {
            this.files = files;
            this.version = version;
        }

        static BuildExecutorModule toMetadataModule(Project project) {
            Path root = project.root().toAbsolutePath().normalize();
            SequencedMap<String, Path> files = new LinkedHashMap<>();
            for (Path file : project.metadata()) {
                Path absolute = (file.isAbsolute() ? file : project.root().resolve(file)).toAbsolutePath().normalize();
                Path relative = root.relativize(absolute);
                files.put(METADATA + "-" + BuildExecutorModule.encode(relative.toString()), relative);
            }
            return new MetadataModule(files, project.version());
        }

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
            files.forEach((name, file) -> buildExecutor.addSource("file-" + name, Bind.asMetadata(), file));
            if (version != null && !version.isEmpty()) {
                SequencedMap<String, String> values = new LinkedHashMap<>();
                values.put("version", version);
                buildExecutor.addStep("command", new MetadataValues(values));
            }
        }
    }

    private record MetadataValues(SequencedMap<String, String> values) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties properties = new SequencedProperties();
            values.forEach(properties::setProperty);
            properties.store(context.next().resolve(BuildStep.METADATA));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record HelpModule(String layout, String assembler) implements BuildExecutorModule {

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
            System.out.println(("""
                    %{title}Jenesis%{reset} - a Java build tool, written and configured in Java.

                    %{header}Active configuration:%{reset}
                      layout      %{name}%{layout}%{reset}
                      assembler   %{name}%{assembler}%{reset}

                    %{header}Usage:%{reset}
                      Pass selectors as command-line arguments to the build launcher
                      (the installed %{name}jenesis%{reset} CLI, a source-mode
                      %{name}Project.java%{reset} script, or a programmatic
                      %{name}Project.build(...)%{reset} call from Java code).

                    Without selectors, the default target (%{name}build%{reset}) is executed.

                    %{header}Selectors (available in every layout):%{reset}
                      %{name}build%{reset}       Resolve, compile, package, and test every module
                      %{name}stage%{reset}       Stage produced artifacts into a local repository
                      %{name}export%{reset}      Export the staged repository as the build deliverable
                      %{name}pin%{reset}         Rewrite version/checksum pins into pom.xml or module-info.java
                      %{name}metadata%{reset}    Refresh the metadata module outputs
                      %{name}help%{reset}        Print this message
                      %{name}skill%{reset}       Print an agent-oriented onboarding briefing (plain text)

                    %{header}Module-scoped selector:%{reset}
                      A selector starting with %{name}+%{reset} is shorthand for a single project module:
                      %{name}+<module>%{reset} resolves to the module's subgraph inside %{name}build%{reset} (it does
                      not run %{name}stage%{reset}, %{name}export%{reset}, or %{name}pin%{reset}; invoke those explicitly if needed).
                      %{name}+<module>/<step>%{reset} drills further into a specific step inside that
                      module, e.g. %{name}+myModule/compile/dependencies/resolved%{reset}.
                      <module> matches the source folder that holds the module's pom.xml
                      or module-info.java. Run %{name}build%{reset} once and look at the printed
                      module-* lines to discover available module names.

                    %{header}Wildcards in selectors:%{reset}
                      %{name}:%{reset}   matches a single path segment, e.g. %{name}build/:/java%{reset} matches
                          the %{name}java%{reset} step of every direct child of %{name}build%{reset}.
                      %{name}::%{reset}  matches any depth (zero or more segments), e.g. %{name}::/test%{reset}
                          matches every %{name}test%{reset} step anywhere in the tree.
                      Both wildcards are lenient: branches that fail to match are silently
                      skipped, so a typo in the tail of a %{name}::%{reset} selector produces no error.

                    %{header}System properties (-Djenesis.project.<key>=<value>):%{reset}
                      Honored only when the project goes through Project.resolveProperties()
                      (the default main(...) does). A custom Project.java that wires its own
                      values, or sets fields after resolveProperties(), may ignore them.
                      %{name}root%{reset}, %{name}target%{reset}, %{name}cache%{reset}              Override input/output locations
                      %{name}layout%{reset}                           auto, maven, modular, or modular_to_maven
                      %{name}skipTests%{reset}                        Skip executing tests
                      %{name}sources%{reset}, %{name}documentation%{reset}           Assemble source/javadoc jars
                      %{name}stageTests%{reset}                       Stage test artifacts alongside main artifacts
                      %{name}strictPinning%{reset}                    Fail the build for any unpinned artifact
                      %{name}metadata%{reset}                         Path-separated list of extra metadata files
                      %{name}version%{reset}                          Project version
                      %{name}pinAlgorithm%{reset}                     Algorithm for pin checksums (default: SHA-256)
                      %{name}docker%{reset}[, %{name}docker.image%{reset}]           Wrap the build in a container

                    %{header}Cache invalidation:%{reset}
                      Changes to the sources of the project being built are always
                      detected. When working on the build itself, in-code-only changes
                      to a custom build step are not detected because the incremental
                      cache keys each step by its serialized form; bump the step class's
                      %{name}serialVersionUID%{reset} to force re-execution of such steps, or pass
                      %{name}-Djenesis.executor.rebuild=true%{reset} for a full rebuild.

                    %{header}Custom Javadoc tags in module-info.java:%{reset}
                      %{name}@jenesis.release%{reset} <V>             Java release target
                      %{name}@jenesis.main%{reset} <class>            Main class for the module
                      %{name}@jenesis.test%{reset} [<module>]         Mark module as a test variant of <module>
                      %{name}@jenesis.pin%{reset} <mod> <ver> [<algo>/<hex>]
                                                       Pin a dependency version and checksum

                    See README.md for the full reference.
                    """)
                    .replace("%{layout}", layout)
                    .replace("%{assembler}", assembler)
                    .replace("%{reset}", BuildExecutorCallback.RESET)
                    .replace("%{header}", BuildExecutorCallback.YELLOW)
                    .replace("%{name}", BuildExecutorCallback.CYAN)
                    .replace("%{title}", BuildExecutorCallback.GREEN));
        }
    }

    private record SkillModule(Path target) implements BuildExecutorModule {

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
            System.out.println(("""
                    Jenesis - skill briefing for coding agents
                    ==========================================

                    Jenesis is a Java build tool whose project configuration is itself
                    Java code. A build is invoked by passing selectors as command-line
                    arguments to a launcher. Three entry shapes exist, all equivalent:

                      - the installed `jenesis` CLI (from the release zip / SDKMAN);
                      - a source-mode `Project.java` script in the project tree, run
                        with `java <script.java> [selectors...]`;
                      - a programmatic `Project.build(selectors...)` call from
                        Java code (used when embedding the build in another tool).

                    With no selectors the default target `build` runs. Whichever
                    launcher is used, it constructs a `BuildExecutor`, wires the
                    layout, and forwards the selectors.

                    Project layouts
                    ---------------
                    A layout decides how modules are discovered and what gets staged:

                      maven             pom.xml per module; emits a classic jar plus
                                        pom.xml. Mirrors `build/Maven.java`.
                      modular           module-info.java per module; emits a modular
                                        jar, no pom.xml. Mirrors `build/Modular.java`.
                      modular_to_maven  Both module-info.java and pom.xml; emits
                                        modular jars laid out in a Maven repo.

                    The default is `auto`: it inspects the project root and picks
                    `maven` or `modular` (never `modular_to_maven`, which must be
                    forced). Force a layout with `-Djenesis.project.layout=<name>`.

                    Target folder
                    -------------
                    Every build output lives under the project's target folder. For
                    this build the absolute path is:

                      %{target}

                    Shape under target/:
                      build/                   Per-step output trees, mirroring the
                                               build graph. A selector path like
                                               `build/maven/compose/module/<m>/produce/
                                               assemble/java/artifacts` corresponds
                                               1:1 to a folder here, so an agent can
                                               walk it to inspect a step's actual
                                               output.
                      build/.../<step>/output/ Files the step produced (jars, the
                                               conventional `*.properties`, etc.).
                      build/.../<step>/        Auxiliary files (command-line argument
                        supplement/            files, intermediates).
                      stage/output/            The tree built by `stage`, ready for
                                               `export`. MAVEN / MODULAR_TO_MAVEN
                                               produce a Maven-repository layout;
                                               MODULAR produces <module>/<version>/.

                    target/ is ephemeral and safe to delete; the build recreates
                    whatever is needed. `-Djenesis.executor.rebuild=true` wipes it
                    before building. Browse this path to inspect intermediate
                    results when debugging a selector or diffing a behaviour change.

                    Deriving selectors from target/ (minimal recreation)
                    ----------------------------------------------------
                    Because target/build/ mirrors the build graph 1:1, any folder
                    under it doubles as a selector. To rebuild a single artifact
                    after a source edit without re-running the whole graph:

                      1. Find the step folder you care about under target/build/
                         (e.g. `target/build/maven/compose/module/<m>/produce/
                         assemble/java/artifacts/`).
                      2. Strip the `target/` prefix and any trailing `/output` or
                         `/supplement` from the path.
                      3. Pass the remaining path to the launcher as a selector
                         (e.g. `build/maven/compose/module/<m>/produce/assemble/
                         java/artifacts`).

                    The executor walks that selector's subgraph and re-runs only
                    steps whose serialized form or predecessor checksums changed,
                    ignoring unrelated branches. Combine with wildcards (`:`,
                    `::`) to scope to multiple modules at once, or with the
                    `+<module>` shorthand to address a module by its source-folder
                    name without typing the full path.

                    Conventional per-module files
                    -----------------------------
                    Every per-module step writes properties files into its output
                    folder. Names are constants on `BuildStep`:

                      metadata.properties   POM-style descriptive metadata
                                            (`project`, `artifact`, `version`,
                                            `name`, `description`, `url`,
                                            `license.<id>.{name,url}`,
                                            `developer.<id>.{name,email}`,
                                            `scm.{connection,developerConnection,url}`).
                                            Project-level overrides live in the file
                                            pointed at by
                                            `-Djenesis.project.metadata=<path>`
                                            (conventionally `project.properties`).
                      module.properties     Graph-state only (`path`, `module`,
                                            `test`, `main`). Framework-managed.
                      identity.properties   `<prefix>/<coordinate>` -> path-or-empty.
                      requires.properties   `<prefix>/<coordinate>` -> empty or
                                            `<algo>/<hex>` checksum (pinned).
                      versions.properties   `<prefix>/<version-less coord>` ->
                                            `<version>[ <algo>/<hex>]`. Bill of
                                            materials for the resolution pass.
                      scopes.properties     `<prefix>/<coord>` -> COMPILE,RUNTIME.
                      exclusions.properties `<prefix>/<coord>` -> comma-separated
                                            `<groupId>/<artifactId>` exclusions.
                      inventory.properties  Per-module summary used by staging
                                            (artifacts/sources/documentation/pom/
                                            runtime classpath, prefixed).

                    Treat these as the single source of truth between steps; never
                    invent a side-channel. README's "Conventional folders and files"
                    table is the full reference.

                    Selectors
                    ---------
                    Selectors address points in the build graph:

                      build, stage, export, pin, metadata, help, skill
                                            Top-level entry points.
                      +<module>             Module subgraph inside `build` (does not
                                            run stage/export/pin). The <module>
                                            matches the source folder of the
                                            pom.xml / module-info.java.
                      +<module>/<step>      Drill into a specific step inside that
                                            module, e.g.
                                            +foo/compile/dependencies/resolved.
                      :                     Single-segment wildcard
                                            (`build/:/java` matches every direct
                                            child's `java` step).
                      ::                    Multi-segment wildcard. Lenient - typos
                                            in a `::` tail silently match nothing.

                    Cache model (important for build-step authors)
                    ----------------------------------------------
                    Every `BuildStep` is `Serializable`. The incremental cache keys
                    each step by:
                      1. the digest of its serialized form (fields plus the class's
                         `serialVersionUID`), AND
                      2. the checksums of every predecessor folder's contents.

                    Consequence: changes to a build step's *code* (the body of
                    `apply(...)`, switching tool flags, etc.) do NOT alter the
                    serialized form, so cached outputs are NOT invalidated. After
                    such an edit, bump the step class's `serialVersionUID` to force
                    re-execution of that step, or run with
                    `-Djenesis.executor.rebuild=true` to wipe `target/` for a full
                    rebuild. Changes to project sources are always detected; this
                    caveat applies only when editing the build code itself.

                    Custom Javadoc tags in module-info.java
                    ---------------------------------------
                      @jenesis.release <V>              Java release target.
                      @jenesis.main <class>             Main class for the module.
                      @jenesis.test [<module>]          Mark this module as a test
                                                        variant of <module>.
                      @jenesis.pin <mod> <ver> [<algo>/<hex>]
                                                        Pin a dependency's version
                                                        and (optionally) its content
                                                        checksum.

                    Useful system properties (-Djenesis.project.<key>=<value>)
                    ----------------------------------------------------------
                      root, target, cache         Override input/output locations.
                      layout                      auto, maven, modular,
                                                  modular_to_maven.
                      skipTests                   Skip wiring test execution.
                      sources, documentation      Assemble sources / javadoc jars.
                      stageTests                  Stage test artifacts.
                      strictPinning               Fail the build for any unpinned
                                                  artifact.
                      metadata                    Path-separated list of extra
                                                  metadata files.
                      version                     Stamp version onto every produced
                                                  artifact.
                      pinAlgorithm                Algorithm for pin checksums
                                                  (default SHA-256).

                    Executor-level properties:
                      -Djenesis.executor.rebuild=true   Wipe target/ before build.
                      -Djenesis.executor.timeout=PT5M   Per-step timeout.
                      -Djenesis.verbose=true            Verbose step output.

                    Where to read more
                    ------------------
                    README.md (at the project root, and on the public repo) is the
                    full reference. Key sections an agent will need:

                      "Layouts and assemblers"            How the three layouts
                                                          wire modules.
                      "Conventional folders and files"    Exact schema of every
                                                          properties file.
                      "Build steps" and
                      "Build executor modules"            Per-step and per-module
                                                          contracts.
                      "Project metadata"                  How metadata.properties
                                                          and project.properties
                                                          merge.
                      "Releasing to Maven Central"        Stage / export / pin and
                                                          handoff to JReleaser.

                    Online resources
                    ----------------
                      Source repository
                        https://github.com/raphw/jenesis
                      README (current main)
                        https://github.com/raphw/jenesis/blob/main/README.md
                      Issue tracker (bugs, questions, design discussion)
                        https://github.com/raphw/jenesis/issues
                      Releases (changelog, downloads, the matching git tag for
                      each published version)
                        https://github.com/raphw/jenesis/releases
                      Example build configurations bundled in the repo
                        build/Maven.java, build/Modular.java,
                        build/ModularToMaven.java, build/Manual.java,
                        build/Minimal.java

                    When stuck, prefer reading the source: every public type lives
                    under `sources/build/jenesis/` and is small enough to read
                    end-to-end. The tests under `tests/` double as executable
                    documentation for the public API.

                    Run `help` for the same content with color, oriented at humans.
                    """).replace("%{target}", target.toAbsolutePath().normalize().toString()));
        }
    }

    private static final class PinModule implements BuildExecutorModule {

        private final Path root;
        private final String fileName;
        private final Function<Path, BuildStep> stepFactory;

        private PinModule(Path root, String fileName, Function<Path, BuildStep> stepFactory) {
            this.root = root;
            this.fileName = fileName;
            this.stepFactory = stepFactory;
        }

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
            Set<String> paths = new LinkedHashSet<>();
            for (Path folder : inherited.values()) {
                Path moduleFile = folder.resolve(BuildStep.MODULE);
                if (!Files.isRegularFile(moduleFile)) {
                    continue;
                }
                SequencedProperties properties = SequencedProperties.ofFiles(moduleFile);
                String path = properties.getProperty("path");
                if (path != null) {
                    paths.add(path);
                }
            }
            for (String path : paths) {
                Path file = root.resolve(path).resolve(fileName);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                buildExecutor.addStep("module-" + BuildExecutorModule.encode(path),
                        stepFactory.apply(file),
                        inherited.sequencedKeySet());
            }
        }
    }

    private static final class PomAwareAssembler implements MultiProjectAssembler<ProjectModuleDescriptor> {

        private final MultiProjectAssembler<? super ProjectModuleDescriptor> base;

        private PomAwareAssembler(MultiProjectAssembler<? super ProjectModuleDescriptor> base) {
            this.base = base;
        }

        @Override
        public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                         Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers) {
            BuildExecutorModule delegate = base.apply(descriptor.toInherited(), repositories, resolvers);
            return (sub, inherited) -> {
                sub.addModule("assemble", delegate, inherited.sequencedKeySet().stream());
                sub.addModule("describe", (describe, describeInherited) ->
                                describe.addStep("pom", new Pom(), describeInherited.sequencedKeySet().stream()),
                        inherited.sequencedKeySet().stream());
            };
        }
    }

    public Project() {
        this(Path.of("."),
                Path.of("target"),
                Path.of(".jenesis", "cache"),
                Layout.AUTO,
                true,
                false,
                false,
                false,
                false,
                List.of(),
                null,
                Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(BUILD))),
                new JavaMultiProjectAssembler(),
                Map.of(),
                Map.of());
    }

    public Project root(Path root) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project target(Path target) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project cache(Path cache) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project layout(Layout layout) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project tests(boolean tests) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project sources(boolean sources) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project documentation(boolean documentation) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project stageTests(boolean stageTests) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project strictPinning(boolean strictPinning) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project metadata(Path... metadata) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                List.of(metadata),
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project version(String version) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project defaultTarget(String... defaultTarget) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(defaultTarget))),
                assembler,
                repositories,
                resolvers);
    }

    public Project assembler(MultiProjectAssembler<? super ProjectModuleDescriptor> assembler) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project repositories(Map<String, Repository> repositories) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project resolvers(Map<String, Resolver> resolvers) {
        return new Project(root,
                target,
                cache,
                layout,
                tests,
                sources,
                documentation,
                stageTests,
                strictPinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public Project resolveProperties() {
        Path resolvedRoot = root;
        Path resolvedTarget = target;
        Path resolvedCache = cache;
        Layout resolvedLayout = layout;
        boolean resolvedTests = tests;
        boolean resolvedSources = sources;
        boolean resolvedDocumentation = documentation;
        boolean resolvedStageTests = stageTests;
        boolean resolvedStrictPinning = strictPinning;
        List<Path> resolvedMetadata = metadata;
        String resolvedVersion = version;
        String rootOverride = System.getProperty("jenesis.project.root");
        if (rootOverride != null) {
            resolvedRoot = Path.of(rootOverride);
        }
        String targetOverride = System.getProperty("jenesis.project.target");
        if (targetOverride != null) {
            resolvedTarget = Path.of(targetOverride);
        }
        String cacheOverride = System.getProperty("jenesis.project.cache");
        if (cacheOverride != null) {
            resolvedCache = Path.of(cacheOverride);
        }
        String forced = System.getProperty("jenesis.project.layout");
        if (forced != null) {
            resolvedLayout = switch (forced.toLowerCase(Locale.ROOT)) {
                case "auto" -> Layout.AUTO;
                case "maven" -> Layout.MAVEN;
                case "modular" -> Layout.MODULAR;
                case "modular_to_maven" -> Layout.MODULAR_TO_MAVEN;
                default -> throw new IllegalArgumentException(
                        "Unknown layout: " + forced + " (expected auto, maven, modular, or modular_to_maven)");
            };
        }
        if (System.getProperty("jenesis.project.skipTests") != null) {
            resolvedTests = false;
        }
        if (Boolean.getBoolean("jenesis.project.sources")) {
            resolvedSources = true;
        }
        if (Boolean.getBoolean("jenesis.project.documentation")) {
            resolvedDocumentation = true;
        }
        if (Boolean.getBoolean("jenesis.project.stageTests")) {
            resolvedStageTests = true;
        }
        String strictPinningOverride = System.getProperty("jenesis.project.strictPinning");
        if (strictPinningOverride != null) {
            resolvedStrictPinning = Boolean.parseBoolean(strictPinningOverride);
        }
        String metadataOverride = System.getProperty("jenesis.project.metadata");
        if (metadataOverride != null) {
            resolvedMetadata = Arrays.stream(metadataOverride.split(Pattern.quote(File.pathSeparator)))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Path::of)
                    .toList();
        }
        String versionOverride = System.getProperty("jenesis.project.version");
        if (versionOverride != null) {
            resolvedVersion = versionOverride;
        }
        if (resolvedRoot.isAbsolute()) {
            Path absoluteCwd = Path.of("").toAbsolutePath().normalize();
            Path absoluteRoot = resolvedRoot.normalize();
            if (absoluteRoot.startsWith(absoluteCwd)) {
                Path relative = absoluteCwd.relativize(absoluteRoot);
                resolvedRoot = relative.toString().isEmpty() ? Path.of(".") : relative;
            }
        }
        return new Project(resolvedRoot,
                resolvedTarget,
                resolvedCache,
                resolvedLayout,
                resolvedTests,
                resolvedSources,
                resolvedDocumentation,
                resolvedStageTests,
                resolvedStrictPinning,
                resolvedMetadata,
                resolvedVersion,
                defaultTarget,
                assembler,
                repositories,
                resolvers);
    }

    public SequencedMap<String, Path> build(String... selectors) throws IOException {
        BuildExecutor executor = BuildExecutor.of(target);
        Function<String, String> resolver = layout.apply(executor, this, assembler);
        return executor.execute(Arrays.stream(selectors.length == 0 ? defaultTarget.toArray(String[]::new) : selectors)
                .map(selector -> selector.startsWith("+") ? resolver.apply(selector.substring(1)) : selector)
                .toArray(String[]::new));
    }

    SequencedMap<String, Path> doMain(String... selectors) throws IOException, InterruptedException {
        if (Boolean.getBoolean("jenesis.project.docker")) {
            SortedMap<String, String> properties = new TreeMap<>();
            for (String name : System.getProperties().stringPropertyNames()) {
                if (name.startsWith("jenesis.") && !name.startsWith("jenesis.project.docker")) {
                    properties.put(name, System.getProperty(name));
                }
            }
            String image = System.getProperty("jenesis.project.docker.image");
            Path root = this.root().toAbsolutePath().normalize();
            DockerizedJava docker = image == null ? new DockerizedJava(root) : new DockerizedJava(root, image);
            for (Path path : List.of(this.target(), this.cache())) {
                Path absolute = (path.isAbsolute() ? path : root.resolve(path)).normalize();
                if (!absolute.startsWith(root)) {
                    docker = docker.mount(absolute, absolute.toString(), false);
                }
            }
            String mavenRepositoryUri = System.getenv("MAVEN_REPOSITORY_URI");
            if (mavenRepositoryUri != null) {
                docker = docker.env("MAVEN_REPOSITORY_URI", mavenRepositoryUri);
            }
            if (Boolean.getBoolean("jenesis.verbose")) {
                System.out.println("Launching build within Docker image: " + docker.image());
            }
            int code = docker.execute("build/jenesis/Project.java", properties, selectors);
            if (code != 0) {
                System.exit(code);
            }
        }
        return this.build(selectors);
    }

    public static void main(String... selectors) {
        try {
            new Project().resolveProperties().doMain(selectors);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new UsageHint(t);
        }
    }

    private static final class UsageHint extends RuntimeException {

        private UsageHint(Throwable cause) {
            super("Pass `help` as the only argument on the command line to receive"
                    + " usage information, or `skill` for an agent-oriented briefing.",
                    cause);
        }
    }
}
