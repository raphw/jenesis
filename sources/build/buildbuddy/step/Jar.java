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
import java.util.function.Function;

public class Jar extends ProcessBuildStep {

    public Jar(Function<List<String>, ? extends ProcessHandler> factory) {
        super(factory);
    }

    public static Jar tool() {
        return new Jar(ProcessHandler.OfTool.of("jar"));
    }

    public static Jar process() {
        return new Jar(ProcessHandler.OfProcess.ofJavaHome("bin/jar"));
    }

    @Override
    public CompletionStage<List<String>> process(Executor executor,
                                                   BuildStepContext context,
                                                   SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<String> commands = new ArrayList<>(List.of("cf", Files
                .createDirectory(context.next().resolve(ARTIFACTS))
                .resolve("classes.jar")
                .toString()));
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
        return CompletableFuture.completedStage(commands);
    }
}
