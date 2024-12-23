package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

public abstract class Java implements ProcessBuildStep {

    private final String java;

    protected Java() {
        java = ProcessBuildStep.ofJavaHome("bin/java");
    }

    protected Java(String java) {
        this.java = java;
    }

    public static Java ofArguments(String... arguments) {
        return ofArguments(List.of(arguments));
    }

    public static Java ofArguments(List<String> arguments) {
        return new Java() {
            @Override
            protected CompletionStage<List<String>> arguments(Executor executor,
                                                              Path previous,
                                                              Path target,
                                                              Map<String, BuildResult> dependencies) {
                return CompletableFuture.completedStage(arguments);
            }
        };
    }

    protected abstract CompletionStage<List<String>> arguments(Executor executor,
                                                               Path previous,
                                                               Path target,
                                                               Map<String, BuildResult> dependencies) throws IOException;

    @Override
    public CompletionStage<ProcessBuilder> process(Executor executor,
                                                   Path previous,
                                                   Path target,
                                                   Map<String, BuildResult> dependencies) throws IOException {
        return arguments(executor, previous, target, dependencies).thenApplyAsync(arguments -> {
            List<String> commands = new ArrayList<>(List.of(
                    java,
                    "--class-path", dependencies.values().stream()
                            .map(result -> result.folder().toString())
                            .collect(Collectors.joining(":"))
            ));
            commands.addAll(arguments);
            return new ProcessBuilder(commands);
        }, executor);
    }

    @Override
    public ProcessBuilder prepare(ProcessBuilder builder,
                                  Executor executor,
                                  Path previous,
                                  Path target,
                                  Map<String, BuildResult> dependencies) {
        return builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(target.resolve("output").toFile())
                .redirectError(target.resolve("error").toFile());
    }
}
