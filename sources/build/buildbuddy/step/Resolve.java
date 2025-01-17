package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.SequencedProperties;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

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
                    repositories.getOrDefault(group.getKey(), Repository.empty()),
                    group.getValue().sequencedKeySet()).entrySet()) {
                properties.setProperty(
                        group.getKey() + "/" + entry.getKey(),
                        group.getValue().getOrDefault(entry.getKey(), entry.getValue()));
            }
        }
        return CompletableFuture.completedStage(properties);
    }
}
