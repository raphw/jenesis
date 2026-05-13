package build.jenesis;

import module java.base;
import build.jenesis.docker.DockerizedJava;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenProject;
import build.jenesis.maven.MavenRepositoryStage;
import build.jenesis.maven.MavenUriParser;
import build.jenesis.maven.Pom;
import build.jenesis.module.DownloadModuleUris;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularPlacement;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaModule;
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.step.Jar;
import build.jenesis.step.Javadoc;
import build.jenesis.step.Relocate;

public final class Project {

    public static final String BUILD = "build", COLLECT = "collect", STAGE = "stage";

    public record Context(
            boolean tests,
            boolean sources,
            boolean javadoc,
            Path root,
            String hashAlgorithm,
            Map<String, Repository> repositories,
            Map<String, Resolver> resolvers
    ) {
    }

    @FunctionalInterface
    public interface Assembler {

        BuildExecutorModule apply(Context context, ModuleDescriptor descriptor);

        static Assembler ofJava() {
            return (context, descriptor) -> (sub, _) -> {
                sub.addModule("java",
                        (executor, inherited) -> {
                            BuildExecutorModule delegate;
                            if (context.tests()) {
                                Properties metadata = new Properties();
                                Path manifestsDir = inherited.get(BuildExecutorModule.PREVIOUS + descriptor.manifests());
                                Path metadataFile = manifestsDir.resolve(BuildStep.METADATA);
                                if (Files.isRegularFile(metadataFile)) {
                                    try (Reader reader = Files.newBufferedReader(metadataFile)) {
                                        metadata.load(reader);
                                    }
                                }
                                boolean test = metadata.getProperty("project.test") != null;
                                delegate = new JavaModule().test(test, null, context.repositories(), context.resolvers());
                            } else {
                                delegate = new JavaModule();
                            }
                            delegate.accept(executor, inherited);
                        },
                        descriptor.sources(),
                        descriptor.manifests(),
                        descriptor.checked(),
                        descriptor.runtimeChecked(),
                        descriptor.artifacts(),
                        descriptor.runtimeArtifacts());
                if (context.sources()) {
                    sub.addStep("sources", Jar.tool(Jar.Sort.SOURCES), descriptor.sources());
                }
                if (context.javadoc()) {
                    sub.addModule("javadoc", (module, inherited) -> {
                        module.addStep("classes", Javadoc.tool(), inherited.sequencedKeySet().stream());
                        module.addStep("artifacts", Jar.tool(Jar.Sort.JAVADOC), "classes");
                    },
                    descriptor.sources(),
                    descriptor.manifests(),
                    descriptor.checked(),
                    descriptor.runtimeChecked(),
                    descriptor.artifacts(),
                    descriptor.runtimeArtifacts());
                }
            };
        }
    }

    @FunctionalInterface
    public interface Layout {

        Function<String, String> apply(BuildExecutor executor, Builder builder, Assembler assembler) throws IOException;

