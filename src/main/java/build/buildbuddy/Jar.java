package build.buildbuddy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Jar implements ProcessBuildStep {

    private final String jar;

    public Jar() {
        jar = ProcessBuildStep.ofJavaHome("bin/jar");
    }

    public Jar(String jar) {
        this.jar = jar;
    }

    @Override
    public CompletionStage<ProcessBuilder> process(Executor executor,
                                                   BuildStepContext context,
                                                   Map<String, BuildStepArgument> arguments) {
        List<String> commands = new ArrayList<>(List.of(
                jar,
                "cf",
                context.next().resolve("artifact.jar").toString()
        ));
        arguments.values().forEach(result -> commands.add(result.folder().toString()));
        return CompletableFuture.completedStage(new ProcessBuilder(commands));
    }
}
