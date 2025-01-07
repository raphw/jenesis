package build.buildbuddy.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Bind implements BuildStep {

    private final Map<Path, Path> paths;

    public Bind(Map<Path, Path> paths) {
        this.paths = paths;
    }

    public static Bind asSources() {
        return new Bind(Map.of(Path.of("."), Path.of(SOURCES)));
    }

    public static Bind asResources() {
        return new Bind(Map.of(Path.of("."), Path.of(RESOURCES)));
    }

    public static Bind asCoordinates(String name) {
        return new Bind(Map.of(Path.of(name == null ? COORDINATES : name), Path.of(COORDINATES)));
    }

    public static Bind asDependencies(String name) {
        return new Bind(Map.of(Path.of(name), Path.of(DEPENDENCIES)));
    }

    public Bind with(Bind bind) {
        Map<Path, Path> paths = new HashMap<>(this.paths);
        paths.putAll(bind.paths);
        return new Bind(paths);
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            for (Map.Entry<Path, Path> entry : paths.entrySet()) {
                Path source = argument.folder().resolve(entry.getKey());
                if (Files.exists(source)) {
                    Path target = context.next().resolve(entry.getValue());
                    if (!Objects.equals(target.getParent(), context.next())) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.walkFileTree(source, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            Files.createDirectories(target.resolve(source.relativize(dir)));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            Files.createLink(target.resolve(source.relativize(file)), file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
