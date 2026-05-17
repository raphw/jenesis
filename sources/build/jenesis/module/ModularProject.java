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
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.project.DependencyScope;
import build.jenesis.step.Assign;
import build.jenesis.step.Bind;
import build.jenesis.step.Javac;

public class ModularProject implements BuildExecutorModule {

    private static final String DEPENDENCIES = "dependencies", PREPARE = "prepare";
    private static final String ASSIGN = "assign", PRODUCE = "produce";
    private static final String SOURCES = "sources", MANIFESTS = "manifests";
    private static final String SIBLING_MODULE_PREFIX = MultiProjectModule.MODULE + "-";

    private final String prefix;
    private final Path root;
    private final Predicate<Path> filter;

    public ModularProject(String prefix, Path root, Predicate<Path> filter) {
        this.prefix = prefix;
        this.root = root;
        this.filter = filter;
    }

    public static <F extends Function<Path, Optional<Path>> & Serializable> F artifactsByModule() {
        return MultiProjectModule.linkBySubModule("classes.jar", "sources.jar", "javadoc.jar",
                BuildStep.METADATA, BuildStep.IDENTITY, BuildStep.MODULE);
    }

    public static BuildExecutorModule make(Path root,
                                           Map<String, Repository> repositories,
                                           MultiProjectAssembler<? super ModularModuleDescriptor> assembler) {
        return make(root,
                repositories,
                Map.of("module", new ModularJarResolver(true)),
                assembler);
    }

    public static BuildExecutorModule make(Path root,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           MultiProjectAssembler<? super ModularModuleDescriptor> assembler) {
        return make(root,
                "module",
                _ -> true,
                repositories,
                resolvers,
                assembler);
    }

    public static BuildExecutorModule make(Path root,
                                           String prefix,
                                           Predicate<Path> filter,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           MultiProjectAssembler<? super ModularModuleDescriptor> assembler) {
        return new MultiProjectModule(new ModularProject(prefix, root, filter),
                identity -> Optional.of(identity.substring(0, identity.indexOf('/'))),
                _ -> (name, dependencies, _) -> (buildExecutor, inherited) -> {
                    Map<String, Repository> mergedRepositories = Repository.prepend(repositories,
                            Repository.ofProperties(BuildStep.IDENTITY,
                                    inherited.entrySet().stream()
                                            .filter(entry ->
                                                    entry.getKey().startsWith(PREVIOUS + SIBLING_MODULE_PREFIX)
                                                            && entry.getKey().endsWith("/" + ASSIGN))
                                            .map(Map.Entry::getValue)
                                            .toList(),
                                    (folder, file) -> folder.resolve(file).normalize().toUri(),
                                    null));
                    for (DependencyScope scope : DependencyScope.values()) {
                        buildExecutor.addModule(scope.label(), (scopeExec, scopeInherited) -> {
                            scopeExec.addStep(PREPARE,
                                    new MultiProjectDependencies(
                                            identifier -> identifier.contains("/" + MultiProjectModule.IDENTIFIER + "/" + name + "/"),
                                            scope),
                                    scopeInherited.sequencedKeySet());
                            scopeExec.addModule(DEPENDENCIES,
                                    new DependenciesModule(mergedRepositories, resolvers, scope == DependencyScope.COMPILE),
                                    PREPARE);
                        }, inherited.sequencedKeySet());
                    }
                    buildExecutor.addModule(PRODUCE,
                            assembler.apply(new ModularModuleDescriptor(name, dependencies.sequencedKeySet()),
                                    mergedRepositories,
                                    resolvers),
                            Stream.concat(
                                            inherited.sequencedKeySet().stream(),
                                            Stream.of(
                                                    DependencyScope.COMPILE.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.RESOLVED,
                                                    DependencyScope.COMPILE.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS,
                                                    DependencyScope.RUNTIME.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.RESOLVED,
                                                    DependencyScope.RUNTIME.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS))
                                    .collect(Collectors.<String, String, String, LinkedHashMap<String, String>>toMap(
                                            Function.identity(),
                                            key -> switch (key) {
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + name + "/"
                                                        + SOURCES) -> SOURCES;
                                                case String value when value.equals(MultiProjectModule.IDENTIFIER_PATH
                                                        + name + "/"
                                                        + MANIFESTS) -> MANIFESTS;
                                                default -> key;
                                            },
                                            (a, _) -> a,
                                            LinkedHashMap::new)));
                    buildExecutor.addStep(ASSIGN,
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
                                    Stream.of(PRODUCE)));
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
            Properties requires = new SequencedProperties();
            Properties scopes = new SequencedProperties();
            for (String dependency : info.requires()) {
                String key = prefix + "/" + dependency;
                requires.setProperty(key, "");
                scopes.setProperty(key, info.runtimeRequires().contains(dependency)
                        ? DependencyScope.COMPILE.name() + "," + DependencyScope.RUNTIME.name()
                        : DependencyScope.COMPILE.name());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(BuildStep.REQUIRES))) {
                requires.store(writer, null);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(BuildStep.SCOPES))) {
                scopes.store(writer, null);
            }
            if (!info.versions().isEmpty()) {
                Properties properties = new SequencedProperties();
                info.versions().forEach((module, version) -> properties.setProperty(prefix + "/" + module, version));
                try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(BuildStep.VERSIONS))) {
                    properties.store(writer, null);
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
                        buildExecutor.addModule(SIBLING_MODULE_PREFIX + URLEncoder.encode(
                                location.toString(),
                                StandardCharsets.UTF_8), (module, _) -> {
                            module.addSource("sources", Bind.asSources(), parent);
                            module.addStep(MANIFESTS, new Manifests(prefix), "sources");
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

    public record ModularModuleDescriptor(String name, SequencedSet<String> dependencies) implements ModuleDescriptor {

        @Override
        public String sources() {
            return BuildExecutorModule.PREVIOUS + SOURCES;
        }

        @Override
        public String manifests() {
            return BuildExecutorModule.PREVIOUS + MANIFESTS;
        }

        @Override
        public String artifacts(DependencyScope scope) {
            return BuildExecutorModule.PREVIOUS + scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS;
        }

        @Override
        public String resolved(DependencyScope scope) {
            return BuildExecutorModule.PREVIOUS + scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.RESOLVED;
        }
    }
}
