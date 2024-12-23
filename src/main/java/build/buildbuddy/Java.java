package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public abstract class Java implements ProcessBuildStep {

    private final String java;

    protected Java() {
        java = ProcessBuildStep.ofJavaHome("bin/java");
    }

    protected Java(String java) {
        this.java = java;
    }

    public static Java of(String... commands) {
        return of(List.of(commands));
    }

    public static Java of(List<String> commands) {
        return new Java() {
            @Override
            protected CompletionStage<List<String>> commands(Executor executor,
                                                             BuildStepContext context,
                                                             Map<String, BuildStepArgument> arguments) {
                return CompletableFuture.completedStage(commands);
            }
        };
    }

    protected abstract CompletionStage<List<String>> commands(Executor executor,
                                                              BuildStepContext context,
                                                              Map<String, BuildStepArgument> arguments) throws IOException;

    @Override
    public CompletionStage<ProcessBuilder> process(Executor executor,
                                                   BuildStepContext context,
                                                   Map<String, BuildStepArgument> arguments) throws IOException {
        List<String> classPath = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            for (String folder : List.of(Javac.FOLDER, "resources/", Dependencies.FOLDER, Jar.FOLDER)) {
                Path candidate = argument.folder().resolve(folder);
                if (Files.exists(candidate)) {
                    classPath.add(candidate.toString());
                }
            }
        }
        return commands(executor, context, arguments).thenApplyAsync(commands -> new ProcessBuilder(Stream.concat(
                Stream.of(java, "--class-path", String.join(":", classPath)),
                commands.stream()).toList()), executor);
    }

    @Override
    public ProcessBuilder prepare(ProcessBuilder builder,
                                  Executor executor,
                                  BuildStepContext context,
                                  Map<String, BuildStepArgument> arguments) {
        return builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(context.supplement().resolve("output").toFile())
                .redirectError(context.supplement().resolve("error").toFile());
    }
}
