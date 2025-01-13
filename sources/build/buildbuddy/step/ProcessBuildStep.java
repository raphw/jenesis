package build.buildbuddy.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface ProcessBuildStep extends BuildStep {

    boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    static String ofJavaHome(String command) {
        String home = System.getProperty("java.home");
        if (home == null) {
            home = System.getenv("JAVA_HOME");
        }
        if (home == null) {
            throw new IllegalStateException("Neither JAVA_HOME environment or java.home property set");
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
                                            BuildStepContext context,
                                            SequencedMap<String, BuildStepArgument> arguments) throws IOException;

    default ProcessBuilder prepare(ProcessBuilder builder,
                                   Executor executor,
                                   BuildStepContext context,
                                   SequencedMap<String, BuildStepArgument> arguments) {
        return builder.inheritIO();
    }

    default boolean acceptableExitCode(int code,
                                       Executor executor,
                                       BuildStepContext context,
                                       SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        return code == 0;
    }

    @Override
    default CompletionStage<BuildStepResult> apply(Executor executor,
                                                   BuildStepContext context,
                                                   SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        return process(executor, context, arguments).thenComposeAsync(builder -> {
            CompletableFuture<BuildStepResult> future = new CompletableFuture<>();
            try {
                ProcessBuilder prepared = prepare(builder, executor, context, arguments);
                Process process = prepared.start();
                executor.execute(() -> {
                    try {
                        if (acceptableExitCode(process.waitFor(), executor, context, arguments)) {
                            future.complete(new BuildStepResult(true));
                        } else {
                            String output = Files.exists(context.supplement().resolve("output"))
                                    ? Files.readString(context.supplement().resolve("output"))
                                    : "";
                            String error = Files.exists(context.supplement().resolve("error"))
                                    ? Files.readString(context.supplement().resolve("error"))
                                    : "";
                            throw new IllegalStateException("Unexpected exit code: " + process.exitValue() + "\n"
                                    + "To reproduce, execute:\n " + String.join(" ", prepared.command())
                                    + (output.isBlank() ? "" : ("\n\nOutput:\n" + output))
                                    + (error.isBlank() ? "" : ("\n\nError:\n" + error)));
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