        Layout MAVEN = (executor, builder, assembler) -> {
            Map<String, Repository> repositories = new LinkedHashMap<>();
            repositories.put("maven", new MavenDefaultRepository());
            if (builder.repositories() != null) {
                repositories.putAll(builder.repositories());
            }
            Map<String, Resolver> resolvers = new LinkedHashMap<>();
            resolvers.put("maven", new MavenPomResolver());
            if (builder.resolvers() != null) {
                resolvers.putAll(builder.resolvers());
            }
            Context context = new Context(builder.tests(),
                    builder.sources(),
                    builder.javadoc(),
                    builder.root(),
                    builder.hashAlgorithm(),
                    Collections.unmodifiableMap(repositories),
                    Collections.unmodifiableMap(resolvers));
            Assembler wrapped = new PomAwareAssembler(assembler, builder);
            executor.addModule(BUILD, (sub, _) -> sub.addModule("maven",
                    MavenProject.make(builder.root(), builder.hashAlgorithm(),
                            descriptor -> wrapped.apply(context, descriptor))));
            executor.addStep(COLLECT, new Relocate(MultiProjectModule.artifactsByModule()), BUILD);
            executor.addStep(STAGE, new MavenRepositoryStage(builder.stageTests()), COLLECT);
            String prefix = BUILD + "/maven/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            return name -> prefix + "/module-" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        };

        Layout MODULAR = (executor, builder, assembler) -> {
            executor.addStep("download", new DownloadModuleUris());
            executor.addModule(BUILD, (sub, downloaded) -> {
                Map<String, Repository> repositories = new LinkedHashMap<>(Repository.ofProperties(
                        DownloadModuleUris.URIS,
                        downloaded.values(),
                        (_, value) -> URI.create(value),
                        Files.createDirectories(builder.cache().resolve("modules"))));
                if (builder.repositories() != null) {
                    repositories.putAll(builder.repositories());
                }
                Map<String, Resolver> resolvers = new LinkedHashMap<>();
                resolvers.put("module", new ModularJarResolver(true));
                if (builder.resolvers() != null) {
                    resolvers.putAll(builder.resolvers());
                }
                Context context = new Context(builder.tests(),
                        builder.sources(),
                        builder.javadoc(),
                        builder.root(),
                        builder.hashAlgorithm(),
                        Collections.unmodifiableMap(repositories),
                        Collections.unmodifiableMap(resolvers));
                sub.addModule("modules", ModularProject.make(builder.root(), builder.hashAlgorithm(),
                        context.repositories(), context.resolvers(),
                        descriptor -> assembler.apply(context, descriptor)));
            }, "download");
            executor.addStep(COLLECT, new Relocate(MultiProjectModule.artifactsByModule()), BUILD);
            executor.addStep(STAGE, new Relocate(new ModularPlacement(builder.stageTests())), COLLECT);
            String prefix = BUILD + "/modules/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            return name -> prefix + "/module-" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        };

        Layout MODULAR_TO_MAVEN = (executor, builder, assembler) -> {
            Assembler wrapped = new PomAwareAssembler(assembler, builder);
            executor.addStep("download", new DownloadModuleUris(null));
            executor.addModule(BUILD, (sub, downloaded) -> {
                Function<String, String> parser = MavenUriParser.ofUris(new MavenUriParser(),
                        DownloadModuleUris.URIS,
                        downloaded.values());
                Map<String, Repository> repositories = new LinkedHashMap<>();
                repositories.put("maven", new MavenDefaultRepository());
                if (builder.repositories() != null) {
                    repositories.putAll(builder.repositories());
                }
                Map<String, Resolver> resolvers = new LinkedHashMap<>();
                resolvers.put("module", new ModularJarResolver(false,
                        new MavenPomResolver().translated("maven",
                                (_, coordinate) -> parser.apply(coordinate))));
                resolvers.put("maven", new MavenPomResolver());
                if (builder.resolvers() != null) {
                    resolvers.putAll(builder.resolvers());
                }
                Context context = new Context(builder.tests(),
                        builder.sources(),
                        builder.javadoc(),
                        builder.root(),
                        builder.hashAlgorithm(),
                        Collections.unmodifiableMap(repositories),
                        Collections.unmodifiableMap(resolvers));
                sub.addModule("modules", ModularProject.make(builder.root(), builder.hashAlgorithm(),
                        context.repositories(), context.resolvers(),
                        descriptor -> wrapped.apply(context, descriptor)));
            }, "download");
            executor.addStep(COLLECT, new Relocate(MultiProjectModule.artifactsByModule()), BUILD);
            executor.addStep(STAGE, new MavenRepositoryStage(builder.stageTests()), COLLECT);
            String prefix = BUILD + "/modules/" + MultiProjectModule.COMPOSE + "/" + MultiProjectModule.MODULE;
            return name -> prefix + "/module-" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        };

        Layout AUTO = (executor, builder, assembler) -> of(builder.root()).apply(executor, builder, assembler);

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

    private static final class PomAwareAssembler implements Assembler { // Revisit this later and resolve as submodules.

        private final Assembler base;
        private final Pom pom;

        private PomAwareAssembler(Assembler base, Builder builder) throws IOException {
            this.base = base;
            Map<String, String> metadata;
            if (builder.metadata() == null) {
                metadata = Map.of();
            } else {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(builder.root().resolve(builder.metadata()))) {
                    properties.load(reader);
                }
                metadata = new LinkedHashMap<>();
                properties.stringPropertyNames().forEach(name -> metadata.put(name, properties.getProperty(name)));
            }
            this.pom = new Pom(metadata, (key -> key.contains("/" + MultiProjectModule.RUNTIME + "/")));
        }

        @Override
        public BuildExecutorModule apply(Context context, ModuleDescriptor descriptor) {
            BuildExecutorModule delegate = base.apply(context, descriptor);
            return new BuildExecutorModule() {
                @Override
                public Optional<String> resolve(String path) {
                    if (path.equals("pom") || path.startsWith("pom/")) {
                        return Optional.of(path);
                    }
                    return delegate.resolve(path);
                }

                @Override
                public void accept(BuildExecutor sub, SequencedMap<String, Path> inherited) throws IOException {
                    delegate.accept(sub, inherited);
                    sub.addStep("pom", pom,
                            descriptor.sources(),
                            descriptor.manifests(),
                            descriptor.checked(),
                            descriptor.runtimeChecked());
                }
            };
        }
    }

    public static void main(String... selectors) {
        try {
            Builder builder = builder().resolveProperties();
            if (Boolean.getBoolean("jenesis.project.docker")) {
                SortedMap<String, String> properties = new TreeMap<>();
                for (String name : System.getProperties().stringPropertyNames()) {
                    if (name.startsWith("jenesis.") && !name.startsWith("jenesis.project.docker")) {
                        properties.put(name, System.getProperty(name));
                    }
                }
                String image = System.getProperty("jenesis.project.docker.image");
                Path root = builder.root().toAbsolutePath().normalize();
                DockerizedJava docker = image == null ? new DockerizedJava(root) : new DockerizedJava(root, image);
                for (Path path : List.of(builder.target(), builder.cache())) {
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
                    System.out.println("Wrapping build within Docker image: " + docker.image());
                }
                System.exit(docker.execute("build/jenesis/Project.java", properties, selectors));
            }
            builder.build(selectors);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed using selectors " + List.of(selectors), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while wrapping build in container", e);
        }
    }

