package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.module.JenesisModuleRepository;
import build.jenesis.module.JenesisRepository;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModuleInfo;
import build.jenesis.module.ModuleInfoParser;
import build.jenesis.step.Bind;
import build.jenesis.step.Dependencies;

public class InternalModule implements BuildExecutorModule {

    public static final String SOURCE = "source",
            JAVA = "java",
            DELEGATE = "delegate";

    private static final String DEPENDENCIES = "dependencies", REQUIRES = "requires";
    private static final String MAIN_ARTIFACTS = JAVA + "/" + JavaToolchainModule.ARTIFACTS;

    private final String prefix;
    private final Path source;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final SequencedSet<String> additionalDependencies;
    private final String buildModuleName;
    private final Pinning pinning;
    private final String group;

    public InternalModule(String prefix,
                          String group,
                          Path source) {
        this(prefix,
                source,
                Map.of(prefix, JenesisModuleRepository.of(JenesisRepository.Scope.MODULE).prepend(JenesisModuleRepository.ofLocal())),
                Map.of(prefix, new ModularJarResolver(true)),
                Collections.emptyNavigableSet(),
                null,
                null,
                group == null ? "main" : group);
    }

    public InternalModule repositories(Map<String, Repository> repositories) {
        return new InternalModule(prefix, source, repositories, resolvers, additionalDependencies, buildModuleName, pinning, group);
    }

    public InternalModule resolvers(Map<String, Resolver> resolvers) {
        return new InternalModule(prefix, source, repositories, resolvers, additionalDependencies, buildModuleName, pinning, group);
    }

    public InternalModule group(String group) {
        return new InternalModule(prefix, source, repositories, resolvers, additionalDependencies, buildModuleName, pinning, group);
    }

    private InternalModule(String prefix,
                           Path source,
                           Map<String, Repository> repositories,
                           Map<String, Resolver> resolvers,
                           SequencedSet<String> additionalDependencies,
                           String buildModuleName,
                           Pinning pinning,
                           String group) {
        this.prefix = prefix;
        this.source = source;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.additionalDependencies = additionalDependencies;
        this.buildModuleName = buildModuleName;
        this.pinning = pinning;
        this.group = group;
    }

    public InternalModule dependencies(String... dependencies) {
        return new InternalModule(prefix,
                source,
                repositories,
                resolvers,
                new LinkedHashSet<>(List.of(dependencies)),
                buildModuleName,
                pinning,
                group);
    }

    public InternalModule dependencies(SequencedSet<String> dependencies) {
        return new InternalModule(prefix,
                source,
                repositories,
                resolvers,
                new LinkedHashSet<>(dependencies),
                buildModuleName,
                pinning,
                group);
    }

    public InternalModule buildModuleName(String name) {
        return new InternalModule(prefix,
                source,
                repositories,
                resolvers,
                additionalDependencies,
                name,
                pinning,
                group);
    }

    public InternalModule pinning(Pinning pinning) {
        return new InternalModule(prefix,
                source,
                repositories,
                resolvers,
                additionalDependencies,
                buildModuleName,
                pinning,
                group);
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(DELEGATE)) {
            return Optional.of("");
        }
        if (path.startsWith(DELEGATE + "/")) {
            return Optional.of(path.substring(DELEGATE.length() + 1));
        }
        if (path.equals(DEPENDENCIES)) {
            return Optional.of(path);
        }
        return Optional.empty();
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addSource(SOURCE, Bind.asSources(), source);
        buildExecutor.addStep(REQUIRES,
                new ParseModuleInfo(group, prefix, additionalDependencies),
                Stream.concat(Stream.of(SOURCE), inherited.sequencedKeySet().stream()));
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                REQUIRES);
        buildExecutor.addModule(JAVA, new JavaToolchainModule().group(group), SOURCE, DEPENDENCIES);
        buildExecutor.addModule(DELEGATE, (delegateExecutor, delegated) -> {
            Path mainArtifacts = delegated.get(PREVIOUS + MAIN_ARTIFACTS).resolve(BuildStep.ARTIFACTS);
            List<Path> artifacts = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(mainArtifacts)) {
                for (Path file : files) {
                    artifacts.add(file);
                }
            }
            artifacts.addAll(Dependencies.select(delegated.get(PREVIOUS + DEPENDENCIES), group, "runtime"));
            artifacts.sort(null);
            JenesisClassLoaderBridge bridge;
            Object foreignModule;
            try {
                bridge = new JenesisClassLoaderBridge(artifacts);
                foreignModule = bridge.findProvider(buildModuleName);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve internal build execution module " + source, e);
            }
            SequencedMap<String, Path> forwarded = new LinkedHashMap<>(delegated);
            forwarded.remove(PREVIOUS + MAIN_ARTIFACTS);
            forwarded.remove(PREVIOUS + DEPENDENCIES);
            bridge.accept(foreignModule, delegateExecutor, forwarded);
        }, Stream.concat(Stream.of(MAIN_ARTIFACTS, DEPENDENCIES), inherited.sequencedKeySet().stream()));
    }

    private record ParseModuleInfo(String group,
                                   String prefix,
                                   SequencedSet<String> additionalDependencies) implements BuildStep {

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            if (arguments.get(SOURCE).hasChanged(Path.of(BuildStep.SOURCES + "module-info.java"))) {
                return true;
            }
            for (Map.Entry<String, BuildStepArgument> argument : arguments.entrySet()) {
                if (!argument.getKey().equals(SOURCE) && argument.getValue().hasChanged(Path.of(BuildStep.VERSIONS))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path moduleInfo = arguments.get(SOURCE).folder()
                    .resolve(BuildStep.SOURCES)
                    .resolve("module-info.java");
            if (!Files.isRegularFile(moduleInfo)) {
                throw new IllegalStateException(
                        "Internal module source is not modular (missing module-info.java)");
            }
            ModuleInfo info = new ModuleInfoParser(group).identify(moduleInfo);
            SequencedProperties properties = new SequencedProperties();
            for (String dependency : info.requires()) {
                properties.setProperty(group + "/compile/" + prefix + "/" + dependency, "");
                if (info.runtimeRequires().contains(dependency)) {
                    properties.setProperty(group + "/runtime/" + prefix + "/" + dependency, "");
                }
            }
            for (String dependency : additionalDependencies) {
                properties.setProperty(group + "/compile/" + dependency, "");
                properties.setProperty(group + "/runtime/" + dependency, "");
            }
            info.plugins().forEach((coordinate, group) -> properties.setProperty(group + "/plugin/" + coordinate, ""));
            properties.store(context.next().resolve(BuildStep.REQUIRES));
            SequencedProperties versions = pinnedVersions(arguments);
            if (!versions.isEmpty()) {
                versions.store(context.next().resolve(BuildStep.VERSIONS));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static SequencedProperties pinnedVersions(SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedProperties versions = new SequencedProperties();
        for (Map.Entry<String, BuildStepArgument> argument : arguments.entrySet()) {
            if (argument.getKey().equals(SOURCE)) {
                continue;
            }
            Path file = argument.getValue().folder().resolve(BuildStep.VERSIONS);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            SequencedProperties present = SequencedProperties.ofFiles(file);
            for (String coordinate : present.stringPropertyNames()) {
                versions.putIfAbsent(coordinate, present.getProperty(coordinate));
            }
        }
        return versions;
    }
}
