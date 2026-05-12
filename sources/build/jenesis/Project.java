package build.jenesis;

import module java.base;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenProject;
import build.jenesis.maven.MavenUriParser;
import build.jenesis.maven.Pom;
import build.jenesis.module.DownloadModuleUris;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaModule;
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.step.Relocate;

public final class Project {

    public static final String BUILD = "build";

    public record Context(
            boolean tests,
            String hashAlgorithm,
            Map<String, Repository> repositories,
            Map<String, Resolver> resolvers
    ) {
    }

    @FunctionalInterface
    public interface Assembler {

        BuildExecutorModule apply(Context context, ModuleDescriptor descriptor);

        static Assembler ofJava() {
            return (context, descriptor) -> (sub, _) -> sub.addModule("java",
                    context.tests()
                            ? new JavaModule().testIfAvailable(context.repositories(), context.resolvers())
                            : new JavaModule(),
                    descriptor.sources(), descriptor.manifests(),
                    descriptor.checked(), descriptor.runtimeChecked(),
                    descriptor.artifacts(), descriptor.runtimeArtifacts());
        }
    }

    @FunctionalInterface
    public interface Layout {

        void apply(BuildExecutor executor, Builder builder, Assembler assembler) throws IOException;

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
            Context context = new Context(builder.tests(), builder.hashAlgorithm(),
                    Collections.unmodifiableMap(repositories),
                    Collections.unmodifiableMap(resolvers));
            executor.addModule(BUILD, MavenProject.make(builder.root(), builder.hashAlgorithm(),
                    descriptor -> assembler.apply(context, descriptor)));
            executor.addStep("collect", new Relocate(MavenProject.artifactsByModule()), BUILD);
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
                Context context = new Context(builder.tests(), builder.hashAlgorithm(),
                        Collections.unmodifiableMap(repositories),
                        Collections.unmodifiableMap(resolvers));
                sub.addModule("modules", ModularProject.make(builder.root(), builder.hashAlgorithm(),
                        context.repositories(), context.resolvers(),
                        descriptor -> assembler.apply(context, descriptor)));
            }, "download");
            executor.addStep("collect", new Relocate(ModularProject.artifactsByModule()), BUILD);
        };

        Layout MODULE_AWARE_MAVEN = (executor, builder, assembler) -> {
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
                if (builder.resolvers() != null) {
                    resolvers.putAll(builder.resolvers());
                }
                Context context = new Context(builder.tests(), builder.hashAlgorithm(),
                        Collections.unmodifiableMap(repositories),
                        Collections.unmodifiableMap(resolvers));
                Assembler wrapped = (innerContext, descriptor) -> {
                    BuildExecutorModule base = assembler.apply(innerContext, descriptor);
                    return (inner, inherited) -> {
                        base.accept(inner, inherited);
                        inner.addStep("pom", new Pom(),
                                descriptor.sources(), descriptor.manifests(), descriptor.checked());
                    };
                };
                sub.addModule("modules", ModularProject.make(builder.root(), builder.hashAlgorithm(),
                        context.repositories(), context.resolvers(),
                        descriptor -> wrapped.apply(context, descriptor)));
            }, "download");
            executor.addStep("collect", new Relocate(ModularProject.artifactsByModule()), BUILD);
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
            if (!moduleInfos.isEmpty()) {
                return MODULAR;
            }
            if (Files.isRegularFile(root.resolve("pom.xml"))) {
                return MAVEN;
            }
            throw new IllegalStateException(
                    "No build descriptor found under " + root.toAbsolutePath()
                            + " (expected a module-info.java or a pom.xml)");
        }
    }

    public static void main(String... selectors) {
        try {
            builder().resolveProperties().build(selectors);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed using selectors " + List.of(selectors), e);
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
            SequencedSet<String> defaultTarget,
            Assembler assembler,
            Map<String, Repository> repositories,
            Map<String, Resolver> resolvers
    ) {

        public Builder root(Path root) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder target(Path target) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder cache(Path cache) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder layout(Layout layout) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder hashAlgorithm(String hashAlgorithm) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder tests(boolean tests) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder defaultTarget(String... defaultTarget) {
            return new Builder(root,
                    target,
                    cache,
                    layout,
                    hashAlgorithm,
                    tests,
                    Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(defaultTarget))),
                    assembler,
                    repositories,
                    resolvers);
        }

        public Builder assembler(Assembler assembler) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder repositories(Map<String, Repository> repositories) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder resolvers(Map<String, Resolver> resolvers) {
            return new Builder(root, target, cache, layout, hashAlgorithm, tests, defaultTarget, assembler, repositories, resolvers);
        }

        public Builder resolveProperties() {
            Path resolvedRoot = root;
            Path resolvedTarget = target;
            Path resolvedCache = cache;
            Layout resolvedLayout = layout;
            String resolvedAlgorithm = hashAlgorithm;
            boolean resolvedTests = tests;
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
                    case "module_aware_maven" -> Layout.MODULE_AWARE_MAVEN;
                    default -> throw new IllegalArgumentException(
                            "Unknown layout: " + forced + " (expected auto, maven, modular, or module_aware_maven)");
                };
            }
            String algorithm = System.getProperty("jenesis.project.hashAlgorithm");
            if (algorithm != null) {
                resolvedAlgorithm = algorithm;
            }
            if (System.getProperty("jenesis.project.skipTests") != null) {
                resolvedTests = false;
            }
            return new Builder(resolvedRoot,
                    resolvedTarget,
                    resolvedCache,
                    resolvedLayout,
                    resolvedAlgorithm,
                    resolvedTests,
                    defaultTarget,
                    assembler,
                    repositories,
                    resolvers);
        }

        public void build(String... selectors) throws IOException {
            BuildExecutor executor = BuildExecutor.of(target);
            layout.apply(executor, this, assembler);
            executor.execute(selectors.length == 0 ? defaultTarget.toArray(String[]::new) : selectors);
        }
    }
}
