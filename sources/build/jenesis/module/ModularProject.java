package build.jenesis.module;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
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
    private final boolean modular;
    private final boolean bridge;

    public ModularProject(String prefix, Path root, Predicate<Path> filter) {
        this(prefix, root, filter, true);
    }

    public ModularProject(String prefix, Path root, Predicate<Path> filter, boolean modular) {
        this(prefix, root, filter, modular, false);
    }

    public ModularProject(String prefix, Path root, Predicate<Path> filter, boolean modular, boolean bridge) {
        this.prefix = prefix;
        this.root = root;
        this.filter = filter;
        this.modular = modular;
        this.bridge = bridge;
    }

    public static BuildExecutorModule make(Path root,
                                           MultiProjectAssembler<? super ModularModuleDescriptor> assembler) {
        return make(root,
                "module",
                _ -> true,
                Map.of("module", new JenesisModuleRepository(true)),
                Map.of("module", new ModularJarResolver(false)),
                false,
                true,
                false,
                new HashDigestFunction("MD5"),
                assembler);
    }

    public static BuildExecutorModule make(Path root,
                                           String prefix,
                                           Predicate<Path> filter,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           boolean strictPinning,
                                           boolean modular,
                                           boolean bridge,
                                           HashDigestFunction digest,
                                           MultiProjectAssembler<? super ModularModuleDescriptor> assembler) {
        return new MultiProjectModule(new ModularProject(prefix, root, filter, modular, bridge),
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
                                    (BiFunction<URI, String, Optional<URI>> & Serializable) (base, _) -> Optional.of(base),
                                    null));
                    for (DependencyScope scope : DependencyScope.values()) {
                        buildExecutor.addModule(scope.label(), (scopeExec, scopeInherited) -> {
                            scopeExec.addStep(PREPARE,
                                    new MultiProjectDependencies(
                                            identifier -> identifier.contains("/" + MultiProjectModule.IDENTIFIER + "/" + name + "/"),
                                            scope,
                                            digest),
                                    scopeInherited.sequencedKeySet());
                            scopeExec.addModule(DEPENDENCIES,
                                    new DependenciesModule(mergedRepositories, resolvers, scope == DependencyScope.COMPILE, strictPinning),
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
                    if (bridge) {
                        buildExecutor.addStep("maven",
                                new MavenIdentity(prefix),
                                MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS,
                                PRODUCE);
                    }
                    buildExecutor.addStep(ASSIGN,
                            new Assign(),
                            bridge
                                    ? new String[] {
                                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES,
                                            PRODUCE,
                                            "maven"}
                                    : new String[] {
                                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES,
                                            PRODUCE});
                    buildExecutor.addStep(MultiProjectModule.INVENTORY,
                            new Inventory(),
                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS,
                            ASSIGN,
                            PRODUCE,
                            DependencyScope.RUNTIME.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS);
                });
    }

    private record Coordinates(String prefix) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties module = SequencedProperties.ofFiles(arguments.get(MANIFESTS)
                    .folder()
                    .resolve(BuildStep.MODULE));
            SequencedProperties coordinates = new SequencedProperties();
            coordinates.setProperty(prefix + "/" + module.getProperty("module"), "");
            coordinates.store(context.next().resolve(BuildStep.IDENTITY));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record MavenIdentity(String prefix) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path pomFile = null;
            String groupId = null;
            String artifactId = null;
            String version = null;
            String module = null;
            for (BuildStepArgument argument : arguments.values()) {
                Path folder = argument.folder();
                Path pomCandidate = folder.resolve("pom.xml");
                if (pomFile == null && Files.isRegularFile(pomCandidate)) {
                    pomFile = pomCandidate;
                }
                Path metadataFile = folder.resolve(BuildStep.METADATA);
                if (groupId == null && Files.isRegularFile(metadataFile)) {
                    SequencedProperties metadata = SequencedProperties.ofFiles(metadataFile);
                    groupId = metadata.getProperty("project");
                    artifactId = metadata.getProperty("artifact");
                    version = metadata.getProperty("version");
                }
                Path moduleFile = folder.resolve(BuildStep.MODULE);
                if (module == null && Files.isRegularFile(moduleFile)) {
                    module = SequencedProperties.ofFiles(moduleFile).getProperty("module");
                }
            }
            if (pomFile == null) {
                throw new IllegalStateException("No produced POM found for module " + module);
            }
            if (groupId == null || artifactId == null || version == null) {
                throw new IllegalStateException("Incomplete Maven metadata for module " + module);
            }
            if (module == null) {
                throw new IllegalStateException("Missing module coordinate for produced POM " + pomFile);
            }
            String pom = context.next().relativize(pomFile).toString().replace(File.separatorChar, '/');
            SequencedProperties identity = new SequencedProperties();
            identity.setProperty("maven/" + groupId + "/" + artifactId + "/" + version, "");
            identity.setProperty("maven/" + groupId + "/" + artifactId + "/pom/" + version, pom);
            identity.setProperty(prefix + "/" + module + ":pom", pom);
            identity.store(context.next().resolve(BuildStep.IDENTITY));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record Manifests(String prefix, String path, boolean modular) implements BuildStep {

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
                        + "' declares @jenesis.test "
                        + info.testOf()
                        + " but does not 'requires "
                        + info.testOf()
                        + ";' (declared requires: "
                        + info.requires()
                        + ")");
            }
            SequencedProperties requires = new SequencedProperties();
            SequencedProperties scopes = new SequencedProperties();
            for (String dependency : info.requires()) {
                String key = prefix + "/" + dependency;
                requires.setProperty(key, "");
                scopes.setProperty(key, info.runtimeRequires().contains(dependency)
                        ? DependencyScope.COMPILE.name() + "," + DependencyScope.RUNTIME.name()
                        : DependencyScope.COMPILE.name());
            }
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            scopes.store(context.next().resolve(BuildStep.SCOPES));
            if (!info.versions().isEmpty()) {
                SequencedProperties properties = new SequencedProperties();
                info.versions().forEach(properties::setProperty);
                properties.store(context.next().resolve(BuildStep.VERSIONS));
            }
            Javac.writeRelease(context.next(), info.release());
            SequencedProperties module = new SequencedProperties();
            module.setProperty("path", path);
            module.setProperty("module", info.coordinate());
            module.setProperty("modular", Boolean.toString(modular));
            if (info.testOf() != null) {
                module.setProperty("test", info.testOf());
            }
            if (info.main() != null) {
                module.setProperty("main", info.main());
            }
            module.store(context.next().resolve(BuildStep.MODULE));
            SequencedProperties metadata = new SequencedProperties();
            String moduleName = info.coordinate();
            String[] segments = moduleName.split("\\.");
            metadata.setProperty("project", segments.length >= 2 ? segments[0] + "." + segments[1] : moduleName);
            metadata.setProperty("artifact", moduleName);
            metadata.setProperty("version", "0-SNAPSHOT");
            if (info.name() != null) {
                metadata.setProperty("name", info.name());
            }
            if (info.description() != null) {
                metadata.setProperty("description", info.description());
            }
            for (BuildStepArgument argument : arguments.values()) {
                Path upstream = argument.folder().resolve(BuildStep.METADATA);
                if (Files.isRegularFile(upstream)) {
                    SequencedProperties.ofFiles(upstream).forEach(metadata::put);
                }
            }
            metadata.store(context.next().resolve(BuildStep.METADATA));
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
                        buildExecutor.addModule(SIBLING_MODULE_PREFIX + BuildExecutorModule.encode(relative), (module, modInherited) -> {
                            module.addSource("sources", Bind.asSources(), parent);
                            SequencedSet<String> manifestDeps = new LinkedHashSet<>();
                            manifestDeps.add("sources");
                            manifestDeps.addAll(modInherited.sequencedKeySet());
                            module.addStep(MANIFESTS, new Manifests(prefix, relative, modular), manifestDeps);
                            module.addStep(COORDINATES, new Coordinates(prefix), MANIFESTS);
                        }, inherited.sequencedKeySet().stream());
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.exists(dir.resolve(BuildExecutor.SKIP_MARKER))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record ModularModuleDescriptor(String name, SequencedSet<String> dependencies) implements ModuleDescriptor {

        @Override
        public SequencedSet<String> sources() {
            return of(BuildExecutorModule.PREVIOUS + SOURCES);
        }

        @Override
        public SequencedSet<String> resources() {
            return Collections.emptyNavigableSet();
        }

        @Override
        public SequencedSet<String> manifests() {
            return of(BuildExecutorModule.PREVIOUS + MANIFESTS);
        }

        @Override
        public SequencedSet<String> coordinates() {
            return of(BuildExecutorModule.PREVIOUS + COORDINATES);
        }

        @Override
        public SequencedSet<String> artifacts(DependencyScope scope) {
            return of(BuildExecutorModule.PREVIOUS + scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS);
        }

        @Override
        public SequencedSet<String> resolved(DependencyScope scope) {
            return of(BuildExecutorModule.PREVIOUS + scope.label() + "/" + DEPENDENCIES + "/" + DependenciesModule.RESOLVED);
        }

        private static SequencedSet<String> of(String value) {
            return Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(value)));
        }
    }
}
