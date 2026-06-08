package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Pinning;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Dependencies;

public class ExternalModule implements BuildExecutorModule {

    public static final String COORDINATE = "coordinate", DEPENDENCIES = "dependencies", DELEGATE = "delegate";

    private final String coordinate;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final SequencedSet<String> additionalDependencies;
    private final String buildModuleName;
    private final Pinning pinning;
    private final String group;

    public ExternalModule(String coordinate,
                          String group,
                          Map<String, Repository> repositories,
                          Map<String, Resolver> resolvers) {
        this(coordinate, repositories, resolvers, Collections.emptyNavigableSet(), null, null, group == null ? "main" : group);
    }

    private ExternalModule(String coordinate,
                           Map<String, Repository> repositories,
                           Map<String, Resolver> resolvers,
                           SequencedSet<String> additionalDependencies,
                           String buildModuleName,
                           Pinning pinning,
                           String group) {
        this.coordinate = coordinate;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.additionalDependencies = additionalDependencies;
        this.buildModuleName = buildModuleName;
        this.pinning = pinning;
        this.group = group;
    }

    public ExternalModule dependencies(String... dependencies) {
        return dependencies(new LinkedHashSet<>(List.of(dependencies)));
    }

    public ExternalModule dependencies(SequencedSet<String> dependencies) {
        return new ExternalModule(coordinate,
                repositories,
                resolvers,
                dependencies,
                buildModuleName,
                pinning,
                group);
    }

    public ExternalModule buildModuleName(String name) {
        return new ExternalModule(coordinate,
                repositories,
                resolvers,
                additionalDependencies,
                name,
                pinning,
                group);
    }

    public ExternalModule pinning(Pinning pinning) {
        return new ExternalModule(coordinate,
                repositories,
                resolvers,
                additionalDependencies,
                buildModuleName,
                pinning,
                group);
    }

    public ExternalModule group(String group) {
        return new ExternalModule(coordinate,
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
        List<String> coordinates = new ArrayList<>(additionalDependencies.size() + 1);
        coordinates.add(coordinate);
        coordinates.addAll(additionalDependencies);
        buildExecutor.addStep(COORDINATE,
                new WriteCoordinates(group, coordinates),
                inherited.sequencedKeySet().stream());
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers)
                        .pinning(pinning),
                COORDINATE);
        buildExecutor.addModule(DELEGATE, (delegateExecutor, delegated) -> {
            List<Path> artifacts = new ArrayList<>(
                    Dependencies.select(delegated.get(PREVIOUS + DEPENDENCIES), group, "runtime"));
            artifacts.sort(null);
            JenesisClassLoaderBridge bridge;
            Object foreignModule;
            try {
                bridge = new JenesisClassLoaderBridge(artifacts);
                foreignModule = bridge.findProvider(buildModuleName);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve external build execution module " + coordinate, e);
            }
            SequencedMap<String, Path> forwarded = new LinkedHashMap<>(delegated);
            forwarded.remove(PREVIOUS + DEPENDENCIES);
            bridge.accept(foreignModule, delegateExecutor, forwarded);
        }, Stream.concat(Stream.of(DEPENDENCIES), inherited.sequencedKeySet().stream()));
    }

    private record WriteCoordinates(String group, List<String> coordinates) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties properties = new SequencedProperties();
            for (String coordinate : coordinates) {
                properties.setProperty(group + "/runtime/" + coordinate, "");
            }
            properties.store(context.next().resolve(BuildStep.REQUIRES));
            SequencedProperties versions = new SequencedProperties();
            for (BuildStepArgument argument : arguments.values()) {
                Path file = argument.folder().resolve(BuildStep.VERSIONS);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                SequencedProperties present = SequencedProperties.ofFiles(file);
                for (String coordinate : present.stringPropertyNames()) {
                    versions.putIfAbsent(coordinate, present.getProperty(coordinate));
                }
            }
            if (!versions.isEmpty()) {
                versions.store(context.next().resolve(BuildStep.VERSIONS));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
