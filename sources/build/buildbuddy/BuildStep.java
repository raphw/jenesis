package build.buildbuddy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface BuildStep {

    String SOURCES = "sources/", RESOURCES = "resources/", CLASSES = "classes/", ARTIFACTS = "artifacts/";
    String COORDINATES = "coordinates.properties", DEPENDENCIES = "dependencies.properties";

    default boolean isAlwaysRun() {
        return false;
    }

    CompletionStage<BuildStepResult> apply(Executor executor,
                                           BuildStepContext context,
                                           SequencedMap<String, BuildStepArgument> arguments) throws IOException;

    default BuildStep without(String... identities) {
        return without(Set.of(identities));
    }

    default BuildStep without(Set<String> identities) {
        return new BuildStep() {
            @Override
            public boolean isAlwaysRun() {
                return BuildStep.this.isAlwaysRun();
            }

            @Override
            public CompletionStage<BuildStepResult> apply(Executor executor,
                                                          BuildStepContext context,
                                                          SequencedMap<String, BuildStepArgument> arguments)
                    throws IOException {
                SequencedMap<String, BuildStepArgument> reduction = new LinkedHashMap<>(arguments);
                identities.forEach(reduction.keySet()::remove);
                return BuildStep.this.isAlwaysRun()
                        || context.previous() == null
                        || reduction.values().stream().anyMatch(BuildStepArgument::hasChanged)
                        ? BuildStep.this.apply(executor, context, reduction)
                        : CompletableFuture.completedStage(new BuildStepResult(false));
            }
        };
    }
}
