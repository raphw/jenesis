package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.ProcessBuildStep;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

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
        List<String> files = new ArrayList<>(), classPath = new ArrayList<>(), commands = new ArrayList<>(List.of(javac,
                "--release", Integer.toString(Runtime.version().version().getFirst()),
                "-d", Files.createDirectory(context.next().resolve(CLASSES)).toString()));
        for (BuildStepArgument argument : arguments.values()) {
            Path sources = argument.folder().resolve(Bind.SOURCES),
                    classes = argument.folder().resolve(CLASSES),
                    jars = argument.folder().resolve(Jar.JARS),
                    libs = argument.folder().resolve(Dependencies.LIBS);
            if (Files.exists(classes)) {
                classPath.add(classes.toString());
            }
            if (Files.exists(jars)) {
                Files.walkFileTree(jars, new FileExtensionAddingVisitor(classPath, ".jar"));
            }
            if (Files.exists(libs)) {
                Files.walkFileTree(libs, new FileExtensionAddingVisitor(classPath, ".jar"));
            }
            if (Files.exists(sources)) {
                Files.walkFileTree(sources, new FileExtensionAddingVisitor(files, ".java"));
            }
        }
        if (!classPath.isEmpty()) {
            commands.add("--module-path"); // TODO: distinguish module and class path properly
            commands.add(String.join(File.pathSeparator, classPath)); // TODO: escape path
        }
        commands.addAll(files);
        return CompletableFuture.completedStage(new ProcessBuilder(commands));
    }

    private static class FileExtensionAddingVisitor extends SimpleFileVisitor<Path> {

        private final List<String> target;
        private final String extension;

        private FileExtensionAddingVisitor(List<String> target, String extension) {
            this.target = target;
            this.extension = extension;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file.getFileName().toString().endsWith(extension)) {
                target.add(file.toString());
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
