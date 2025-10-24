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
                                           BiFunction<String, SequencedSet<String>, BuildExecutorModule> builder) {
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
                                           BiFunction<String, SequencedSet<String>, BuildExecutorModule> builder) {
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
                                           BiFunction<String, SequencedSet<String>, BuildExecutorModule> builder) {
        return new MultiProjectModule(new ModularProject(prefix, root, filter),
                identity -> Optional.of(identity.substring(0, identity.indexOf('/'))),
                _ -> (name, dependencies, _) -> (buildExecutor, inherited) -> {
                    buildExecutor.addStep("prepare",
                            new MultiProjectDependencies(
                                    algorithm,
                                    identifier -> identifier.startsWith(BuildExecutorModule.PREVIOUS.repeat(3)
                                            + "identify/"
                                            + name + "/")),
                            inherited.sequencedKeySet());
                    buildExecutor.addModule("dependencies",
                            new DependenciesModule(
                                    Repository.prepend(repositories,
                                            Repository.ofProperties(BuildStep.COORDINATES,
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
                            builder.apply(name, dependencies.sequencedKeySet()),
                            Stream.concat(inherited.sequencedKeySet().stream(), Stream.of("dependencies")));
                    buildExecutor.addStep("assign",
                            new Assign(),
                            Stream.concat(
                                    inherited.sequencedKeySet().stream().filter(identifier -> identifier
                                            .startsWith(PREVIOUS.repeat(3) + "identify/")),
                                    Stream.of("build")));
                });
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
                            module.addStep(MultiProjectModule.MODULE, (_, context, arguments) -> {
                                ModuleInfo info = parser.identify(arguments.get("sources").folder()
                                        .resolve(BuildStep.SOURCES)
                                        .resolve("module-info.java"));
                                Properties coordinates = new SequencedProperties();
                                coordinates.setProperty(prefix + "/" + info.coordinate(), "");
                                try (BufferedWriter writer = Files.newBufferedWriter(context
                                        .next()
                                        .resolve(BuildStep.COORDINATES))) {
                                    coordinates.store(writer, null);
                                }
                                Properties dependencies = new SequencedProperties();
                                for (String dependency : info.requires()) {
                                    dependencies.setProperty(prefix + "/" + dependency, "");
                                }
                                try (BufferedWriter writer = Files.newBufferedWriter(context
                                        .next()
                                        .resolve(BuildStep.DEPENDENCIES))) {
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
