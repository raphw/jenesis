package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public abstract class AbstractProcessBuildStep implements BuildStep {

    protected abstract CompletionStage<ProcessBuilder> process(Executor executor,
                                                               Path previous,
                                                               Path target,
                                                               Map<String, BuildResult> dependencies) throws IOException;

    @Override
    public CompletionStage<String> apply(Executor executor,
                                         Path previous,
                                         Path target,
                                         Map<String, BuildResult> dependencies) throws IOException {
        return process(executor, previous, target, dependencies).thenComposeAsync(builder -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            try {
                Process process = builder.start();
                executor.execute(() -> {
                    try {
                        if (process.waitFor() == 0) {
                            future.complete("Compiled from " + dependencies);
                        } else {
                            throw new IllegalStateException("Unexpected exit code: " + process.exitValue());
                        }
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
                return future;
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
            return future;
        });
    }
}
