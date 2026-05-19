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
import build.jenesis.step.FilePlacement;
import build.jenesis.step.Inventory;
import build.jenesis.step.Javac;

import static build.jenesis.project.MultiProjectModule.ASSIGN;
import static build.jenesis.project.MultiProjectModule.COORDINATES;
import static build.jenesis.project.MultiProjectModule.DEPENDENCIES;
import static build.jenesis.project.MultiProjectModule.MANIFESTS;
import static build.jenesis.project.MultiProjectModule.PREPARE;
import static build.jenesis.project.MultiProjectModule.PRODUCE;
import static build.jenesis.project.MultiProjectModule.SOURCES;

public class ModularProject implements BuildExecutorModule {

    private static final String SIBLING_MODULE_PREFIX = MultiProjectModule.MODULE + "-";

    private final String prefix;
    private final Path root;
    private final Predicate<Path> filter;

    public ModularProject(String prefix, Path root, Predicate<Path> filter) {
        this.prefix = prefix;
        this.root = root;
        this.filter = filter;
    }

    public static FilePlacement artifactsByModule() {
        return MultiProjectModule.linkBySubModule("classes.jar", "sources.jar", "javadoc.jar",
                BuildStep.MODULE, BuildStep.PROJECT, BuildStep.IDENTITY);
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
                    SequencedMap<String, String> produceDeps = new LinkedHashMap<>();
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + SOURCES, SOURCES);
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS, MANIFESTS);
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES, COORDINATES);
                    for (DependencyScope scope : List.of(DependencyScope.COMPILE, DependencyScope.RUNTIME)) {
                        String resolved = scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.RESOLVED;
                        String artifacts = scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS;
                        produceDeps.put(resolved, resolved);
                        produceDeps.put(artifacts, artifacts);
                    }
                    for (String key : inherited.sequencedKeySet()) {
                        produceDeps.putIfAbsent(key, key);
                    }
                    buildExecutor.addModule(PRODUCE,
                            assembler.apply(new ModularModuleDescriptor(name, dependencies.sequencedKeySet()),
                                    mergedRepositories,
                                    resolvers),
                            produceDeps);
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
                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES,
                            PRODUCE);
                    buildExecutor.addStep(MultiProjectModule.INVENTORY,
                            new Inventory(),
                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS,
                            ASSIGN,
                            DependencyScope.RUNTIME.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS);
                });
    }

    private record Coordinates(String prefix) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Properties module = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(arguments.get(MANIFESTS)
                    .folder()
                    .resolve(BuildStep.MODULE))) {
                module.load(reader);
            }
            Properties coordinates = new SequencedProperties();
            coordinates.setProperty(prefix + "/" + module.getProperty("module"), "");
            try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(BuildStep.IDENTITY))) {
                coordinates.store(writer, null);
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record Manifests(String prefix, String path) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            ModuleInfo info = new ModuleInfoParser().identify(arguments.get("sources").folder()
                    .resolve(BuildStep.SOURCES)
                    .resolve("module-info.java"));
            if (info.testOf() != null && !info.testOf().isEmpty() && !info.requires().contains(info.testOf())) {
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
            Properties module = new SequencedProperties();
            module.setProperty("path", path);
            module.setProperty("module", info.coordinate());
            if (info.testOf() != null) {
                module.setProperty("tests", info.testOf());
            }
            if (info.main() != null) {
                module.setProperty("main", info.main());
            }
            try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(BuildStep.MODULE))) {
                module.store(writer, null);
            }
            Properties metadata = new SequencedProperties();
            if (info.name() != null) {
                metadata.setProperty("name", info.name());
            }
            if (info.description() != null) {
                metadata.setProperty("description", info.description());
            }
            if (!metadata.isEmpty()) {
                try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(BuildStep.PROJECT))) {
                    metadata.store(writer, null);
                }
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
                        String relative = location.toString().replace(File.separatorChar, '/');
                        buildExecutor.addModule(SIBLING_MODULE_PREFIX + BuildExecutorModule.encode(relative), (module, _) -> {
                            module.addSource("sources", Bind.asSources(), parent);
                            module.addStep(MANIFESTS, new Manifests(prefix, relative), "sources");
                            module.addStep(COORDINATES, new Coordinates(prefix), MANIFESTS);
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
        public String coordinates() {
            return BuildExecutorModule.PREVIOUS + COORDINATES;
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
