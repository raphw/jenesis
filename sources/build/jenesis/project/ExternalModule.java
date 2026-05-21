package build.jenesis.project;

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

public class ExternalModule implements BuildExecutorModule {

    public static final String COORDINATE = "coordinate", DEPENDENCIES = "dependencies", DELEGATE = "delegate";
    private static final String EXTERNAL_ARTIFACTS = DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS;

    private final String coordinate;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final List<String> additionalDependencies;

    public ExternalModule(String coordinate,
                          Map<String, Repository> repositories,
                          Map<String, Resolver> resolvers) {
        this(coordinate, repositories, resolvers, List.of());
    }

    private ExternalModule(String coordinate,
                           Map<String, Repository> repositories,
                           Map<String, Resolver> resolvers,
                           List<String> additionalDependencies) {
        this.coordinate = coordinate;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.additionalDependencies = additionalDependencies;
    }

    public ExternalModule withDependencies(String... dependencies) {
        return withDependencies(List.of(dependencies));
    }

    public ExternalModule withDependencies(List<String> dependencies) {
        return new ExternalModule(coordinate, repositories, resolvers, List.copyOf(dependencies));
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(DELEGATE)) {
            return Optional.of("");
        }
        if (path.startsWith(DELEGATE + "/")) {
            return Optional.of(path.substring(DELEGATE.length() + 1));
        }
        return Optional.empty();
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        List<String> coordinates = new ArrayList<>(additionalDependencies.size() + 1);
        coordinates.add(coordinate);
        coordinates.addAll(additionalDependencies);
        buildExecutor.addStep(COORDINATE, new WriteCoordinates(coordinates));
        buildExecutor.addModule(DEPENDENCIES,
                new DependenciesModule(repositories, resolvers, false),
                COORDINATE);
        buildExecutor.addModule(DELEGATE, (delegateExecutor, delegated) -> {
            Path artifacts = delegated.get(PREVIOUS + EXTERNAL_ARTIFACTS).resolve(BuildStep.DEPENDENCIES);
            List<URL> urls = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(artifacts)) {
                for (Path file : files) {
                    urls.add(file.toUri().toURL());
                }
            }
            URL mainArtifact = artifacts.resolve(coordinate.replace('/', '-') + ".jar").toUri().toURL();
            URLClassLoader classLoader = new URLClassLoader(
                    urls.toArray(URL[]::new),
                    BuildExecutorModule.class.getClassLoader());
            URLClassLoader scanLoader = new URLClassLoader(
                    new URL[] {mainArtifact},
                    BuildExecutorModule.class.getClassLoader());
            BuildExecutorModule delegate;
            try {
                String name = ServiceLoader
                        .load(BuildExecutorModule.class, scanLoader)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "No BuildExecutorModule service provider found for " + coordinate))
                        .type()
                        .getName();
                delegate = Class.forName(name, true, classLoader)
                        .asSubclass(BuildExecutorModule.class)
                        .getConstructor()
                        .newInstance();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve external build execution module " + coordinate, e);
            }
            SequencedMap<String, Path> forwarded = new LinkedHashMap<>(delegated);
            forwarded.remove(PREVIOUS + EXTERNAL_ARTIFACTS);
            delegate.accept(delegateExecutor, forwarded);
        }, Stream.concat(Stream.of(EXTERNAL_ARTIFACTS), inherited.sequencedKeySet().stream()));
    }

    private record WriteCoordinates(List<String> coordinates) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties properties = new SequencedProperties();
            for (String coordinate : coordinates) {
                properties.setProperty(coordinate, "");
            }
            properties.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
