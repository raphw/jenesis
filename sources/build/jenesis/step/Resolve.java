package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

import static java.util.Objects.requireNonNull;

public class Resolve implements DependencyProcessingBuildStep {

    private final transient Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean compile;

    public Resolve(Map<String, Repository> repositories, Map<String, Resolver> resolvers, boolean compile) {
        this.repositories = repositories;
        this.resolvers = new LinkedHashMap<>(resolvers);
        this.compile = compile;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
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
            for (Map.Entry<String, String> entry : requireNonNull(
                    resolvers.get(group.getKey()),
                    "Unknown resolver: " + group.getKey()).dependencies(
                    executor,
                    group.getKey(),
                    repositories,
                    coordinates,
                    versions.getOrDefault(group.getKey(), new LinkedHashMap<>()),
                    compile).entrySet()) {
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
