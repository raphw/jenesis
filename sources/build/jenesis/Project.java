package build.jenesis;

import module java.base;
import build.jenesis.docker.DockerizedJava;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenModuleResolver;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenProject;
import build.jenesis.maven.MavenRepositoryExport;
import build.jenesis.maven.MavenRepositoryStaging;
import build.jenesis.maven.MavenResolver;
import build.jenesis.maven.PinPom;
import build.jenesis.maven.Pom;
import build.jenesis.module.JenesisModuleRepository;
import build.jenesis.module.JenesisModuleRepositoryExport;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.module.ModularStaging;
import build.jenesis.module.PinModuleInfo;
import build.jenesis.project.AssemblyDescriptor;
import build.jenesis.project.Ide;
import build.jenesis.project.InferredMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.project.ProjectModuleDescriptor;
import build.jenesis.project.ProjectWatch;
import build.jenesis.step.Bind;
import build.jenesis.step.ImageStaging;
import build.jenesis.step.Inventory;
import build.jenesis.step.ReportStaging;
import build.jenesis.step.Tree;

public record Project(
        Path root,
        Path configuration,
        Path target,
        Path artifacts,
        BuildExecutorCache cache,
        HashDigestFunction hashFunction,
        Layout layout,
        boolean tests,
        boolean sources,
        boolean documentation,
        Pinning pinning,
        List<Path> metadata,
        String version,
        SequencedSet<String> defaultTarget,
        MultiProjectAssembler<? super ProjectModuleDescriptor> assembler,
        Map<String, Repository> repositories,
        Map<String, Resolver> resolvers,
        Supplier<BuildExecutor.Configuration> configurator) {

    public static final String BUILD = "build",
            STAGE = "stage",
            EXPORT = "export",
            PIN = "pin",
            DEPENDENCIES = "dependencies",
            IDE = "ide",
            METADATA = "metadata",
            HELP = "help",
            SKILL = "skill",
            PROPERTIES = "properties";

    @FunctionalInterface
    public interface Layout {

        Function<String, String> apply(BuildExecutor executor,
                                       Project project,
                                       MultiProjectAssembler<? super ProjectModuleDescriptor> assembler,
                                       boolean printDependencies) throws IOException;

        Layout MAVEN = (executor, project, assembler, printDependencies) -> {
            executor.addModule(HELP, new HelpModule("maven", assembler.getClass().getName()));
            executor.addModule(SKILL, new SkillModule(project.target()));
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            MultiProjectAssembler<? super ProjectModuleDescriptor> pomAware = new PomAwareAssembler(assembler, null, null, false);
            executor.addModule(BUILD, (sub, inherited) -> {
                Map<String, Repository> repositories = new LinkedHashMap<>(project.repositories());
                repositories.putIfAbsent("maven",
                        new MavenDefaultRepository()
                                .cached(project.artifacts() == null ? null : Files.createDirectories(project.artifacts())));
                Map<String, Resolver> resolvers = new LinkedHashMap<>(project.resolvers());
                resolvers.putIfAbsent("maven", new MavenPomResolver());
                SequencedSet<String> mavenDeps = new LinkedHashSet<>();
                inherited.sequencedKeySet().stream()
                        .filter(key -> key.startsWith(BuildExecutorModule.PREVIOUS + METADATA + "/"))
                        .forEach(mavenDeps::add);
                sub.addModule("maven", MavenProject.make(project.root(),
                                "main",
                                "maven",
                                Collections.unmodifiableMap(repositories),
                                Collections.unmodifiableMap(resolvers),
                                project.pinning(),
                                printDependencies,
                                (descriptor, mergedRepos, mergedResolvers) -> pomAware.apply(
                                        new ProjectModuleDescriptor(descriptor,
                                                project.configuration(),
                                                project.tests(),
                                                project.sources(),
                                                project.documentation(),
                                                project.pinning(),
                                                PathPlacement.CLASS_PATH),
                                        mergedRepos,
                                        mergedResolvers)),
                        mavenDeps);
            }, METADATA);
            executor.addModule(STAGE, (stage, inherited) -> {
                stage.addStep("maven", new MavenRepositoryStaging(), inherited.sequencedKeySet());
                stage.addStep("packages", new ImageStaging("package"), inherited.sequencedKeySet());
                stage.addStep("native", new ImageStaging("native"), inherited.sequencedKeySet());
                stage.addStep("reports", new ReportStaging(), inherited.sequencedKeySet());
            }, BUILD);
            executor.addModule(EXPORT, (export, _) -> export.addStep(
                    "maven", new MavenRepositoryExport(), BuildExecutorModule.PREVIOUS + STAGE + "/maven"), STAGE);
            String prefix = BUILD + "/maven/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            executor.addModule(PIN, new PinModule(project.root(),
                    "pom.xml",
                    (path, file) -> new PinPom("maven", path, List.of(file), project.hashFunction())), BUILD);
            executor.addModule(DEPENDENCIES, (tree, inherited) -> tree.addStep(
                    "tree", new Tree(), inherited.sequencedKeySet()), BUILD);
            executor.addModule(IDE, (ide, inherited) -> {
                ide.addStep("idea", new Ide(project.root(), Ide.IDEA), inherited.sequencedKeySet());
                ide.addStep("vscode", new Ide(project.root(), Ide.VSCODE), inherited.sequencedKeySet());
                ide.addStep("eclipse", new Ide(project.root(), Ide.ECLIPSE), inherited.sequencedKeySet());
            }, BUILD);
            return name -> {
                int slash = name.indexOf('/');
                String module = (slash == -1 ? name : name.substring(0, slash)).replace('+', '/');
                return prefix + "/module-" + BuildExecutorModule.encode(module)
                        + (slash == -1 ? "" : "/" + name.substring(slash + 1));
            };
        };

        Layout MODULAR = (executor, project, assembler, printDependencies) -> {
            executor.addModule(HELP, new HelpModule("modular", assembler.getClass().getName()));
            executor.addModule(SKILL, new SkillModule(project.target()));
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            executor.addModule(BUILD, (sub, inherited) -> {
                Map<String, Repository> repositories = new LinkedHashMap<>(project.repositories());
                repositories.putIfAbsent("module",
                        new JenesisModuleRepository(true)
                                .cached(project.artifacts() == null ? null : Files.createDirectories(project.artifacts()))
                                .prepend(JenesisModuleRepository.ofLocal()));
                Map<String, Resolver> resolvers = new LinkedHashMap<>(project.resolvers());
                resolvers.putIfAbsent("module", new ModularJarResolver(false));
                SequencedSet<String> modulesDeps = new LinkedHashSet<>();
                inherited.sequencedKeySet().stream()
                        .filter(key -> key.startsWith(BuildExecutorModule.PREVIOUS + METADATA + "/"))
                        .forEach(modulesDeps::add);
                sub.addModule("modules", ModularProject.make(project.root(),
                                "main",
                                "module",
                                _ -> true,
                                Collections.unmodifiableMap(repositories),
                                Collections.unmodifiableMap(resolvers),
                                project.pinning(),
                                true,
                                printDependencies,
                                (descriptor, mergedRepos, mergedResolvers) -> assembler.apply(
                                        new ProjectModuleDescriptor(descriptor,
                                                project.configuration(),
                                                project.tests(),
                                                project.sources(),
                                                project.documentation(),
                                                project.pinning(),
                                                PathPlacement.MODULE_PATH),
                                        mergedRepos,
                                        mergedResolvers)),
                        modulesDeps);
            }, METADATA);
            executor.addModule(STAGE, (stage, inherited) -> {
                stage.addStep("modular", new ModularStaging(), inherited.sequencedKeySet());
                stage.addStep("packages", new ImageStaging("package"), inherited.sequencedKeySet());
                stage.addStep("runtime", new ImageStaging("image"), inherited.sequencedKeySet());
                stage.addStep("native", new ImageStaging("native"), inherited.sequencedKeySet());
                stage.addStep("reports", new ReportStaging(), inherited.sequencedKeySet());
            }, BUILD);
            executor.addModule(EXPORT, (export, _) -> export.addStep(
                    "modular", new JenesisModuleRepositoryExport(), BuildExecutorModule.PREVIOUS + STAGE + "/modular"), STAGE);
            String prefix = BUILD + "/modules/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            executor.addModule(PIN, new PinModule(project.root(), "module-info.java",
                    (path, file) -> new PinModuleInfo("module", path, List.of(file), project.hashFunction())), BUILD);
            executor.addModule(DEPENDENCIES, (tree, inherited) -> tree.addStep(
                    "tree", new Tree(), inherited.sequencedKeySet()), BUILD);
            executor.addModule(IDE, (ide, inherited) -> {
                ide.addStep("idea", new Ide(project.root(), Ide.IDEA), inherited.sequencedKeySet());
                ide.addStep("vscode", new Ide(project.root(), Ide.VSCODE), inherited.sequencedKeySet());
                ide.addStep("eclipse", new Ide(project.root(), Ide.ECLIPSE), inherited.sequencedKeySet());
            }, BUILD);
            return name -> {
                int slash = name.indexOf('/');
                String module = (slash == -1 ? name : name.substring(0, slash)).replace('+', '/');
                return prefix + "/module-" + BuildExecutorModule.encode(module)
                        + (slash == -1 ? "" : "/" + name.substring(slash + 1));
            };
        };

        Layout MODULAR_TO_MAVEN = (executor, project, assembler, printDependencies) -> {
            executor.addModule(HELP, new HelpModule("modular_to_maven", assembler.getClass().getName()));
            executor.addModule(SKILL, new SkillModule(project.target()));
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            MultiProjectAssembler<? super ProjectModuleDescriptor> pomAware = new PomAwareAssembler(assembler,
                    BuildExecutorModule.PREVIOUS.repeat(2) + MultiProjectModule.MANIFESTS,
                    "module",
                    true);
            executor.addModule(BUILD, (sub, inherited) -> {
                Map<String, Repository> repositories = new LinkedHashMap<>(project.repositories());
                repositories.putIfAbsent("maven",
                        new MavenDefaultRepository()
                                .cached(project.artifacts() == null ? null : Files.createDirectories(project.artifacts())));
                repositories.putIfAbsent("module",
                        new JenesisModuleRepository(false)
                                .cached(project.artifacts() == null ? null : Files.createDirectories(project.artifacts()))
                                .prepend(JenesisModuleRepository.ofLocal()));
                Map<String, Resolver> resolvers = new LinkedHashMap<>(project.resolvers());
                resolvers.putIfAbsent("maven", new MavenPomResolver());
                resolvers.putIfAbsent("module", new MavenModuleResolver("maven",
                        MavenResolver.of(resolvers.get("maven")), repositories.get("module")));
                SequencedSet<String> modulesDeps = new LinkedHashSet<>();
                inherited.sequencedKeySet().stream()
                        .filter(key -> key.startsWith(BuildExecutorModule.PREVIOUS + METADATA + "/"))
                        .forEach(modulesDeps::add);
                sub.addModule("modules", ModularProject.make(project.root(),
                                "main",
                                "module",
                                _ -> true,
                                Collections.unmodifiableMap(repositories),
                                Collections.unmodifiableMap(resolvers),
                                project.pinning(),
                                true,
                                printDependencies,
                                (descriptor, mergedRepos, mergedResolvers) -> pomAware.apply(
                                        new ProjectModuleDescriptor(descriptor,
                                                project.configuration(),
                                                project.tests(),
                                                project.sources(),
                                                project.documentation(),
                                                project.pinning(),
                                                PathPlacement.INFERRED),
                                        mergedRepos,
                                        mergedResolvers)),
                        modulesDeps);
            }, METADATA);
            executor.addModule(STAGE, (stage, inherited) -> {
                stage.addStep("maven", new MavenRepositoryStaging(), inherited.sequencedKeySet());
                stage.addStep("modular", new ModularStaging(), inherited.sequencedKeySet());
                stage.addStep("packages", new ImageStaging("package"), inherited.sequencedKeySet());
                stage.addStep("runtime", new ImageStaging("image"), inherited.sequencedKeySet());
                stage.addStep("native", new ImageStaging("native"), inherited.sequencedKeySet());
                stage.addStep("reports", new ReportStaging(), inherited.sequencedKeySet());
            }, BUILD);
            executor.addModule(EXPORT, (export, _) -> {
                export.addStep("maven", new MavenRepositoryExport(), BuildExecutorModule.PREVIOUS + STAGE + "/maven");
                export.addStep("modular", new JenesisModuleRepositoryExport(), BuildExecutorModule.PREVIOUS + STAGE + "/modular");
            }, STAGE);
            String prefix = BUILD + "/modules/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            executor.addModule(PIN,
                    new PinModule(project.root(),
                            "module-info.java",
                            (path, file) -> new PinModuleInfo("module", path, List.of(file), project.hashFunction())),
                    BUILD);
            executor.addStep(DEPENDENCIES, new Tree(), BUILD);
            executor.addModule(IDE, (ide, inherited) -> {
                ide.addStep("idea", new Ide(project.root(), Ide.IDEA), inherited.sequencedKeySet());
                ide.addStep("vscode", new Ide(project.root(), Ide.VSCODE), inherited.sequencedKeySet());
                ide.addStep("eclipse", new Ide(project.root(), Ide.ECLIPSE), inherited.sequencedKeySet());
            }, BUILD);
            return name -> {
                int slash = name.indexOf('/');
                String module = (slash == -1 ? name : name.substring(0, slash)).replace('+', '/');
                return prefix + "/module-" + BuildExecutorModule.encode(module)
                        + (slash == -1 ? "" : "/" + name.substring(slash + 1));
            };
        };

        Layout AUTO = (executor, project, assembler, printDependencies) -> of(project.root()).apply(executor, project, assembler, printDependencies);

        static Layout of(Path root) throws IOException {
            if (Files.isRegularFile(root.resolve("pom.xml"))) {
                return MAVEN;
            }
            List<Path> moduleInfos = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root)
                            && Files.exists(directory.resolve(BuildExecutor.SKIP_MARKER))) {
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
            if (!moduleInfos.isEmpty()) {
                return MODULAR_TO_MAVEN;
            }
            throw new IllegalStateException(
                    "No build descriptor found under " + root.toAbsolutePath()
                            + " (expected a module-info.java or a pom.xml)");
        }
    }

    private record MetadataModule(SequencedMap<String, Path> files, String version) implements BuildExecutorModule {

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
                      %{name}build%{reset}        Resolve, compile, package, and test every module
                      %{name}stage%{reset}        Stage produced artifacts into a local repository
                      %{name}export%{reset}       Export the staged repository as the build deliverable
                      %{name}pin%{reset}          Rewrite version/checksum pins into pom.xml or module-info.java
                      %{name}dependencies%{reset} Print each module's resolved dependency graph (with licenses)
                      %{name}ide%{reset}          Generate IntelliJ IDEA, VS Code, and Eclipse project metadata
                      %{name}metadata%{reset}     Refresh the metadata module outputs
                      %{name}help%{reset}         Print this message
                      %{name}skill%{reset}        Print an agent-oriented onboarding briefing (plain text)
                      %{name}properties%{reset}   Print the active -Djenesis.* system properties, sorted by key
                    
                    %{header}Module-scoped selector:%{reset}
                      A selector starting with %{name}+%{reset} is shorthand for a single project module:
                      %{name}+<module>%{reset} resolves to the module's subgraph inside %{name}build%{reset} (it does
                      not run %{name}stage%{reset}, %{name}export%{reset}, or %{name}pin%{reset}; invoke those explicitly if needed).
                      %{name}+<module>/<step>%{reset} drills further into a specific step inside that
                      module, e.g. %{name}+myModule/compile/dependencies/resolved%{reset}.
                      <module> matches the source folder that holds the module's pom.xml
                      or module-info.java. A module in a nested folder is written with %{name}+%{reset}
                      between the segments (a %{name}/%{reset} starts the step tail): the module in
                      %{name}foo/bar%{reset} is selected as %{name}+foo+bar%{reset}, its compile step as
                      %{name}+foo+bar/compile%{reset}. Run %{name}build%{reset} once and look at the printed
                      module-* lines to discover available module names.
                    
                    %{header}Wildcards in selectors:%{reset}
                      %{name}:%{reset}   matches a single path segment, e.g. %{name}build/:/java%{reset} matches
                          the %{name}java%{reset} step of every direct child of %{name}build%{reset}.
                      %{name}::%{reset}  matches any depth (zero or more segments), e.g. %{name}::/test%{reset}
                          matches every %{name}test%{reset} step anywhere in the tree.
                      Both wildcards are lenient: branches that fail to match are silently
                      skipped, so a typo in the tail of a %{name}::%{reset} selector produces no error.
                    
                    %{header}System properties (-Djenesis.project.<key>=<value>):%{reset}
                      Read by the default %{name}new Project()%{reset} constructor as the starting
                      defaults, so they apply unless a wired value overrides them
                      (an explicit %{name}.layout(...)%{reset}, %{name}.target(...)%{reset}, and so on wins).
                      %{name}root%{reset}, %{name}target%{reset}, %{name}artifacts%{reset}          Override input/output locations
                      %{name}layout%{reset}                           auto, maven, modular, or modular_to_maven
                      %{name}sources%{reset}, %{name}documentation%{reset}           Assemble source/javadoc jars
                      %{name}metadata%{reset}                         Path-separated list of extra metadata files
                      %{name}version%{reset}                          Project version
                      %{name}digest%{reset}                           Algorithm for pin and dependency checksums (default: SHA-256)
                      %{name}watch%{reset}                            Rebuild the selected target whenever a source file changes (Ctrl+C to stop)
                      %{name}docker%{reset}[, %{name}docker.image%{reset}]           Wrap the build in a container
                      %{name}docker.mount%{reset} <h[:c],...>         Extra read-only container mounts (host or host:container)
                      %{name}docker.mountWritable%{reset} <h[:c],...> Extra writable container mounts
                      %{name}docker.env%{reset} <N[=V],...>           Forward host env vars (name) or set them (name=value)
                    
                    %{header}Printing (-Djenesis.print.<key>=<value>):%{reset}
                      %{name}progress%{reset}                         Print the build progress lines (default: true)
                      %{name}checksum%{reset}                         Print each step's input/output file checksums
                      %{name}command%{reset}                          Print each external tool command line as it runs
                      %{name}fetch%{reset}                            Print each artifact downloaded from a repository
                      %{name}cache%{reset}                            Print each step served from or written to the build cache
                      %{name}docker%{reset}                           Print the Docker image when a build/run is wrapped (default: true)
                      %{name}dependencies%{reset}                     Print each module's dependency tree as it resolves
                    
                    %{header}Pinning (-Djenesis.dependency.pin=<mode>):%{reset}
                      %{name}strict%{reset} fails the build on any unpinned artifact; %{name}ignore%{reset} floats
                      versions to the latest and skips checksum verification (refresh the
                      pins by running the %{name}pin%{reset} step under it); %{name}versions%{reset} keeps the
                      pinned versions but skips checksum verification. Unset keeps existing
                      pins but tolerates missing ones.

                    %{header}Platform (-Djenesis.platform.<token>=<true|false>):%{reset}
                      The active platform starts from the detected operating system and
                      chipset (%{name}windows%{reset}/%{name}linux%{reset}/%{name}macos%{reset} plus %{name}x86_64%{reset}/%{name}aarch64%{reset}). A
                      %{name}<token>=true%{reset} flag adds a token and %{name}<token>=false%{reset} removes a
                      detected one, selecting platform-guarded pins (see the guard
                      suffix below); e.g. %{name}-Djenesis.platform.linux=false
                      -Djenesis.platform.windows=true%{reset} cross-resolves a Windows closure.

                    %{header}Repositories (-Djenesis.repository.<key>=<value>):%{reset}
                      %{name}insecure%{reset}                          Allow plaintext (%{name}http://%{reset}) repository fetches;
                                                      by default only %{name}https://%{reset} and %{name}file://%{reset} are
                                                      accepted and a credential is never forwarded
                                                      across a redirect to another host
                      The Maven and Jenesis module repositories take a %{name}uri%{reset} (remote),
                      %{name}local%{reset} (on-disk cache) and %{name}token%{reset} (bearer credential) under
                      %{name}jenesis.maven.<key>%{reset} and %{name}jenesis.module.<key>%{reset}; each falls back to the
                      %{name}MAVEN_REPOSITORY_<KEY>%{reset} / %{name}JENESIS_REPOSITORY_<KEY>%{reset} environment variable.

                    %{header}Tests (-Djenesis.test.<key>=<value>):%{reset}
                      %{name}skip%{reset}                             Skip executing tests
                      %{name}engine%{reset} <name>                    Force the test engine (%{name}junit-platform%{reset},
                                                      %{name}junit4%{reset}, %{name}testng%{reset}); unset auto-detects it
                                                      from the resolved test dependencies
                      %{name}filter%{reset} <patterns>                Comma-separated %{name}<classRegex>[#<method>]%{reset} entries
                                                      restricting which tests run; changing the value
                                                      invalidates the test step's cache and forces a re-run
                      %{name}incremental%{reset} [<digest>]           Re-run only the tests a change can reach: a fast
                                                      feedback aid for %{name}watch%{reset} loops, not a correctness gate.
                                                      Static selection cannot see reflection or other indirect
                                                      couplings, so conclude with a full run once a change is
                                                      done. The value names the change-detection digest; omit it for %{name}MD5%{reset}

                    %{header}Staging (-Djenesis.stage.<key>=<value>):%{reset}
                      %{name}tests%{reset}                            Stage test-variant artifacts alongside main artifacts
                    
                    %{header}Build cache (-Djenesis.cache.uri=<uri>):%{reset}
                      Reuse step outputs across builds. A %{name}file://%{reset} URI is an on-disk
                      cache, tuned by an optional %{name}cache.properties%{reset} at its root; an
                      %{name}http(s)://%{reset} URL is a remote cache server, configured through
                      %{name}jenesis.cache.<key>%{reset}: %{name}project%{reset} names the project and %{name}key%{reset} the access
                      key (both sent as headers), %{name}timeout%{reset} bounds the connect attempt
                      (default %{name}PT1S%{reset}) and %{name}insecure%{reset} permits the key over plaintext http
                      off loopback. Reads block the build; writes run on a background
                      thread. Trace them with %{name}-Djenesis.print.cache%{reset}.
                      %{name}-Djenesis.project.cache%{reset} keeps a project-local cache (a filesystem
                      path; empty resolves to %{name}.jenesis/cache%{reset} under the project root);
                      with a remote configured it layers in front, and a local hit still
                      sends the remote a HEAD touch so its LRU stays warm.

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
                      %{name}@jenesis.pin%{reset} <group>/<repo>/<coord> <ver> [<algo>/<hex>] [<guard>]
                                                       Pin a dependency version and checksum
                                                       (<module> is short for <group>/module/<module>,
                                                       <groupId>/<artifactId> for <group>/maven/<groupId>/<artifactId>);
                                                       an optional trailing %{name}[<token>,<token>...]%{reset} guard applies
                                                       the pin only when those tokens are in the active platform,
                                                       with an unguarded line for the same coordinate as fallback

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
                    Jenesis - operating instructions for coding agents
                    ==================================================
                    
                    You are operating inside a Jenesis-built Java project. This
                    briefing tells you how to drive the build, inspect intermediate
                    state, and avoid the cache pitfalls that catch agents most
                    often. README.md at the project root is the full reference;
                    use this document as the working minimum.
                    
                    1. Invoke the build
                    -------------------
                    Pick whichever launcher fits the situation; all three are
                    equivalent and forward your selectors to a `BuildExecutor`
                    wired to the configured layout:
                    
                      - run the installed `jenesis` CLI (release zip / SDKMAN);
                      - run `java <Project.java> [selectors...]` on a source-mode
                        `Project.java` script in the project tree;
                      - call `Project.build(selectors...)` from Java code when
                        embedding the build.
                    
                    Pass no selectors to run the default target (`build`). Pass
                    multiple selectors space-separated to run several entry points
                    in one invocation.
                    
                    When the build ships as source files, the source-mode
                    launcher recompiles the build's own engine and `Project.java`
                    on every invocation. While the build code is unchanged, skip
                    that recompile to launch faster:
                    
                      - Precompile the build once with `javac`, then run the
                        compiled launcher directly:
                          javac -d .jenesis/launcher \\
                              $(find build/jenesis/ -name '*.java')
                          java -cp .jenesis/launcher \\
                              build.jenesis.Project [selectors...]
                      - Or ahead-of-time compile that launcher with GraalVM
                        `native-image` for near-instant startup. The native binary
                        detects the native-image runtime and forks the JDK
                        `javac`/`jar` tools (keep a JDK on `JAVA_HOME`/`PATH`); the
                        incremental cache serializes build steps, so the image
                        needs reachability metadata captured from a real build:
                          java -Djenesis.process.factory=fork \\
                              -agentlib:native-image-agent=config-output-dir=.jenesis/native-config \\
                              -cp .jenesis/launcher build.jenesis.Project build
                          native-image --no-fallback \\
                              -H:ConfigurationFileDirectories=.jenesis/native-config \\
                              -cp .jenesis/launcher build.jenesis.Project jenesis
                          ./jenesis [selectors...]
                        Capture the metadata from builds that exercise the layouts
                        and steps you use. Loading foreign build modules (the
                        class-loader bridge) needs a full JVM and is not supported
                        on this path.
                    
                    Rebuild the precompiled or native launcher whenever the build
                    sources change. This only accelerates launching the build;
                    the project being built is still recompiled by the build graph
                    whenever its own sources change.
                    
                    2. Choose a layout when needed
                    ------------------------------
                    The layout decides how modules are discovered and what gets
                    staged:
                    
                      maven             pom.xml per module; emits a classic jar
                                        plus pom.xml.
                      modular           module-info.java per module; emits a
                                        modular jar, no pom.xml.
                      modular_to_maven  module-info.java per module; emits a
                                        modular jar plus a generated pom.xml,
                                        staged as both a module and a Maven repo.
                    
                    Trust the default: `auto` inspects the project root and picks
                    `maven` when a root `pom.xml` is present, otherwise
                    `modular_to_maven` for a `module-info.java` project (it never
                    picks the plain `modular` layout, which you must force).
                    Override with `-Djenesis.project.layout=<name>` only when you
                    need a layout other than what `auto` would select.
                    
                    3. Inspect target/
                    ------------------
                    Every build output lives under the project's target folder. For
                    this build the absolute path is:
                    
                      %{target}
                    
                    Shape under target/:
                      build/                   Per-step output trees mirroring the
                                               build graph 1:1. Walk this when you
                                               need to see a step's actual output.
                      build/.../<step>/output/ Files the step produced (jars, the
                                               conventional `*.properties`, etc.).
                      build/.../<step>/        Auxiliary files (command-line
                        supplement/            argument files, intermediates).
                      stage/<layout>/output/   The tree built by `stage`, ready for
                                               `export`, nested under a layout
                                               sub-step. MAVEN produces a
                                               Maven-repository layout under
                                               stage/maven/output; MODULAR produces
                                               <module>/<version>/ under
                                               stage/modular/output; MODULAR_TO_MAVEN
                                               stages both, and `export` publishes
                                               each.
                    
                    Do not delete target/ and do not pass
                    `-Djenesis.executor.rebuild=true` to wipe it. Jenesis tracks
                    source changes and predecessor checksums on every step and
                    will re-run exactly the steps whose inputs changed;
                    clearing the cache by hand only forces the next build to
                    repeat work it would otherwise skip. Browse this path when
                    debugging a selector or diffing a behaviour change, but
                    leave its contents in place.
                    
                    4. Derive a selector from target/ for minimal recreation
                    --------------------------------------------------------
                    Because target/build/ mirrors the build graph 1:1, any folder
                    under it doubles as a selector. To rebuild a single artifact
                    after a source edit without re-running the whole graph:
                    
                      1. Find the step folder under target/build/ (e.g.
                         `target/build/maven/compose/module/<m>/produce/
                         assemble/binary/artifacts/`).
                      2. Strip the `target/` prefix and any trailing `/output` or
                         `/supplement` segment.
                      3. Pass what remains to the launcher as a selector (e.g.
                         `build/maven/compose/module/<m>/produce/assemble/binary/
                         artifacts`).
                    
                    The executor walks that selector's subgraph and re-runs only
                    steps whose serialized form or predecessor checksums changed.
                    Combine with wildcards (`:`, `::`) to scope to multiple
                    modules, or use the `+<module>` shorthand to address a module
                    by its source-folder name without typing the full path.
                    
                    5. Read per-module state from properties files
                    ----------------------------------------------
                    Every per-module step writes properties files into its output
                    folder. Read these to learn what a step decided; never invent
                    a side channel. Names are constants on `BuildStep`:
                    
                      metadata.properties   POM-style descriptive metadata
                                            (`project`, `artifact`, `version`,
                                            `name`, `description`, `url`,
                                            `license.<id>.{name,url}`,
                                            `developer.<id>.{name,email}`,
                                            `scm.{connection,developerConnection,url}`).
                                            Project-level overrides live in the
                                            file pointed at by
                                            `-Djenesis.project.metadata=<path>`
                                            (conventionally `project.properties`).
                      module.properties     Graph-state only (`path`, `module`,
                                            `test`, `main`). Framework-managed.
                      identity.properties   `<repository>/<coordinate>` ->
                                            path-or-empty.
                      requires.properties   `<group>/<scope>/<repository>/<coordinate>`
                                            -> empty or `<algo>/<hex>` checksum
                                            (pinned); scope rides in the key.
                      versions.properties   `<group>/<repository>/<coordinate>` ->
                                            `<version>[ <algo>/<hex>]`. Bill of
                                            materials for the resolution pass.
                      exclusions.properties `<group>/<scope>/<repository>/<coordinate>`
                                            -> comma-separated
                                            `<groupId>/<artifactId>` exclusions.
                      inventory.properties  Per-module summary used by staging
                                            (artifacts/sources/documentation/pom/
                                            runtime classpath, prefixed).
                    
                    Consult README's "Conventional folders and files" table for
                    the full schema.
                    
                    6. Address the graph with selectors
                    -----------------------------------
                    Selectors address points in the build graph:

                      build, stage, export, pin, dependencies, ide,
                      metadata, help, skill
                                            Top-level entry points.
                      ide[/idea|/vscode|/eclipse]
                                            Generate IDE project metadata at the
                                            project root from each module's
                                            inventory; drill into one tool with
                                            the sub-step name.
                      +<module>             Module subgraph inside `build` (does
                                            not run stage/export/pin). The
                                            <module> matches the source folder of
                                            the pom.xml / module-info.java; a
                                            nested folder uses `+` between segments
                                            (foo/bar -> +foo+bar).
                      +<module>/<step>      Drill into a specific step inside that
                                            module, e.g.
                                            +foo+bar/compile/dependencies/resolved.
                      :                     Single-segment wildcard
                                            (`build/:/java` matches every direct
                                            child's `java` step).
                      ::                    Multi-segment wildcard. Lenient: typos
                                            in a `::` tail silently match nothing,
                                            so verify selectors before assuming
                                            they ran something.
                    
                    7. Respect the cache model when editing build steps
                    ---------------------------------------------------
                    Every `BuildStep` is `Serializable`. The incremental cache
                    keys each step by:
                      1. the digest of its serialized form (fields plus the
                         class's `serialVersionUID`), AND
                      2. the checksums of every predecessor folder's contents.
                    
                    Project source changes are always detected. Changes to a
                    build step's *code* (the body of `apply(...)`, switched tool
                    flags, etc.) do NOT alter the serialized form, so cached
                    outputs are NOT invalidated. After such an edit, bump the
                    step class's `serialVersionUID` to force re-execution of
                    that step. Do not reach for `-Djenesis.executor.rebuild=true`
                    or delete `target/` by hand to work around this; let the
                    cache decide what to rebuild and only nudge it through
                    `serialVersionUID` when a step's code changes silently.
                    `-Djenesis.executor.rebuild=true` is appropriate only when
                    iterating on the build itself and a step's code change is
                    not yet reflected by a `serialVersionUID` bump, not as a
                    routine clean slate.
                    
                    8. Write Javadoc tags on module-info.java when configuring a
                       module
                    ------------------------------------------------------------
                      @jenesis.release <V>              Java release target.
                      @jenesis.main <class>             Main class for the module.
                      @jenesis.test [<module>]          Mark this module as a test
                                                        variant of <module>.
                      @jenesis.pin <group>/<repo>/<coord> <ver> [<algo>/<hex>] [<guard>]
                                                        Pin a dependency's version
                                                        and (optionally) its
                                                        content checksum. A bare
                                                        <module> abbreviates
                                                        <group>/module/<module>,
                                                        and <groupId>/<artifactId>
                                                        abbreviates
                                                        <group>/maven/<groupId>/<artifactId>.
                                                        An optional trailing
                                                        [<token>,<token>...] guard
                                                        applies the pin only when
                                                        those tokens are in the
                                                        active platform, with an
                                                        unguarded line for the same
                                                        coordinate as the fallback.
                    
                    9. Set system properties for one-off overrides
                    ----------------------------------------------
                    Project-level (-Djenesis.project.<key>=<value>):
                      root, target, artifacts     Override input/output locations.
                      layout                      auto, maven, modular,
                                                  modular_to_maven.
                      sources, documentation      Assemble sources / javadoc jars.
                      metadata                    Path-separated list of extra
                                                  metadata files.
                      version                     Stamp version onto every
                                                  produced artifact.
                      digest                      Algorithm for pin and
                                                  dependency checksums
                                                  (default SHA-256).
                      watch                       Rebuild the selected target
                                                  whenever a source file changes
                                                  (Ctrl+C to stop).
                    
                    Pinning:
                      -Djenesis.dependency.pin=strict|versions|ignore
                                                  strict fails on any unpinned
                                                  artifact; ignore floats to the
                                                  latest and skips checksums
                                                  (refresh pins via the pin step);
                                                  versions keeps pinned versions
                                                  but skips checksum verification.

                    Platform:
                      -Djenesis.platform.<token>=true|false  The active platform
                                                  starts from the detected OS and
                                                  chipset (windows/linux/macos plus
                                                  x86_64/aarch64); =true adds a
                                                  token and =false removes a
                                                  detected one, selecting which
                                                  platform-guarded pins apply.

                    Repositories:
                      -Djenesis.repository.insecure=true  Allow plaintext
                                                  (http://) fetches; by default
                                                  only https:// and file:// are
                                                  accepted, and a credential is
                                                  never forwarded across a
                                                  redirect to another host.
                      -Djenesis.maven.uri|local|token     Maven repository remote
                                                  URL, local cache and bearer token
                                                  (env fallbacks
                                                  MAVEN_REPOSITORY_URI/LOCAL/TOKEN).
                      -Djenesis.module.uri|local|token    Jenesis module repository,
                                                  likewise (env fallbacks
                                                  JENESIS_REPOSITORY_URI/LOCAL/TOKEN).

                    Build cache:
                      -Djenesis.cache.uri=<uri>           Reuse step outputs across
                                                  builds: a file:// URI is an on-disk
                                                  cache (tuned by a cache.properties
                                                  at its root), an http(s) URL a
                                                  remote server. For a server,
                                                  -Djenesis.cache.project and
                                                  -Djenesis.cache.key authorise it
                                                  (env fallbacks
                                                  JENESIS_CACHE_PROJECT/KEY);
                                                  -Djenesis.cache.timeout and
                                                  -Djenesis.cache.insecure tune it.
                      -Djenesis.project.cache=<path>      Also cache locally on disk,
                                                  layered in front of the remote
                                                  (empty resolves to .jenesis/cache
                                                  under the project root); a local
                                                  hit HEAD-touches the remote to keep
                                                  its LRU warm.

                    Executor-level:
                      -Djenesis.executor.rebuild=true   Wipe target/ before build.
                                                        Avoid setting this; rely
                                                        on the incremental cache
                                                        to recompute what
                                                        actually changed.
                      -Djenesis.executor.timeout=PT5M   Per-step timeout.
                      -Djenesis.executor.digest=<algo>  MessageDigest algorithm
                                                        for content and
                                                        serialization hashes
                                                        (default MD5).
                    
                    Printing (-Djenesis.print.<key>=<value>):
                      -Djenesis.print.progress=false      Suppress the build
                                                        progress lines
                                                        (default: true).
                      -Djenesis.print.checksum=true       Print each step's
                                                        input/output file
                                                        checksums.
                      -Djenesis.print.command=true        Print each external tool
                                                        command line as it runs.
                      -Djenesis.print.fetch=true          Print each artifact
                                                        downloaded from a
                                                        repository.
                      -Djenesis.print.cache=true          Print each step served
                                                        from or written to the
                                                        build cache.
                      -Djenesis.print.docker=false        Suppress the Docker
                                                        image notice when a
                                                        build/run is wrapped in
                                                        a container (default:
                                                        true).
                      -Djenesis.print.dependencies=true   Print each module's
                                                        dependency tree as it
                                                        resolves.
                    
                    Test execution (-Djenesis.test.<key>=<value>):
                      -Djenesis.test.skip=true            Skip test
                                                        execution.
                      -Djenesis.stage.tests=true          Stage test-variant
                                                        artifacts alongside main
                                                        artifacts.
                      -Djenesis.test.filter=<patterns>    Comma-separated
                                                        <classRegex>[#<method>]
                                                        entries restricting which
                                                        tests the default
                                                        InferredMultiProjectAssembler
                                                        executes. Changing the
                                                        value invalidates the test
                                                        step's cache and forces a
                                                        re-run.
                      -Djenesis.test.incremental          Re-run only the tests a
                                                        change can reach: a fast
                                                        feedback aid for watch
                                                        loops, not a correctness
                                                        gate. Static selection
                                                        cannot see reflection or
                                                        other indirect couplings,
                                                        so conclude a developed
                                                        change with a full test
                                                        run. The value names the
                                                        change-detection digest;
                                                        a bare flag picks MD5.

                    Tool execution:
                      -Djenesis.process.factory=fork      Fork JDK tools (jar,
                                                        javadoc, ...) into
                                                        separate processes instead
                                                        of invoking them
                                                        in-process. Use under
                                                        stricter sandboxes.
                    
                    10. Launch a built main class with the Execute companion
                    --------------------------------------------------------
                    To run a module's main class against the built artifacts, use
                    the companion launcher rather than wiring `java` yourself:
                    
                      java build/jenesis/Execute.java [args...]
                      jenesis-exec [args...]                    (installed CLI)
                    
                    Execute runs the build, scans inventories for a module with a
                    main class, and launches it on the resolved runtime
                    classpath / module path. If exactly one module declares a
                    main, it is picked implicitly; otherwise disambiguate with
                    `-Djenesis.execute.module=<path>`, the module's source folder
                    (a nested one as either `server/ui` or `server+ui`), and
                    `-Djenesis.execute.mainClass=<fqcn>`. Wrap the launched
                    program in Docker independently of the build with
                    `-Djenesis.execute.docker=true` and (optional)
                    `-Djenesis.execute.docker.image=<reference>` plus
                    `-Djenesis.execute.docker.mount` (read-only) /
                    `.mountWritable` (read-write) bind mounts and
                    `.env=<name[=value],...>` to forward host environment
                    variables. Execute is a
                    separate entry point: Project's `build`/`stage`/... selectors
                    do NOT apply to it, and its `jenesis.execute.*` properties do
                    NOT apply to plain `Project` invocations.
                    
                    11. Recommend pinning dependencies
                    ----------------------------------
                    Recommend pinning external dependencies so the build resolves
                    the same artifacts on every machine (and inside a native
                    image). When you add or change a dependency, offer to pin it.
                    The `pin` selector records resolved versions and content
                    checksums back into the build descriptor, idempotently:
                    
                      java build/jenesis/Project.java pin
                    
                    It writes pom.xml (`<dependencyManagement>` versions with
                    `<!--Checksum/<algo>/<hex>-->`, and qualified compiler closures
                    in a `<!--jenesis.pin ... -->` comment) or module-info.java
                    (`@jenesis.pin <group>/<repo>/<coord> <ver> [<algo>/<hex>] [<guard>]` tags), per layout.
                    A trailing `[<token>,...]` guard scopes a pin to a platform; the pin
                    step refreshes only the line matching the local platform and preserves
                    the rest. The same pins can be written by hand. Enforce coverage with
                    `-Djenesis.dependency.pin=strict`, which fails the build on
                    any unpinned artifact, or refresh them with
                    `-Djenesis.dependency.pin=ignore` and the `pin` step.
                    
                    12. Study a demo for a worked example
                    -------------------------------------
                    Before writing build configuration, read the demo that matches
                    the scenario; each is a minimal, self-contained, runnable
                    project, so copy its shape rather than inventing one:
                    
                      java-pom          POM layout: plain javac plus a pinned
                                        Maven dependency.
                      java-pom-multi    Multi-module POM (a library and a consumer
                                        module).
                      java-modular      MODULAR_TO_MAVEN layout: a pinned
                                        named-module dependency, emits a modular
                                        jar plus a generated POM.
                      java-modular-multi Multi-module MODULAR_TO_MAVEN (a library
                                        and a consumer requiring it plus an
                                        external named module).
                      kotlin/scala/     Mixed-language compiler chains; the
                      groovy            compiler closure resolves in its own
                                        group, isolated from the project's.
                      java-quality      Inferred code-quality tools turned on by
                                        a config file: Checkstyle, PMD, SpotBugs
                                        and a verifying formatter; the
                                        kotlin/scala/groovy-quality demos do the
                                        same per language.
                      code-coverage     Inferred test observation: JaCoCo records
                                        coverage during the test run, enabled
                                        with -Djenesis.observe.jacoco=true.
                      custom-assembler  Wrap `InferredMultiProjectAssembler` to
                                        preprocess sources before the regular flow.
                      custom-build      A hand-wired `BuildExecutor`, no `Project`,
                                        layout, or assembler (code generation step).
                      internal-module/  Load a build module (a `BuildExecutorModule`
                      external-module   plugin) from local source or a coordinate.
                    
                    They live under `demo/` in the repository, indexed by
                    `demo/README.md`, and online at
                    https://github.com/raphw/jenesis/tree/main/demo.
                    
                    13. Read further when stuck
                    ---------------------------
                    README.md (project root, and on the public repo) is the full
                    reference. Useful sections:
                    
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
                    
                    Online resources:
                      Source repository
                        https://github.com/raphw/jenesis
                      README (current main)
                        https://github.com/raphw/jenesis/blob/main/README.md
                      Issue tracker (bugs, questions, design discussion)
                        https://github.com/raphw/jenesis/issues
                      Releases (changelog, downloads, the matching git tag for
                      each published version)
                        https://github.com/raphw/jenesis/releases
                    
                    When stuck, read the source: every public type lives under
                    `sources/build/jenesis/` and is small enough to read
                    end-to-end. Tests under `tests/` double as executable
                    documentation for the public API.
                    
                    Run `help` for the same material with color, oriented at
                    humans.
                    """).replace("%{target}", target.toAbsolutePath().normalize().toString()));
        }
    }

    private record PinModule(Path root, String fileName, BiFunction<String, Path, BuildStep> stepFactory)
            implements BuildExecutorModule {

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
            SequencedSet<String> paths = new LinkedHashSet<>();
            for (Path folder : inherited.values()) {
                Path inventoryFile = folder.resolve(Inventory.INVENTORY);
                if (!Files.isRegularFile(inventoryFile)) {
                    continue;
                }
                SequencedProperties inventory = SequencedProperties.ofFiles(inventoryFile);
                for (String key : inventory.stringPropertyNames()) {
                    if (key.endsWith(".path")) {
                        paths.add(inventory.getProperty(key));
                    }
                }
            }
            for (String path : paths) {
                Path file = root.resolve(path).resolve(fileName);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                buildExecutor.addStep("module-" + BuildExecutorModule.encode(path),
                        stepFactory.apply(path, file),
                        new LinkedHashSet<>(inherited.sequencedKeySet()));
            }
        }
    }

    private record PomAwareAssembler(MultiProjectAssembler<? super ProjectModuleDescriptor> base,
                                     String manifests,
                                     String prefix,
                                     boolean resolved) implements MultiProjectAssembler<ProjectModuleDescriptor> {

        @Override
        public AssemblyDescriptor apply(ProjectModuleDescriptor descriptor,
                                        Map<String, Repository> repositories,
                                        Map<String, Resolver> resolvers) {
            return base.apply(descriptor.toInherited(), repositories, resolvers).mapBuild(delegate -> (sub, inherited) -> {
                sub.addModule("assemble", delegate, inherited.sequencedKeySet().stream());
                sub.addModule("describe", (describe, describeInherited) -> {
                            describe.addStep("pom", new Pom().resolved(resolved), describeInherited.sequencedKeySet().stream());
                            if (manifests != null) {
                                describe.addStep("identity", new MavenIdentity(prefix, manifests), "pom", manifests);
                            }
                        },
                        inherited.sequencedKeySet().stream());
            });
        }
    }

    private record MavenIdentity(String prefix, String manifests) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path pomFile = arguments.get("pom").folder().resolve("pom.xml");
            Path folder = arguments.get(manifests).folder();
            SequencedProperties metadata = SequencedProperties.ofFiles(folder.resolve(BuildStep.METADATA));
            String groupId = metadata.getProperty("project");
            String artifactId = metadata.getProperty("artifact");
            String version = metadata.getProperty("version");
            String module = SequencedProperties.ofFiles(folder.resolve(BuildStep.MODULE)).getProperty("module");
            String pom = context.next().relativize(pomFile).toString().replace(File.separatorChar, '/');
            SequencedProperties identity = new SequencedProperties();
            identity.setProperty("maven/" + groupId + "/" + artifactId + "/" + version, "");
            identity.setProperty("maven/" + groupId + "/" + artifactId + "/pom/" + version, pom);
            identity.setProperty(prefix + "/" + module + ":pom", pom);
            identity.store(context.next().resolve(BuildStep.IDENTITY));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    public Project() {
        Path resolvedRoot = Path.of(".");
        String rootOverride = System.getProperty("jenesis.project.root");
        if (rootOverride != null) {
            resolvedRoot = Path.of(rootOverride);
        }
        if (resolvedRoot.isAbsolute()) {
            Path absoluteCwd = Path.of("").toAbsolutePath().normalize();
            Path absoluteRoot = resolvedRoot.normalize();
            if (absoluteRoot.startsWith(absoluteCwd)) {
                Path relative = absoluteCwd.relativize(absoluteRoot);
                resolvedRoot = relative.toString().isEmpty() ? Path.of(".") : relative;
            }
        }
        String configurationOverride = System.getProperty("jenesis.project.configuration");
        Path resolvedConfiguration = configurationOverride == null
                ? resolvedRoot
                : resolvedRoot.resolve(Path.of(configurationOverride));
        Path resolvedTarget = Path.of("target");
        String targetOverride = System.getProperty("jenesis.project.target");
        if (targetOverride != null) {
            resolvedTarget = Path.of(targetOverride);
        }
        Path resolvedArtifacts = Path.of(".jenesis", "artifacts");
        String artifactsOverride = System.getProperty("jenesis.project.artifacts");
        if (artifactsOverride != null) {
            resolvedArtifacts = Path.of(artifactsOverride);
        }
        String cacheOverride = System.getProperty("jenesis.project.cache");
        if (cacheOverride != null && cacheOverride.contains("://")) {
            throw new IllegalArgumentException(
                    "jenesis.project.cache is a filesystem path, not a URI (use jenesis.cache.uri for a URI): " + cacheOverride);
        }
        BuildExecutorCache resolvedCache = cacheOverride == null
                ? null
                : new BuildExecutorFileCache(resolvedRoot.resolve(cacheOverride.isEmpty()
                        ? Path.of(".jenesis", "cache")
                        : Path.of(cacheOverride)));
        String layoutOverride = System.getProperty("jenesis.project.layout");
        Layout resolvedLayout = layoutOverride == null ? Layout.AUTO : switch (layoutOverride.toLowerCase(Locale.ROOT)) {
            case "auto" -> Layout.AUTO;
            case "maven" -> Layout.MAVEN;
            case "modular" -> Layout.MODULAR;
            case "modular_to_maven" -> Layout.MODULAR_TO_MAVEN;
            default -> throw new IllegalArgumentException(
                    "Unknown layout: " + layoutOverride + " (expected auto, maven, modular, or modular_to_maven)");
        };
        String metadataOverride = System.getProperty("jenesis.project.metadata");
        List<Path> resolvedMetadata = metadataOverride == null ? List.of() : Arrays.stream(
                        metadataOverride.split(Pattern.quote(File.pathSeparator)))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Path::of)
                .toList();
        this(resolvedRoot,
                resolvedConfiguration,
                resolvedTarget,
                resolvedArtifacts,
                resolvedCache,
                new HashDigestFunction(System.getProperty("jenesis.project.digest", "SHA-256")),
                resolvedLayout,
                true,
                Boolean.getBoolean("jenesis.project.sources"),
                Boolean.getBoolean("jenesis.project.documentation"),
                Pinning.fromProperty(),
                resolvedMetadata,
                System.getProperty("jenesis.project.version"),
                Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(BUILD))),
                new InferredMultiProjectAssembler(),
                Map.of(),
                Map.of(),
                BuildExecutor.Configuration::new);
    }

    public Project root(Path root) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project target(Path target) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project artifacts(Path artifacts) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project cache(BuildExecutorCache cache) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project hashFunction(HashDigestFunction hashFunction) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project layout(Layout layout) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project tests(boolean tests) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project sources(boolean sources) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project documentation(boolean documentation) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project pinning(Pinning pinning) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project metadata(Path... metadata) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                List.of(metadata),
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project version(String version) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project defaultTarget(String... defaultTarget) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(defaultTarget))),
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project assembler(MultiProjectAssembler<? super ProjectModuleDescriptor> assembler) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project repositories(Map<String, Repository> repositories) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project resolvers(Map<String, Resolver> resolvers) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public Project configurator(Supplier<BuildExecutor.Configuration> configurator) {
        return new Project(root,
                configuration,
                target,
                artifacts,
                cache,
                hashFunction,
                layout,
                tests,
                sources,
                documentation,
                pinning,
                metadata,
                version,
                defaultTarget,
                assembler,
                repositories,
                resolvers,
                configurator);
    }

    public SequencedMap<String, Path> build(String... selectors) throws IOException {
        return build(Boolean.getBoolean("jenesis.print.dependencies"), selectors);
    }

    public SequencedMap<String, Path> build(boolean printDependencies, String... selectors) throws IOException {
        BuildExecutor.Configuration configuration = configurator.get();
        if (cache != null) {
            BuildExecutorCache configured = configuration.cache();
            configuration = configuration.cache(configured == null
                    ? cache
                    : new BuildExecutorLayeredCache(cache, configured));
        }
        BuildExecutor executor = configuration.of(target);
        Function<String, String> resolver = layout.apply(executor, this, assembler, printDependencies);
        return executor.execute(Arrays.stream(selectors.length == 0 ? defaultTarget.toArray(String[]::new) : selectors)
                .map(selector -> selector.startsWith("+") ? resolver.apply(selector.substring(1)) : selector)
                .toArray(String[]::new));
    }

    private void watch(String... selectors) throws IOException {
        Path absoluteRoot = root().toAbsolutePath().normalize();
        Set<Path> excluded = new LinkedHashSet<>();
        excluded.add((target().isAbsolute() ? target() : absoluteRoot.resolve(target())).normalize());
        if (artifacts() != null) {
            excluded.add((artifacts().isAbsolute() ? artifacts() : absoluteRoot.resolve(artifacts())).normalize());
        }
        new ProjectWatch(absoluteRoot, excluded, 200L).watch(() -> {
            try {
                build(selectors);
            } catch (Exception e) {
                System.out.println("Build failed: " + e);
            }
        });
    }

    public static void loadJenesisProperties(Path path) throws IOException {
        Path base = path.resolve("jenesis.properties");
        SequencedProperties project = Files.isRegularFile(base) ? SequencedProperties.ofFiles(base) : null;
        String location = System.getProperty("jenesis.project.global");
        if (location == null && project != null) {
            location = project.getProperty("jenesis.project.global");
        }
        if (location == null) {
            location = System.getProperty("user.home");
        }
        SequencedProperties user = null;
        Path home = null;
        if (!location.isEmpty()) {
            home = Path.of(location).resolve(".jenesis");
            Path file = home.resolve("jenesis.properties");
            user = Files.isRegularFile(file) ? SequencedProperties.ofFiles(file) : null;
        }
        Set<Path> loaded = new LinkedHashSet<>();
        Deque<Path> pending = new ArrayDeque<>();
        addProfiles(pending, path, System.getProperty("jenesis.project.properties"));
        if (project != null) {
            addProfiles(pending, path, project.getProperty("jenesis.project.properties"));
        }
        loadProfiles(loaded, pending, path);
        if (user != null) {
            addProfiles(pending, home, user.getProperty("jenesis.project.properties"));
            loadProfiles(loaded, pending, home);
        }
        if (project != null) {
            apply(project);
        }
        if (user != null) {
            apply(user);
        }
    }

    private static void loadProfiles(Set<Path> loaded, Deque<Path> pending, Path base) throws IOException {
        while (!pending.isEmpty()) {
            Path file = pending.removeFirst().normalize();
            if (!loaded.add(file)) {
                continue;
            }
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Profile properties file not found: " + file);
            }
            SequencedProperties properties = SequencedProperties.ofFiles(file);
            addProfiles(pending, base, properties.getProperty("jenesis.project.properties"));
            apply(properties);
        }
    }

    private static void apply(SequencedProperties properties) {
        for (String name : properties.stringPropertyNames()) {
            System.getProperties().putIfAbsent(name, properties.getProperty(name));
        }
    }

    private static void addProfiles(Deque<Path> pending, Path base, String list) {
        if (list == null) {
            return;
        }
        for (String name : list.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                pending.add(base.resolve(trimmed.endsWith(".properties") ? trimmed : trimmed + ".properties"));
            }
        }
    }

    SequencedMap<String, Path> doMain(String... selectors) throws IOException, InterruptedException {
        if (selectors.length == 1 && selectors[0].equals(PROPERTIES)) {
            SortedMap<String, String> properties = new TreeMap<>();
            for (String name : System.getProperties().stringPropertyNames()) {
                if (name.startsWith("jenesis.")) {
                    properties.put(name, System.getProperty(name));
                }
            }
            properties.forEach((name, value) -> System.out.println(name + "=" + value));
            return new LinkedHashMap<>();
        }
        if (Boolean.getBoolean("jenesis.project.watch")) {
            watch(selectors);
            return new LinkedHashMap<>();
        }
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
            for (Path path : List.of(this.target(), this.artifacts())) {
                Path absolute = (path.isAbsolute() ? path : root.resolve(path)).normalize();
                if (!absolute.startsWith(root)) {
                    docker = docker.mount(absolute, absolute.toString(), false);
                }
            }
            docker = docker.mounts(System.getProperty("jenesis.project.docker.mount"), root, true);
            docker = docker.mounts(System.getProperty("jenesis.project.docker.mountWritable"), root, false);
            docker = docker.envs(System.getProperty("jenesis.project.docker.env"));
            String mavenRepositoryUri = System.getProperty("jenesis.maven.uri", System.getenv("MAVEN_REPOSITORY_URI"));
            if (mavenRepositoryUri != null) {
                docker = docker.env("MAVEN_REPOSITORY_URI", mavenRepositoryUri);
            }
            String jenesisRepositoryUri = System.getProperty("jenesis.module.uri", System.getenv("JENESIS_REPOSITORY_URI"));
            if (jenesisRepositoryUri != null) {
                docker = docker.env("JENESIS_REPOSITORY_URI", jenesisRepositoryUri);
            }
            String mavenRepositoryLocal = System.getProperty("jenesis.maven.local", System.getenv("MAVEN_REPOSITORY_LOCAL"));
            Path mavenLocal = (mavenRepositoryLocal == null
                    ? Path.of(System.getProperty("user.home"), ".m2", "repository")
                    : Path.of(mavenRepositoryLocal)).toAbsolutePath().normalize();
            if (Files.isDirectory(mavenLocal)) {
                docker = docker.mount(mavenLocal, mavenLocal.toString(), true);
                if (mavenRepositoryLocal != null) {
                    docker = docker.env("MAVEN_REPOSITORY_LOCAL", mavenLocal.toString());
                }
            }
            String jenesisRepositoryLocal = System.getProperty("jenesis.module.local", System.getenv("JENESIS_REPOSITORY_LOCAL"));
            Path jenesisLocal = (jenesisRepositoryLocal == null
                    ? Path.of(System.getProperty("user.home"), ".jenesis")
                    : Path.of(jenesisRepositoryLocal)).toAbsolutePath().normalize();
            if (Files.isDirectory(jenesisLocal)) {
                docker = docker.mount(jenesisLocal, jenesisLocal.toString(), true);
                if (jenesisRepositoryLocal != null) {
                    docker = docker.env("JENESIS_REPOSITORY_LOCAL", jenesisLocal.toString());
                }
            }
            if (Boolean.parseBoolean(System.getProperty("jenesis.print.docker", "true"))) {
                System.out.println("Launching build within Docker image: " + docker.image());
            }
            int code = docker.execute("build/jenesis/Project.java", properties, selectors);
            if (code != 0) {
                System.exit(code);
            }
            return new LinkedHashMap<>();
        }
        return this.build(selectors);
    }

    public static void main(String... selectors) {
        try {
            loadJenesisProperties(Path.of(System.getProperty("jenesis.project.root", ".")));
            new Project().doMain(selectors);
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
