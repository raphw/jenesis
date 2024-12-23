package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.ProcessBuildStep;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Javac implements ProcessBuildStep {

    public static final String CLASSES = "classes/";

    private final String javac;

    public Javac() {
        javac = ProcessBuildStep.ofJavaHome("bin/javac" + (WINDOWS ? ".exe" : ""));
    }

    public Javac(String javac) {
        this.javac = javac;
    }

    @Override
    public CompletionStage<ProcessBuilder> process(Executor executor,
                                                   BuildStepContext context,
                                                   Map<String, BuildStepArgument> arguments) throws IOException {
        List<String> commands = new ArrayList<>(List.of(javac,
                "--release", Integer.toString(Runtime.version().version().getFirst()),
                "-d", Files.createDirectory(context.next().resolve(CLASSES)).toString()));
        for (BuildStepArgument argument : arguments.values()) {
            Path sources = argument.folder().resolve(Bind.SOURCES);
            Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".java")) {
                        commands.add(file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return CompletableFuture.completedStage(new ProcessBuilder(commands));
    }
}