    public static Builder builder() {
        return new Builder(
                Path.of("."),
                Path.of("target"),
                Path.of("cache"),
                Layout.AUTO,
                "SHA256",
                true,
                false,
                false,
                false,
                null,
                Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(BUILD))),
                Assembler.ofJava(),
                null,
                null);
    }

    public record Builder(
            Path root,
            Path target,
            Path cache,
            Layout layout,
            String hashAlgorithm,
            boolean tests,
            boolean sources,
            boolean javadoc,
            boolean stageTests,
            Path metadata,
            SequencedSet<String> defaultTarget,
            Assembler assembler,
            Map<String, Repository> repositories,
            Map<String, Resolver> resolvers
    ) {

        public Builder root(Path root) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder target(Path target) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder cache(Path cache) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder layout(Layout layout) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder hashAlgorithm(String hashAlgorithm) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder tests(boolean tests) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder sources(boolean sources) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder javadoc(boolean javadoc) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder stageTests(boolean stageTests) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder metadata(Path metadata) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder defaultTarget(String... defaultTarget) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(defaultTarget))),
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder assembler(Assembler assembler) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder repositories(Map<String, Repository> repositories) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder resolvers(Map<String, Resolver> resolvers) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    sources,
                    javadoc,
                    stageTests,
                    metadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder resolveProperties() {
            Path resolvedRoot = root;
            Path resolvedTarget = target;
            Path resolvedCache = cache;
            Layout resolvedLayout = layout;
            String resolvedAlgorithm = hashAlgorithm;
            boolean resolvedTests = tests;
            boolean resolvedSources = sources;
            boolean resolvedJavadoc = javadoc;
            boolean resolvedStageTests = stageTests;
            Path resolvedMetadata = metadata;
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
            String algorithm = System.getProperty("jenesis.project.hashAlgorithm");
            if (algorithm != null) {
                resolvedAlgorithm = algorithm;
            }
            if (System.getProperty("jenesis.project.skipTests") != null) {
                resolvedTests = false;
            }
            if (Boolean.getBoolean("jenesis.project.sources")) {
                resolvedSources = true;
            }
            if (Boolean.getBoolean("jenesis.project.docs")) {
                resolvedJavadoc = true;
            }
            if (Boolean.getBoolean("jenesis.project.stageTests")) {
                resolvedStageTests = true;
            }
            String metadataOverride = System.getProperty("jenesis.project.metadata");
            if (metadataOverride != null) {
                resolvedMetadata = Path.of(metadataOverride);
            }
            return new Builder(resolvedRoot,
                    resolvedTarget,
                    resolvedCache,
                    resolvedLayout,
                    resolvedAlgorithm,
                    resolvedTests,
                    resolvedSources,
                    resolvedJavadoc,
                    resolvedStageTests,
                    resolvedMetadata,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public void build(String... selectors) throws IOException {
            BuildExecutor executor = BuildExecutor.of(target);
            Function<String, String> resolver = layout.apply(executor, this, assembler);
            executor.execute(Arrays.stream(selectors.length == 0 ? defaultTarget.toArray(String[]::new) : selectors)
                    .map(selector -> selector.startsWith("+") ? resolver.apply(selector.substring(1)) : selector)
                    .toArray(String[]::new));
        }
    }
}
