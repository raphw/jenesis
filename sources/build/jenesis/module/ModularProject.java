package build.jenesis.module;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.Platform;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.project.AssemblyDescriptor;
import build.jenesis.project.ProjectModule;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.step.Assign;
import build.jenesis.step.Bind;
import build.jenesis.step.Dependencies;
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

    private final String group;
    private final String prefix;
    private final Path root;
    private final Predicate<Path> filter;
    private final boolean modular;
    private final Platform platform;

    public ModularProject(String prefix, Path root) {
        this("main", prefix, root, _ -> true, true, new Platform());
    }

    private ModularProject(String group,
                           String prefix,
                           Path root,
                           Predicate<Path> filter,
                           boolean modular,
                           Platform platform) {
        this.group = group;
        this.prefix = prefix;
        this.root = root;
        this.filter = filter;
        this.modular = modular;
        this.platform = platform;
    }

    public ModularProject group(String group) {
        return new ModularProject(group, prefix, root, filter, modular, platform);
    }

    public ModularProject filter(Predicate<Path> filter) {
        return new ModularProject(group, prefix, root, filter, modular, platform);
    }

    public ModularProject modular(boolean modular) {
        return new ModularProject(group, prefix, root, filter, modular, platform);
    }

    public ModularProject platform(Platform platform) {
        return new ModularProject(group, prefix, root, filter, modular, platform);
    }

    public static BuildExecutorModule make(Path root, MultiProjectAssembler<? super ModularModuleDescriptor> assembler) {
        return make(root,
                "main",
                "module",
                _ -> true,
                Map.of("module", new JenesisModuleRepository(true)),
                Map.of("module", new ModularJarResolver(false)),
                null,
                true,
                false,
                assembler);
    }

    public static BuildExecutorModule make(Path root,
                                           String group,
                                           String prefix,
                                           Predicate<Path> filter,
                                           Map<String, Repository> repositories,
                                           Map<String, Resolver> resolvers,
                                           Pinning pinning,
                                           boolean modular,
                                           boolean printDependencies,
                                           MultiProjectAssembler<? super ModularModuleDescriptor> assembler) {
        return new MultiProjectModule(new ModularProject(prefix, root).group(group).filter(filter).modular(modular),
                identity -> Optional.of(identity.substring(0, identity.indexOf('/'))),
                _ -> (name, dependencies, _) -> {
                    AssemblyDescriptor packaging = assembler.apply(
                            new ModularModuleDescriptor(name, dependencies.sequencedKeySet()),
                            repositories,
                            resolvers);
                    AssemblyDescriptor assembly = new AssemblyDescriptor((buildExecutor, inherited) -> {
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
                    buildExecutor.addModule(DEPENDENCIES, (depExec, depInherited) -> {
                        depExec.addStep(PREPARE,
                                new MultiProjectDependencies(
                                        identifier -> identifier.contains("/" + MultiProjectModule.IDENTIFIER + "/" + name + "/")),
                                depInherited.sequencedKeySet());
                        depExec.addStep(Dependencies.ARTIFACTS,
                                new Dependencies(mergedRepositories, resolvers).pinning(pinning).printing(printDependencies),
                                PREPARE);
                    }, inherited.sequencedKeySet());
                    SequencedMap<String, String> produceDeps = new LinkedHashMap<>();
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + SOURCES, SOURCES);
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS, MANIFESTS);
                    produceDeps.put(MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES, COORDINATES);
                    produceDeps.put(DEPENDENCIES + "/" + Dependencies.ARTIFACTS, DEPENDENCIES + "/" + Dependencies.ARTIFACTS);
                    for (String key : inherited.sequencedKeySet()) {
                        produceDeps.putIfAbsent(key, key);
                    }
                    buildExecutor.addModule(PRODUCE,
                            assembler.apply(new ModularModuleDescriptor(name, dependencies.sequencedKeySet()),
                                    mergedRepositories,
                                    resolvers).build(),
                            produceDeps);
                    buildExecutor.addStep(ASSIGN,
                            new Assign(),
                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + COORDINATES,
                            PRODUCE);
                    buildExecutor.addStep(MultiProjectModule.INVENTORY,
                            new Inventory(),
                            MultiProjectModule.IDENTIFIER_PATH + name + "/" + MANIFESTS,
                            ASSIGN,
                            PRODUCE,
                            DEPENDENCIES + "/" + Dependencies.ARTIFACTS);
                    });
                    for (Map.Entry<String, BuildExecutorModule> phase : packaging.tail().entrySet()) {
                        assembly = assembly.then(phase.getKey(), phase.getValue());
                    }
                    return assembly;
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

    private record Manifests(String group,
                             String prefix,
                             String path,
                             boolean modular,
                             Platform platform) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            ModuleInfo info = new ModuleInfoParser(group).identify(arguments.get("sources").folder()
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
            for (String dependency : info.requires()) {
                requires.setProperty(group + "/compile/" + prefix + "/" + dependency, "");
                if (info.runtimeRequires().contains(dependency)) {
                    requires.setProperty(group + "/runtime/" + prefix + "/" + dependency, "");
                }
            }
            info.plugins().forEach((coordinate, group) ->
                    requires.setProperty(group + "/plugin/" + coordinate, ""));
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            SequencedMap<String, String> versions = new LinkedHashMap<>(info.versions());
            for (Map.Entry<String, SequencedMap<String, String>> variant : info.variants().entrySet()) {
                String selected = platform.select(variant.getKey(),
                        versions.get(variant.getKey()),
                        variant.getValue());
                if (selected != null) {
                    versions.put(variant.getKey(), selected);
                }
            }
            if (!versions.isEmpty()) {
                SequencedProperties properties = new SequencedProperties();
                versions.forEach(properties::setProperty);
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
            metadata.setProperty("version", "1-SNAPSHOT");
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
        List<Path> moduleInfos = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("module-info.java")) {
                    moduleInfos.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && Files.exists(dir.resolve(BuildExecutor.SKIP_MARKER))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        moduleInfos.sort(null);
        for (Path file : moduleInfos) {
            Path parent = file.getParent(), location = root.relativize(parent);
            if (filter.test(location)) {
                String relative = location.toString().replace(File.separatorChar, '/');
                buildExecutor.addModule(SIBLING_MODULE_PREFIX + BuildExecutorModule.encode(relative), (module, modInherited) -> {
                    module.addSource("sources", Bind.asSources(), parent);
                    SequencedSet<String> manifestDeps = new LinkedHashSet<>();
                    manifestDeps.add("sources");
                    manifestDeps.addAll(modInherited.sequencedKeySet());
                    module.addStep(MANIFESTS, new Manifests(group, prefix, relative, modular, platform), manifestDeps);
                    module.addStep(COORDINATES, new Coordinates(prefix), MANIFESTS);
                }, inherited.sequencedKeySet().stream());
            }
        }
    }

    public record ModularModuleDescriptor(String name, SequencedSet<String> dependencies) implements ProjectModule {

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
        public SequencedSet<String> artifacts() {
            return of(BuildExecutorModule.PREVIOUS + DEPENDENCIES + "/" + Dependencies.ARTIFACTS);
        }

        private static SequencedSet<String> of(String value) {
            return Collections.unmodifiableSequencedSet(new LinkedHashSet<>(List.of(value)));
        }
    }
}
