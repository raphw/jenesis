package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
                                                   SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        Path target = Files.createDirectory(context.next().resolve(CLASSES));
        List<String> files = new ArrayList<>(), classPath = new ArrayList<>(), commands = new ArrayList<>(List.of(javac,
                "--release", Integer.toString(Runtime.version().version().getFirst()),
                "-d", target.toString()));
        for (BuildStepArgument argument : arguments.values()) {
            Path sources = argument.folder().resolve(Bind.SOURCES),
                    classes = argument.folder().resolve(CLASSES),
                    artifacts = argument.folder().resolve(ARTIFACTS);
            if (Files.exists(classes)) {
                classPath.add(classes.toString());
            }
            if (Files.exists(artifacts)) {
                Files.walkFileTree(artifacts, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        classPath.add(file.toString());
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            if (Files.exists(sources)) {
                Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(target.resolve(sources.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String name = file.toString();
                        if (name.endsWith(".java")) {
                            files.add(name);
                        } else {
                            Files.createLink(target.resolve(sources.relativize(file)), file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        if (!classPath.isEmpty()) {
            commands.add("--module-path"); // TODO: distinguish module and class path properly
            commands.add(String.join(File.pathSeparator, classPath)); // TODO: escape path
        }
        commands.addAll(files);
        return CompletableFuture.completedStage(new ProcessBuilder(commands));
    }
}
