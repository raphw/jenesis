package build.buildbuddy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface ProcessBuildStep extends BuildStep {

    static String ofJavaHome(String command) {
        String home = System.getProperty("java.home");
        if (home == null) {
            throw new IllegalStateException("java.home property not set");
        } else {
            File javac = new File(home, command);
            if (javac.isFile()) {
                return javac.getPath();
            } else {
                throw new IllegalStateException("Could not find command " + command + " in " + home);
            }
        }
    }

    CompletionStage<ProcessBuilder> process(Executor executor,
                                            Path previous,
                                            Path target,
                                            Map<String, BuildResult> dependencies) throws IOException;

    @Override
    default CompletionStage<String> apply(Executor executor,
                                          Path previous,
                                          Path target,
                                          Map<String, BuildResult> dependencies) throws IOException {
        return process(executor, previous, target, dependencies).thenComposeAsync(builder -> {
            System.out.println(String.join(" ", builder.command()));
            CompletableFuture<String> future = new CompletableFuture<>();
            try {
                Process process = builder.inheritIO().start();
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
