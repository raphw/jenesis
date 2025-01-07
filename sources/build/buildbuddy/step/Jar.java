package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Jar implements ProcessBuildStep {

    private final String jar;

    public Jar() {
        jar = ProcessBuildStep.ofJavaHome("bin/jar" + (WINDOWS ? ".exe" : ""));
    }

    public Jar(String jar) {
        this.jar = jar;
    }

    @Override
    public CompletionStage<ProcessBuilder> process(Executor executor,
                                                   BuildStepContext context,
                                                   SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<String> commands = new ArrayList<>(List.of(jar,
                "cf",
                Files.createDirectory(context.next().resolve(ARTIFACTS)).resolve("classes.jar").toString()));
        for (BuildStepArgument argument : arguments.values()) {
            for (String name : List.of(Javac.CLASSES, Bind.RESOURCES)) {
                Path folder = argument.folder().resolve(name);
                if (Files.exists(folder)) {
                    commands.add("-C");
                    commands.add(folder.toString());
                    commands.add(".");
                }
            }
        }
        return CompletableFuture.completedStage(new ProcessBuilder(commands));
    }
}
