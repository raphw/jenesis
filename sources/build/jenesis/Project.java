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
            METADATA = "metadata";

    @FunctionalInterface
    public interface Layout {

        Function<String, String> apply(BuildExecutor executor,
                                       Project project,
                                       MultiProjectAssembler<? super ProjectModuleDescriptor> assembler) throws IOException;

        Layout MAVEN = (executor, project, assembler) -> {
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            MultiProjectAssembler<? super ProjectModuleDescriptor> pomAware = new PomAwareAssembler(assembler);
            executor.addModule(BUILD, (sub, inherited) -> {
                SequencedSet<String> mavenDeps = new LinkedHashSet<>();
                inherited.sequencedKeySet().stream()
                        .filter(key -> key.startsWith(BuildExecutorModule.PREVIOUS + METADATA + "/"))
                        .forEach(mavenDeps::add);
                sub.addModule("maven", MavenProject.make(project.root(),
                                (descriptor, repositories, resolvers) -> pomAware.apply(
                                        new ProjectModuleDescriptor(descriptor,
                                                project.tests(),
                                                project.sources(),
                                                project.documentation()),
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
            return name -> prefix + "/module-" + BuildExecutorModule.encode(name);
        };

        Layout MODULAR = (executor, project, assembler) -> {
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            executor.addStep("download", new DownloadModuleUris());
            executor.addModule(BUILD, (sub, inherited) -> {
                Map<String, Repository> repositories = new LinkedHashMap<>(Repository.ofProperties(
                        DownloadModuleUris.URIS,
                        inherited.values(),
                        (_, value) -> URI.create(value),
                        MavenDefaultRepository.versionResolver(),
                        Files.createDirectories(project.cache().resolve("modules"))));
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
                        (descriptor, mergedRepos, mergedResolvers) -> assembler.apply(
                                new ProjectModuleDescriptor(descriptor,
                                        project.tests(),
                                        project.sources(),
                                        project.documentation()),
                                mergedRepos,
                                mergedResolvers)),
                        modulesDeps);
            }, "download", METADATA);
            executor.addStep(STAGE, new ModularStaging(project.stageTests()), BUILD);
            String prefix = BUILD + "/modules/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            HashDigestFunction hashFunction = new HashDigestFunction(
                    System.getProperty("jenesis.project.pinAlgorithm", "SHA-256"));
            executor.addModule(PIN, new PinModule(project.root(), "module-info.java",
                    file -> new PinModuleInfo("module", file, hashFunction)), BUILD);
            return name -> prefix + "/module-" + BuildExecutorModule.encode(name);
        };

        Layout MODULAR_TO_MAVEN = (executor, project, assembler) -> {
            executor.addModule(METADATA, MetadataModule.toMetadataModule(project));
            MavenPomResolver resolver = new MavenPomResolver();
            MultiProjectAssembler<? super ProjectModuleDescriptor> pomAware = new PomAwareAssembler(assembler);
            executor.addStep("download", new DownloadModuleUris(null));
            executor.addModule(BUILD, (sub, inherited) -> {
                Function<String, String> parser = MavenUriParser.ofUris(new MavenUriParser(),
                        DownloadModuleUris.URIS,
                        inherited.values());
                Map<String, Repository> repositories = new LinkedHashMap<>();
                repositories.put("maven", new MavenDefaultRepository());
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
                                (descriptor, mergedRepos, mergedResolvers) -> pomAware.apply(
                                        new ProjectModuleDescriptor(descriptor,
                                                project.tests(),
                                                project.sources(),
                                                project.documentation()),
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
            return name -> prefix + "/module-" + BuildExecutorModule.encode(name);
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
                Path.of("cache"),
                Layout.AUTO,
                true,
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
        } catch (IOException e) {
            throw new UncheckedIOException("Failed using selectors " + List.of(selectors), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while wrapping build in container", e);
        }
    }
}
