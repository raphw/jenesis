package codes.rafael.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Javac implements BuildStep {

    @Override
    public CompletionStage<String> apply(Executor executor,
                                         Path previous,
                                         Path target,
                                         Map<String, BuildResult> dependencies) throws IOException {
        List<String> commands = new ArrayList<>(Arrays.asList(
            "javac",
            "--release",
            Runtime.version().toString(),
            "-d",
            target.toString()
        ));
        dependencies.values().forEach(dependency -> {
            commands.add("--source-path");
            commands.add(dependency.root().toString());
        });
        Process process = new ProcessBuilder(commands).start();
        CompletableFuture<String> future = new CompletableFuture<>();
        executor.execute(() -> {
            if (process.exitValue() == 0) {
                future.complete("Compiled from " + dependencies);
            } else {
                future.completeExceptionally(new IllegalStateException("Failed compilation"));
            }
        });
        return future;
    }
}
