package build.jenesis.step;

import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

import module java.base;

import static java.util.Objects.requireNonNull;

public class Resolve implements DependencyTransformingBuildStep {

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;

    public Resolve(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this.repositories = repositories;
        this.resolvers = resolvers;
    }

    @Override
    public CompletionStage<Properties> transform(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> groups)
            throws IOException {
        Properties properties = new SequencedProperties();
        for (Map.Entry<String, SequencedMap<String, String>> group : groups.entrySet()) {
            for (Map.Entry<String, String> entry : requireNonNull(
                    resolvers.get(group.getKey()),
                    "Unknown resolver: " + group.getKey()).dependencies(
                    executor,
                    group.getKey(),
                    repositories,
                    group.getValue().sequencedKeySet()).entrySet()) {
                String value;
                if (Objects.equals(group.getKey(), entry.getKey().substring(0, entry.getKey().indexOf('/')))) {
                    value = group.getValue().getOrDefault(
                            entry.getKey().substring(entry.getKey().indexOf('/') + 1),
                            entry.getValue());
                } else {
                    value = entry.getValue();
                }
                properties.setProperty(entry.getKey(), value);
            }
        }
        return CompletableFuture.completedStage(properties);
    }
}
