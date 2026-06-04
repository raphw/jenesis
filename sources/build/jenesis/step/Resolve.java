package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.Repository;
import build.jenesis.ResolutionListener;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

import static java.util.Objects.requireNonNull;

public class Resolve implements DependencyProcessingBuildStep {

    private final transient Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean compile;
    private final boolean pinned;
    private final transient Supplier<ResolutionListener> listener;

    public Resolve(Map<String, Repository> repositories, Map<String, Resolver> resolvers, boolean compile) {
        this(repositories, resolvers, compile, true, null);
    }

    private Resolve(Map<String, Repository> repositories,
                    Map<String, Resolver> resolvers,
                    boolean compile,
                    boolean pinned,
                    Supplier<ResolutionListener> listener) {
        this.repositories = repositories;
        this.resolvers = new LinkedHashMap<>(resolvers);
        this.compile = compile;
        this.pinned = pinned;
        this.listener = listener;
    }

    public Resolve pinned(boolean pinned) {
        return new Resolve(repositories, resolvers, compile, pinned, listener);
    }

    public Resolve listening(Supplier<ResolutionListener> listener) {
        return new Resolve(repositories, resolvers, compile, pinned, listener);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        if (listener != null) {
            return true;
        }
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(REQUIRES),
                Path.of(VERSIONS),
                Path.of(EXCLUSIONS)));
    }

    @Override
    public CompletionStage<SequencedProperties> transform(Executor executor,
                                                          BuildStepContext context,
                                                          SequencedMap<String, BuildStepArgument> arguments,
                                                          SequencedMap<String, SequencedMap<String, String>> groups,
                                                          SequencedMap<String, SequencedMap<String, String>> versions)
            throws IOException {
        SequencedMap<String, SequencedMap<String, SequencedSet<String>>> exclusions = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path file = argument.folder().resolve(EXCLUSIONS);
            if (!Files.exists(file)) {
                continue;
            }
            SequencedProperties properties = SequencedProperties.ofFiles(file);
            for (String property : properties.stringPropertyNames()) {
                int index = property.indexOf('/');
                String prefix = property.substring(0, index);
                String coordinate = property.substring(index + 1);
                String value = properties.getProperty(property);
                SequencedSet<String> excludes = new LinkedHashSet<>();
                if (!value.isEmpty()) {
                    for (String entry : value.split(",")) {
                        excludes.add(entry);
                    }
                }
                exclusions.computeIfAbsent(prefix, _ -> new LinkedHashMap<>()).put(coordinate, excludes);
            }
        }
        SequencedProperties properties = new SequencedProperties();
        for (Map.Entry<String, SequencedMap<String, String>> group : groups.entrySet()) {
            SequencedMap<String, SequencedSet<String>> coordinates = new LinkedHashMap<>();
            SequencedMap<String, SequencedSet<String>> groupExclusions = exclusions
                    .getOrDefault(group.getKey(), new LinkedHashMap<>());
            for (String coordinate : group.getValue().sequencedKeySet()) {
                coordinates.put(coordinate, groupExclusions.getOrDefault(coordinate, Collections.emptyNavigableSet()));
            }
            Resolver resolver = requireNonNull(
                    resolvers.get(Resolver.base(group.getKey())),
                    "Unknown resolver: " + Resolver.base(group.getKey()));
            SequencedMap<String, String> groupVersions = new LinkedHashMap<>();
            if (pinned) {
                groupVersions.putAll(versions.getOrDefault(group.getKey(), new LinkedHashMap<>()));
                for (String managed : resolver.managedPrefixes()) {
                    versions.getOrDefault(managed, new LinkedHashMap<>()).forEach(groupVersions::putIfAbsent);
                }
            }
            SequencedMap<String, String> resolved;
            if (listener == null) {
                resolved = resolver.dependencies(executor, group.getKey(), repositories, coordinates, groupVersions, compile);
            } else {
                ResolutionListener current = listener.get();
                resolved = resolver.dependencies(executor, group.getKey(), repositories, coordinates, groupVersions, compile, current);
                current.onResolved();
            }
            for (Map.Entry<String, String> entry : resolved.entrySet()) {
                String value;
                if (Objects.equals(group.getKey(), entry.getKey().substring(0, entry.getKey().indexOf('/')))) {
                    String declared = group.getValue().get(entry.getKey().substring(entry.getKey().indexOf('/') + 1));
                    value = declared == null || declared.isEmpty() ? entry.getValue() : declared;
                } else {
                    value = entry.getValue();
                }
                properties.setProperty(entry.getKey(), value);
            }
        }
        return CompletableFuture.completedStage(properties);
    }
}
