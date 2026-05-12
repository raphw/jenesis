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
import build.jenesis.step.Relocate;

public final class Project {

    public enum Kind {

        AUTO, MAVEN, MODULAR, MODULE_AWARE_MAVEN;

        public static Kind of(Path root) throws IOException {
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
            return AUTO;
        }
    }

    public static void main(String... selectors) {
        try {
            builder().build(selectors);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed using selectors " + List.of(selectors), e);
        }
    }

    public static Builder builder() {
        return new Builder(
                Path.of("."),
                Path.of("target"),
                Path.of("cache"),
                Kind.AUTO,
                "SHA256",
                true,
                null,
                null);
    }


    public record Builder(
            Path root,
            Path target,
            Path cache,
            Kind kind,
            String hashAlgorithm,
            boolean tests,
            Map<String, Repository> repositories,
            Map<String, Resolver> resolvers
    ) {

        public Builder root(Path root) {
            return new Builder(root, target, cache, kind, hashAlgorithm, tests, repositories, resolvers);
        }

        public Builder target(Path target) {
            return new Builder(root, target, cache, kind, hashAlgorithm, tests, repositories, resolvers);
        }

        public Builder cache(Path cache) {
            return new Builder(root, target, cache, kind, hashAlgorithm, tests, repositories, resolvers);
        }

        public Builder force(Kind kind) {
            return new Builder(root, target, cache, kind, hashAlgorithm, tests, repositories, resolvers);
        }

        public Builder hashAlgorithm(String hashAlgorithm) {
            return new Builder(root, target, cache, kind, hashAlgorithm, tests, repositories, resolvers);
        }

        public Builder tests(boolean tests) {
            return new Builder(root, target, cache, kind, hashAlgorithm, tests, repositories, resolvers);
        }

        public Builder repositories(Map<String, Repository> repositories) {
            return new Builder(root, target, cache, kind, hashAlgorithm, tests, repositories, resolvers);
        }

        public Builder resolvers(Map<String, Resolver> resolvers) {
            return new Builder(root, target, cache, kind, hashAlgorithm, tests, repositories, resolvers);
        }

        public Builder resolveProperties() {
            Path resolvedRoot = root;
            Path resolvedTarget = target;
            Path resolvedCache = cache;
            Kind resolvedKind = kind;
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
            String forced = System.getProperty("jenesis.project.kind");
            if (forced != null) {
                resolvedKind = Kind.valueOf(forced.toUpperCase(Locale.ROOT));
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
                    resolvedKind,
                    resolvedAlgorithm,
                    resolvedTests,
                    repositories,
                    resolvers);
        }

        public void build(String... selectors) throws IOException {
            resolveProperties().runBuild(selectors);
        }

        private void runBuild(String... selectors) throws IOException {
            BuildExecutor executor = BuildExecutor.of(target);
            switch ((kind == Kind.AUTO) ? Kind.of(root) : kind) {
                case MAVEN -> {
                    executor.addModule("build", MavenProject.make(root, hashAlgorithm,
                            descriptor -> (sub, _) -> sub.addModule("java",
                                    tests ? new JavaModule().testIfAvailable() : new JavaModule(),
                                    descriptor.sources(), descriptor.manifests(),
                                    descriptor.artifacts(), descriptor.runtimeArtifacts())));
                    executor.addStep("collect", new Relocate(MavenProject.artifactsByModule()), "build");
                }
                case MODULAR -> {
                    executor.addStep("download", new DownloadModuleUris());
                    executor.addModule("build", (sub, downloaded) -> {
                        Map<String, Repository> effectiveRepositories = (repositories != null)
                                ? repositories
                                : Repository.ofProperties(DownloadModuleUris.URIS,
                                downloaded.values(),
                                (_, value) -> URI.create(value),
                                Files.createDirectories(cache.resolve("modules")));
                        Map<String, Resolver> effectiveResolvers = (resolvers != null)
                                ? resolvers
                                : Map.of("module", new ModularJarResolver(true));
                        sub.addModule("modules", ModularProject.make(root, hashAlgorithm,
                                effectiveRepositories, effectiveResolvers,
                                descriptor -> (inner, _) -> inner.addModule("java",
                                        tests
                                                ? new JavaModule().testIfAvailable(effectiveRepositories, effectiveResolvers)
                                                : new JavaModule(),
                                        descriptor.sources(), descriptor.manifests(),
                                        descriptor.checked(), descriptor.runtimeChecked(),
                                        descriptor.artifacts(), descriptor.runtimeArtifacts())));
                    }, "download");
                    executor.addStep("collect", new Relocate(ModularProject.artifactsByModule()), "build");
                }
                case MODULE_AWARE_MAVEN -> {
                    Map<String, Repository> effectiveRepositories = (repositories != null)
                            ? repositories
                            : Map.of("maven", new MavenDefaultRepository());
                    executor.addStep("download", new DownloadModuleUris(null));
                    executor.addModule("build", (sub, downloaded) -> {
                        Function<String, String> parser = MavenUriParser.ofUris(new MavenUriParser(),
                                DownloadModuleUris.URIS,
                                downloaded.values());
                        Map<String, Resolver> effectiveResolvers = (resolvers != null)
                                ? resolvers
                                : Map.of("module", new ModularJarResolver(false,
                                new MavenPomResolver().translated("maven",
                                        (_, coordinate) -> parser.apply(coordinate))));
                        sub.addModule("modules", ModularProject.make(root, hashAlgorithm,
                                effectiveRepositories, effectiveResolvers,
                                descriptor -> (inner, _) -> {
                                    inner.addModule("java",
                                            tests
                                                    ? new JavaModule().testIfAvailable(effectiveRepositories, effectiveResolvers)
                                                    : new JavaModule(),
                                            descriptor.sources(), descriptor.manifests(),
                                            descriptor.artifacts(), descriptor.runtimeArtifacts());
                                    inner.addStep("pom", new Pom(),
                                            descriptor.sources(), descriptor.manifests(), descriptor.checked());
                                }));
                    }, "download");
                    executor.addStep("collect", new Relocate(ModularProject.artifactsByModule()), "build");
                }
                case AUTO -> throw new IllegalStateException(
                        "No build descriptor found under " + root.toAbsolutePath()
                                + " (expected a module-info.java or a pom.xml)");
            }
            executor.execute(selectors);
        }
    }
}
