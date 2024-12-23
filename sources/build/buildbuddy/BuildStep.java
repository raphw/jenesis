package build.buildbuddy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

@FunctionalInterface
public interface BuildStep {

    default boolean isAlwaysRun() {
        return false;
    }

    CompletionStage<BuildStepResult> apply(Executor executor,
                                           BuildStepContext context,
                                           Map<String, BuildStepArgument> arguments) throws IOException;

    default BuildStep without(String... identities) {
        return new BuildStep() {
            @Override
            public boolean isAlwaysRun() {
                return BuildStep.this.isAlwaysRun();
            }

            @Override
            public CompletionStage<BuildStepResult> apply(Executor executor,
                                                          BuildStepContext context,
                                                          Map<String, BuildStepArgument> arguments) throws IOException {
                Map<String, BuildStepArgument> reduction = new HashMap<>(arguments);
                Stream.of(identities).forEach(reduction.keySet()::remove);
                return BuildStep.this.isAlwaysRun()
                        || context.previous() == null
                        || reduction.values().stream().anyMatch(BuildStepArgument::hasChanged)
                        ? BuildStep.this.apply(executor, context, reduction)
                        : CompletableFuture.completedStage(new BuildStepResult(false));
            }
        };
    }
}
