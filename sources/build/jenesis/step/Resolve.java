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
    public CompletionStage<Properties> transform(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> groups,
                                                 SequencedMap<String, SequencedMap<String, String>> versions)
            throws IOException {
        Properties properties = new SequencedProperties();
        for (Map.Entry<String, SequencedMap<String, String>> group : groups.entrySet()) {
            for (Map.Entry<String, String> entry : requireNonNull(
                    resolvers.get(group.getKey()),
                    "Unknown resolver: " + group.getKey()).dependencies(
                    executor,
                    group.getKey(),
                    repositories,
                    group.getValue().sequencedKeySet(),
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
