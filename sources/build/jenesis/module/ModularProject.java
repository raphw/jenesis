package build.jenesis.module;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.project.DependenciesModule;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.step.Assign;
import build.jenesis.step.Bind;

import module java.base;

public class ModularProject implements BuildExecutorModule {

    private static final String DEPENDENCIES = "dependencies";

    private final String prefix;
    private final Path root;
    private final Predicate<Path> filter;

    private final ModuleInfoParser parser = new ModuleInfoParser();

    public ModularProject(String prefix, Path root, Predicate<Path> filter) {
        this.prefix = prefix;
        this.root = root;
        this.filter = filter;
    }

    public static BuildExecutorModule make(Path root,
                                           String algorithm,
                                           Map<String, Repository> repositories,
                                           Function<ModularModuleDescriptor, BuildExecutorModule> builder) {
        return make(root,
                algorithm,
                repositories,
                Map.of("module", new ModularJarResolver(true)),
                builder);
    }

    public static BuildExecutorModule make(Path root,
                                           String algorithm,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           Function<ModularModuleDescriptor, BuildExecutorModule> builder) {
        return make(root,
                "module",
                _ -> true,
                algorithm,
                repositories,
                resolvers,
                builder);
    }

    public static BuildExecutorModule make(Path root,
                                           String prefix,
                                           Predicate<Path> filter,
                                           String algorithm,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           Function<ModularModuleDescriptor, BuildExecutorModule> builder) {
        return new MultiProjectModule(new ModularProject(prefix, root, filter),
                identity -> Optional.of(identity.substring(0, identity.indexOf('/'))),
                _ -> (name, dependencies, _) -> (buildExecutor, inherited) -> {
                    buildExecutor.addStep("prepare",
                            new MultiProjectDependencies(
                                    algorithm,
                                    identifier -> identifier.startsWith(MultiProjectModule.IDENTIFIER_PATH
                                            + name + "/")),
                            inherited.sequencedKeySet());
                    buildExecutor.addModule(DEPENDENCIES,
                            new DependenciesModule(
                                    Repository.prepend(repositories,
                                            Repository.ofProperties(BuildStep.IDENTITY,
                                                    inherited.entrySet().stream()
                                                            .filter(entry ->
                                                                    entry.getKey().startsWith(PREVIOUS + "module-")
                                                                            && entry.getKey().endsWith("/assign"))
                                                            .map(Map.Entry::getValue)
                                                            .toList(),
                                                    file -> Path.of(file).toUri(),
                                                    null)),
                                    resolvers).computeChecksums(algorithm),
                            "prepare");
                    buildExecutor.addModule("build",
                            builder.apply(new ModularModuleDescriptor(name, dependencies.sequencedKeySet())),
                            Stream.concat(
                                            inherited.sequencedKeySet().stream(),
                                            Stream.of(DEPENDENCIES + "/" + MultiProjectModule.CHECKED,
                                                    DEPENDENCIES + "/" + MultiProjectModule.ARTIFACTS))
                                    .collect(Collectors.<String, String, String, LinkedHashMap<String, String>>toMap(
                                            Function.identity(),
                                            key -> switch (key) {
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + name + "/"
                                                        + MultiProjectModule.SOURCES) -> MultiProjectModule.SOURCES;
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + name + "/"
                                                        + MultiProjectModule.MANIFESTS) -> MultiProjectModule.MANIFESTS;
                                                case String value when value.startsWith(DEPENDENCIES + "/") -> value.substring(DEPENDENCIES.length() + 1);
                                                default -> key;
                                            },
                                            (a, _) -> a,
                                            LinkedHashMap::new)));
                    buildExecutor.addStep("assign",
                            new Assign(),
                            Stream.concat(
                                    inherited.sequencedKeySet().stream().filter(identifier -> identifier
                                            .startsWith(MultiProjectModule.IDENTIFIER_PATH)),
                                    Stream.of("build")));
                });
    }

    public static Function<Path, Optional<Path>> artifactsByModule() {
        return MultiProjectModule.linkBySubModule("classes.jar", "pom.xml");
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("module-info.java")) {
                    Path parent = file.getParent(), location = root.relativize(parent);
                    if (filter.test(location)) {
                        buildExecutor.addModule("module-" + URLEncoder.encode(
                                location.toString(),
                                StandardCharsets.UTF_8), (module, _) -> {
                            module.addSource("sources", Bind.asSources(), parent);
                            module.addStep(MultiProjectModule.MANIFESTS, (_, context, arguments) -> {
                                ModuleInfo info = parser.identify(arguments.get("sources").folder()
                                        .resolve(BuildStep.SOURCES)
                                        .resolve("module-info.java"));
                                Properties coordinates = new SequencedProperties();
                                coordinates.setProperty(prefix + "/" + info.coordinate(), "");
                                try (BufferedWriter writer = Files.newBufferedWriter(context
                                        .next()
                                        .resolve(BuildStep.IDENTITY))) {
                                    coordinates.store(writer, null);
                                }
                                Properties dependencies = new SequencedProperties();
                                for (String dependency : info.requires()) {
                                    dependencies.setProperty(prefix + "/" + dependency, "");
                                }
                                try (BufferedWriter writer = Files.newBufferedWriter(context
                                        .next()
                                        .resolve(BuildStep.REQUIRES))) {
                                    dependencies.store(writer, null);
                                }
                                return CompletableFuture.completedStage(new BuildStepResult(true));
                            }, "sources");
                        });
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.exists(dir.resolve(BuildExecutor.BUILD_MARKER))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
