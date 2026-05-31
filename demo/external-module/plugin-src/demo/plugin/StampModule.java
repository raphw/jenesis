package demo.plugin;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStepResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;

/**
 * A build module published as a stand-alone artifact. Unlike the InternalModule
 * demo (which compiles the plugin from local source), here Jenesis resolves the
 * plugin from a repository by coordinate, downloads its jar, loads the service
 * provider, and runs it.
 */
public class StampModule implements BuildExecutorModule {

    @Override
    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
        executor.addStep("stamp", (runner, context, arguments) -> {
            Files.writeString(context.next().resolve("stamp.txt"),
                    "Hello from an ExternalModule plugin, resolved from a repository coordinate!");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        });
    }
}
