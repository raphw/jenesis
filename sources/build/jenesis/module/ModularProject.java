package build.jenesis.module;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.project.DependenciesModule;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.step.Assign;
import build.jenesis.step.Bind;
import build.jenesis.step.Javac;

public class ModularProject implements BuildExecutorModule {

    private static final String DEPENDENCIES = "dependencies", PREPARE = "prepare";

    private final String prefix;
    private final Path root;
    private final Predicate<Path> filter;

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
                                    (folder, file) -> folder.resolve(file).normalize().toUri(),
                                    null));
                    for (Map.Entry<String, Boolean> entry : List.of(
                            Map.entry(BuildStep.COMPILE, true),
                            Map.entry(BuildStep.RUNTIME, false))) {
                        buildExecutor.addModule(entry.getKey(), (scopeExec, scopeInherited) -> {
                            scopeExec.addStep(PREPARE,
                                    new MultiProjectDependencies(
                                            algorithm,
                                            identifier -> identifier.contains("/" + MultiProjectModule.IDENTIFIER + "/" + name + "/"),
                                            entry.getKey()),
                                    scopeInherited.sequencedKeySet());
                            scopeExec.addModule(DEPENDENCIES,
                                    new DependenciesModule(mergedRepositories, resolvers, entry.getValue()).computeChecksums(algorithm),
                                    PREPARE);
                        }, inherited.sequencedKeySet());
                    }
                    buildExecutor.addModule("produce",
                            builder.apply(new ModularModuleDescriptor(name, dependencies.sequencedKeySet())),
                            Stream.concat(
                                            inherited.sequencedKeySet().stream(),
                                            Stream.of(
                                                    BuildStep.COMPILE + "/" + DEPENDENCIES + "/" + MultiProjectModule.CHECKED,
                                                    BuildStep.COMPILE + "/" + DEPENDENCIES + "/" + MultiProjectModule.ARTIFACTS,
                                                    BuildStep.RUNTIME + "/" + DEPENDENCIES + "/" + MultiProjectModule.CHECKED,
                                                    BuildStep.RUNTIME + "/" + DEPENDENCIES + "/" + MultiProjectModule.ARTIFACTS))
                                    .collect(Collectors.<String, String, String, LinkedHashMap<String, String>>toMap(
                                            Function.identity(),
                                            key -> switch (key) {
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + name + "/"
                                                        + MultiProjectModule.SOURCES) -> MultiProjectModule.SOURCES;
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + name + "/"
                                                        + MultiProjectModule.MANIFESTS) -> MultiProjectModule.MANIFESTS;
                                                default -> key;
                                            },
                                            (a, _) -> a,
                                            LinkedHashMap::new)));
                    buildExecutor.addStep("assign",
                            new Assign((BiFunction<Set<String>, SequencedSet<Path>, Map<String, Path>> & Serializable) ((coordinates, files) -> {
                                Path resolved = files.stream()
                                        .filter(file -> file.getFileName() != null
                                                && "classes.jar".equals(file.getFileName().toString()))
                                        .findFirst()
                                        .orElseThrow(() -> new IllegalArgumentException(
                                                "Expected a classes.jar artifact: " + files));
                                return coordinates.stream().collect(Collectors.toMap(
                                        Function.identity(),
                                        _ -> resolved));
                            })),
                            Stream.concat(
                                    inherited.sequencedKeySet().stream().filter(identifier -> identifier
                                            .startsWith(MultiProjectModule.IDENTIFIER_PATH)),
                                    Stream.of("produce")));
                });
    }

    private record Manifests(String prefix) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            ModuleInfo info = new ModuleInfoParser().identify(arguments.get("sources").folder()
                    .resolve(BuildStep.SOURCES)
                    .resolve("module-info.java"));
            Properties coordinates = new SequencedProperties();
            coordinates.setProperty(prefix + "/" + info.coordinate(), "");
            try (BufferedWriter writer = Files.newBufferedWriter(context
                    .next()
                    .resolve(BuildStep.IDENTITY))) {
                coordinates.store(writer, null);
            }
            if (info.testOf() != null) {
                if (!info.testOf().isEmpty() && !info.requires().contains(info.testOf())) {
                    throw new IllegalStateException("Test module '"
                            + info.coordinate()
                            + "' declares @tests "
                            + info.testOf()
                            + " but does not 'requires "
                            + info.testOf()
                            + ";' (declared requires: "
                            + info.requires()
                            + ")");
                }
                Properties module = new SequencedProperties();
                module.setProperty("tests", info.testOf());
                try (BufferedWriter writer = Files.newBufferedWriter(context
                        .next()
                        .resolve(BuildStep.MODULE))) {
                    module.store(writer, null);
                }
            }
            for (Map.Entry<String, SequencedSet<String>> entry : List.of(
                    Map.entry(BuildStep.COMPILE, info.requires()),
                    Map.entry(BuildStep.RUNTIME, info.runtimeRequires()))) {
                Path target = Files.createDirectories(context.next().resolve(entry.getKey()));
                Properties properties = new SequencedProperties();
                for (String dependency : entry.getValue()) {
                    properties.setProperty(prefix + "/" + dependency, "");
                }
                try (BufferedWriter writer = Files.newBufferedWriter(target.resolve(BuildStep.REQUIRES))) {
                    properties.store(writer, null);
                }
            }
            if (!info.versions().isEmpty()) {
                for (String scope : List.of(BuildStep.COMPILE, BuildStep.RUNTIME)) {
                    Path target = Files.createDirectories(context.next().resolve(scope));
                    Properties properties = new SequencedProperties();
                    info.versions().forEach((module, version) -> properties.setProperty(prefix + "/" + module, version));
                    try (BufferedWriter writer = Files.newBufferedWriter(target.resolve(BuildStep.VERSIONS))) {
                        properties.store(writer, null);
                    }
                }
            }
            Javac.writeRelease(context.next(), info.release());
            Properties metadata = new SequencedProperties();
            metadata.setProperty("project.module", info.coordinate());
            if (info.name() != null) {
                metadata.setProperty("project.name", info.name());
            }
            if (info.description() != null) {
                metadata.setProperty("project.description", info.description());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(BuildStep.METADATA))) {
                metadata.store(writer, null);
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
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
                            module.addStep(MultiProjectModule.MANIFESTS, new Manifests(prefix), "sources");
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
