package build.buildbuddy;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Resolve implements BuildStep {

    public static final String SOURCES = "sources/", RESOURCES = "resources/";

    private final Map<Path, Path> paths;

    public Resolve(Map<Path, Path> paths) {
        this.paths = paths;
    }

    public static BuildStep asSources() {
        return new Resolve(Map.of(Path.of("."), Path.of(SOURCES)));
    }

    public static BuildStep asResources() {
        return new Resolve(Map.of(Path.of("."), Path.of(RESOURCES)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            for (Map.Entry<Path, Path> entry : paths.entrySet()) {
                Path source = argument.folder().resolve(entry.getKey());
                if (Files.exists(source)) {
                    Path target = context.next().resolve(entry.getValue());
                    if (!Objects.equals(target.getParent(), context.next())) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.walkFileTree(source, new LinkingFileVisitor(source, target));
                }
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    public static class LinkingFileVisitor extends SimpleFileVisitor<Path> {

        private final Path source, target;

        public LinkingFileVisitor(Path source, Path target) {
            this.source = source;
            this.target = target;
        }

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
    }
}
