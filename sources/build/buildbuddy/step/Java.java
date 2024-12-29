package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public abstract class Java implements ProcessBuildStep {

    private final String java;
    protected boolean modular = true;

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
                                                             SequencedMap<String, BuildStepArgument> arguments) {
                return CompletableFuture.completedStage(commands);
            }
        };
    }

    public Java modular(boolean modular) {
        this.modular = modular;
        return this;
    }

    protected abstract CompletionStage<List<String>> commands(Executor executor,
                                                              BuildStepContext context,
                                                              SequencedMap<String, BuildStepArgument> arguments) throws IOException;

    @Override
    public CompletionStage<ProcessBuilder> process(Executor executor,
                                                   BuildStepContext context,
                                                   SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<String> classPath = new ArrayList<>(), modulePath = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            for (String folder : List.of(Javac.CLASSES, Bind.RESOURCES)) {
                Path candidate = argument.folder().resolve(folder);
                if (Files.isDirectory(candidate)) {
                    if (modular && Files.exists(candidate.resolve("module-info.class"))) {
                        classPath.add(candidate.toString());
                    } else {
                        classPath.add(candidate.toString());
                    }
                }
            }
            Path candidate = argument.folder().resolve(ARTIFACTS);
            if (Files.exists(candidate)) {
                Files.walkFileTree(candidate, new FileAddingVisitor(modular ? modulePath : classPath));
            }
        }
        List<String> prefixes = new ArrayList<>();
        prefixes.add(java); // TODO: better automatic module path resolution?
        if (!classPath.isEmpty()) {
            prefixes.add("-classpath");
            prefixes.add(String.join(File.pathSeparator, classPath));
        }
        if (!modulePath.isEmpty()) {
            prefixes.add("--module-path");
            prefixes.add(String.join(File.pathSeparator, modulePath));
        }
        return commands(executor, context, arguments).thenApplyAsync(commands -> new ProcessBuilder(Stream.concat(
                prefixes.stream(),
                commands.stream()).toList()), executor);
    }

    @Override
    public ProcessBuilder prepare(ProcessBuilder builder,
                                  Executor executor,
                                  BuildStepContext context,
                                  SequencedMap<String, BuildStepArgument> arguments) {
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
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs){
            target.add(file.toString());
            return FileVisitResult.CONTINUE;
        }
    }
}
