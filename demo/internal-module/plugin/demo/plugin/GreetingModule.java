package demo.plugin;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStepResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;

/**
 * A build module that Jenesis loads as a plugin. It is an ordinary modular
 * project ({@code module-info.java} with a {@code provides
 * build.jenesis.BuildExecutorModule}); {@code InternalModule} compiles it from
 * source, loads the service provider in an isolated module layer, and runs its
 * {@link #accept} against the host build graph.
 */
public class GreetingModule implements BuildExecutorModule {

    @Override
    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
        executor.addStep("greeting", (runner, context, arguments) -> {
            Files.writeString(context.next().resolve("greeting.txt"),
                    "Hello from an InternalModule plugin, compiled from local sources!");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        });
    }
}
