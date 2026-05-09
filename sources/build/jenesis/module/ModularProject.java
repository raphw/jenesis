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

    private static final String DEPENDENCIES = "dependencies",
            DEPENDENCIES_COMPILE = DEPENDENCIES + "-" + MultiProjectModule.COMPILE,
            DEPENDENCIES_RUNTIME = DEPENDENCIES + "-" + MultiProjectModule.RUNTIME,
            PREPARE_COMPILE = "prepare-" + MultiProjectModule.COMPILE,
            PREPARE_RUNTIME = "prepare-" + MultiProjectModule.RUNTIME;

    private final String prefix;
    private final Path root;
    private final Predicate<Path> filter;

    private final transient ModuleInfoParser parser = new ModuleInfoParser();

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
                    Map<String, Repository> mergedRepositories = Repository.prepend(repositories,
                            Repository.ofProperties(BuildStep.IDENTITY,
                                    inherited.entrySet().stream()
                                            .filter(entry ->
                                                    entry.getKey().startsWith(PREVIOUS + "module-")
                                                            && entry.getKey().endsWith("/assign"))
                                            .map(Map.Entry::getValue)
                                            .toList(),
                                    file -> Path.of(file).toUri(),
                                    null));
                    buildExecutor.addStep(PREPARE_COMPILE,
                            new MultiProjectDependencies(
                                    algorithm,
                                    identifier -> identifier.startsWith(MultiProjectModule.IDENTIFIER_PATH
                                            + name + "/"),
                                    MultiProjectModule.COMPILE),
                            inherited.sequencedKeySet());
                    buildExecutor.addModule(DEPENDENCIES_COMPILE,
                            new DependenciesModule(mergedRepositories, resolvers, true).computeChecksums(algorithm),
                            PREPARE_COMPILE);
                    buildExecutor.addStep(PREPARE_RUNTIME,
                            new MultiProjectDependencies(
                                    algorithm,
                                    identifier -> identifier.startsWith(MultiProjectModule.IDENTIFIER_PATH
                                            + name + "/"),
                                    MultiProjectModule.RUNTIME),
                            inherited.sequencedKeySet());
                    buildExecutor.addModule(DEPENDENCIES_RUNTIME,
                            new DependenciesModule(mergedRepositories, resolvers, false).computeChecksums(algorithm),
                            PREPARE_RUNTIME);
                    buildExecutor.addModule("build",
                            builder.apply(new ModularModuleDescriptor(name, dependencies.sequencedKeySet())),
                            Stream.concat(
                                            inherited.sequencedKeySet().stream(),
                                            Stream.of(
                                                    DEPENDENCIES_COMPILE + "/" + MultiProjectModule.CHECKED,
                                                    DEPENDENCIES_COMPILE + "/" + MultiProjectModule.ARTIFACTS,
                                                    DEPENDENCIES_RUNTIME + "/" + MultiProjectModule.CHECKED,
                                                    DEPENDENCIES_RUNTIME + "/" + MultiProjectModule.ARTIFACTS))
                                    .collect(Collectors.<String, String, String, LinkedHashMap<String, String>>toMap(
                                            Function.identity(),
                                            key -> switch (key) {
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + name + "/"
                                                        + MultiProjectModule.SOURCES) -> MultiProjectModule.SOURCES;
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + name + "/"
                                                        + MultiProjectModule.MANIFESTS) -> MultiProjectModule.MANIFESTS;
                                                case String value when value.startsWith(DEPENDENCIES_COMPILE + "/") ->
                                                        MultiProjectModule.COMPILE + "-"
                                                                + value.substring(DEPENDENCIES_COMPILE.length() + 1);
                                                case String value when value.startsWith(DEPENDENCIES_RUNTIME + "/") ->
                                                        MultiProjectModule.RUNTIME + "-"
                                                                + value.substring(DEPENDENCIES_RUNTIME.length() + 1);
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

    public static <T extends Function<Path, Optional<Path>> & Serializable> T artifactsByModule() {
        return MultiProjectModule.linkBySubModule("classes.jar", "pom.xml");
    }

    private static void writeRequires(Path folder,
                                      String scope,
                                      String prefix,
                                      SequencedSet<String> requires) throws IOException {
        Path target = Files.createDirectories(folder.resolve(scope));
        Properties properties = new SequencedProperties();
        for (String dependency : requires) {
            properties.setProperty(prefix + "/" + dependency, "");
        }
        try (BufferedWriter writer = Files.newBufferedWriter(target.resolve(BuildStep.REQUIRES))) {
            properties.store(writer, null);
        }
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
                                writeRequires(context.next(), MultiProjectModule.COMPILE, prefix, info.requires());
                                writeRequires(context.next(), MultiProjectModule.RUNTIME, prefix, info.runtimeRequires());
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
