package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.ProcessBuildStep;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
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
        java = ProcessBuildStep.ofJavaHome("bin/java" + (WINDOWS ? ".exe" : ""));
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
        List<String> classes = new ArrayList<>(), modules = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            for (String folder : List.of(Javac.CLASSES, Bind.RESOURCES)) {
                Path candidate = argument.folder().resolve(folder);
                if (Files.isDirectory(candidate)) {
                    if (Files.exists(candidate.resolve("module-info.java"))) {
                        modules.add(candidate.toString());
                    } else {
                        classes.add(candidate.toString());
                    }
                }
            }
            for (String folder : List.of(Dependencies.LIBS, Jar.JARS)) { // TODO: resolve modules and class path
                Path candidate = argument.folder().resolve(folder);
                if (Files.exists(candidate)) {
                    Files.walkFileTree(candidate, new FileAddingVisitor(modules));
                }
            }
        }
        List<String> prefixes = new ArrayList<>();
        prefixes.add(java);
        if (!classes.isEmpty()) {
            prefixes.add("-classpath");
            prefixes.add(String.join(File.pathSeparator, classes));
        }
        if (!modules.isEmpty()) {
            prefixes.add("--module-path");
            prefixes.add(String.join(File.pathSeparator, modules));
        }
        return commands(executor, context, arguments).thenApplyAsync(commands -> new ProcessBuilder(Stream.concat(
                prefixes.stream(),
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

    private static class FileAddingVisitor extends SimpleFileVisitor<Path> {

        private final List<String> target;

        private FileAddingVisitor(List<String> target) {
            this.target = target;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            target.add(file.toString());
            return FileVisitResult.CONTINUE;
        }
    }
}
