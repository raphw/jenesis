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

    public static final String COORDINATE = "coordinate", DEPENDENCIES = "dependencies", EXTERNAL = "external", DELEGATE = "delegate";
    private static final String EXTERNAL_ARTIFACTS = DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS;
    private static final String EXTERNAL_PROPERTIES = "external.properties", JENESIS_MODULE = "Jenesis-Module";

    private final String coordinate;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final List<?> arguments;
    private final String checksum;
    private final List<String> additionalDependencies;

    public ExternalModule(String coordinate,
                          Map<String, Repository> repositories,
                          Map<String, Resolver> resolvers,
                          Object... arguments) {
        this(coordinate, repositories, resolvers, List.of(arguments));
    }

    public ExternalModule(String coordinate,
                          Map<String, Repository> repositories,
                          Map<String, Resolver> resolvers,
                          List<?> arguments) {
        this(coordinate, repositories, resolvers, arguments, null, List.of());
    }

    private ExternalModule(String coordinate,
                           Map<String, Repository> repositories,
                           Map<String, Resolver> resolvers,
                           List<?> arguments,
                           String checksum,
                           List<String> additionalDependencies) {
        this.coordinate = coordinate;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.arguments = arguments;
        this.checksum = checksum;
        this.additionalDependencies = additionalDependencies;
    }

    public ExternalModule computeChecksums(String algorithm) {
        return new ExternalModule(coordinate, repositories, resolvers, arguments, algorithm, additionalDependencies);
    }

    public ExternalModule withDependencies(String... dependencies) {
        return withDependencies(List.of(dependencies));
    }

    public ExternalModule withDependencies(List<String> dependencies) {
        return new ExternalModule(coordinate, repositories, resolvers, arguments, checksum, List.copyOf(dependencies));
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
        DependenciesModule dependencies = new DependenciesModule(repositories, resolvers, false);
        if (checksum != null) {
            dependencies = dependencies.computeChecksums(checksum);
        }
        buildExecutor.addModule(DEPENDENCIES, dependencies, COORDINATE);
        buildExecutor.addStep(EXTERNAL, new ExtractExternal(coordinate), EXTERNAL_ARTIFACTS);
        buildExecutor.addModule(DELEGATE, (delegateExecutor, delegated) -> {
            Path artifacts = delegated.get(PREVIOUS + EXTERNAL_ARTIFACTS).resolve(BuildStep.ARTIFACTS);
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(delegated.get(PREVIOUS + EXTERNAL)
                    .resolve(EXTERNAL_PROPERTIES))) {
                properties.load(reader);
            }
            String name = properties.getProperty(JENESIS_MODULE);
            List<URL> urls = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(artifacts)) {
                for (Path file : files) {
                    urls.add(file.toUri().toURL());
                }
            }
            URLClassLoader classLoader = new URLClassLoader(
                    urls.toArray(URL[]::new),
                    BuildExecutorModule.class.getClassLoader());
            BuildExecutorModule delegate;
            try {
                Class<? extends BuildExecutorModule> loaded = Class.forName(name,
                                true,
                                classLoader).asSubclass(BuildExecutorModule.class);
                Constructor<?> constructor = null;
                for (Constructor<?> candidate : loaded.getConstructors()) {
                    if (candidate.getParameterCount() == arguments.size()) {
                        if (constructor != null) {
                            throw new IllegalStateException("Ambiguous constructor in " 
                                    + loaded + " for " 
                                    + List.of(arguments));
                        }
                        constructor = candidate;
                    }
                }
                if (constructor == null) {
                    throw new IllegalStateException("No suitable constructor in "
                            + loaded + " for "
                            + List.of(arguments));
                }
                delegate = (BuildExecutorModule) constructor.newInstance(arguments.toArray());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve external build execution module " + coordinate, e);
            }
            SequencedMap<String, Path> forwarded = new LinkedHashMap<>(delegated);
            forwarded.remove(PREVIOUS + EXTERNAL_ARTIFACTS);
            delegate.accept(delegateExecutor, forwarded);
        }, Stream.concat(Stream.of(EXTERNAL, EXTERNAL_ARTIFACTS), inherited.sequencedKeySet().stream()));
    }

    private record WriteCoordinates(List<String> coordinates) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Properties properties = new SequencedProperties();
            for (String coordinate : coordinates) {
                properties.setProperty(coordinate, "");
            }
            try (Writer writer = Files.newBufferedWriter(context.next().resolve(BuildStep.REQUIRES))) {
                properties.store(writer, null);
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record ExtractExternal(String coordinate) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path artifacts = arguments.get(EXTERNAL_ARTIFACTS).folder().resolve(BuildStep.ARTIFACTS);
            String name;
            try (JarFile jarFile = new JarFile(artifacts.resolve(coordinate.replace('/', '-') + ".jar").toFile())) {
                Manifest manifest = jarFile.getManifest();
                if (manifest == null) {
                    throw new IllegalStateException("Missing manifest in main artifact: " + coordinate);
                }
                name = manifest.getMainAttributes().getValue(JENESIS_MODULE);
                if (name == null) {
                    throw new IllegalStateException("Missing Jenesis module manifest entry in artifact: " + coordinate);
                }
            }
            Properties properties = new SequencedProperties();
            properties.setProperty(JENESIS_MODULE, name);
            try (Writer writer = Files.newBufferedWriter(context.next().resolve(EXTERNAL_PROPERTIES))) {
                properties.store(writer, null);
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
