package build.buildbuddy;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildExecutor {

    private final Path root;
    private final HashFunction hashFunction;

    private final TaskGraph<String, Map<String, BuildStatus>> taskGraph = new TaskGraph<>((left, right) -> Stream
            .concat(left.entrySet().stream(), right.entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

    public BuildExecutor(Path root, HashFunction hashFunction) {
        this.root = root;
        this.hashFunction = hashFunction;
    }

    public void addSource(String identity, Path path) {
        taskGraph.add(identity, wrapSource(identity, path), Set.of());
    }

    public void replaceSource(String identity, Path path) {
        taskGraph.replace(identity, wrapSource(identity, path));
    }

    private BiFunction<Executor, Map<String, BuildStatus>, CompletionStage<Map<String, BuildStatus>>> wrapSource(
            String identity,
            Path path) {
        return (executor, states) -> {
            CompletableFuture<Map<String, BuildStatus>> future = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    future.complete(Map.of(identity, new BuildStatus(path, HashFunction.read(path))));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        };
    }

    public void addStep(String identity, BuildStep step, String... dependencies) {
        addStep(identity, step, Set.of(dependencies));
    }

    public void addStep(String identity, BuildStep step, Set<String> dependencies) {
        taskGraph.add(identity, wrapStep(identity, step), dependencies);
    }

    public void addStepAtEnd(String identity, BuildStep step) {
        addStep(identity, step, taskGraph.registrations.keySet());
    }

    public void replaceStep(String identity, BuildStep step) {
        taskGraph.replace(identity, wrapStep(identity, step));
    }

    private BiFunction<Executor, Map<String, BuildStatus>, CompletionStage<Map<String, BuildStatus>>> wrapStep(
            String identity,
            BuildStep step) {
        return (executor, states) -> {
            try {
                Path current = Files.createDirectory(root.resolve(identity)),
                        output = Files.createDirectory(current.resolve("output")),
                        checksum = Files.createDirectory(current.resolve("checksum"));
                Map<String, BuildResult> dependencies = new HashMap<>();
                for (Map.Entry<String, BuildStatus> entry : states.entrySet()) {
                    Path checksums = checksum.resolve("checksums." + entry.getKey());
                    dependencies.put(entry.getKey(), new BuildResult(entry.getValue().folder(), Files.exists(checksums)
                            ? ChecksumStatus.diff(HashFunction.read(checksums), entry.getValue().checksums())
                            : ChecksumStatus.added(entry.getValue().checksums().keySet())));
                }
                if (step.isAlwaysRun() || dependencies.values().stream().anyMatch(BuildResult::isChanged)) {
                    Path target = Files.createTempDirectory(identity);
                    return step.apply(executor, output, target, dependencies).handleAsync((handled, throwable) -> {
                        try {
                            Files.walkFileTree(checksum, new RecursiveFileDeletion());
                            if (throwable != null) {
                                Files.delete(Files.walkFileTree(target, new RecursiveFileDeletion()));
                                throw throwable;
                            } else if (handled) {
                                Files.move(target, output, StandardCopyOption.REPLACE_EXISTING);
                            } else {
                                Files.delete(target);
                            }
                            for (Map.Entry<String, BuildResult> entry : dependencies.entrySet()) {
                                Files.copy(
                                        root.resolve(entry.getKey() + "/checksum/checksums"),
                                        checksum.resolve("checksums." + entry.getKey()));
                            }
                            Map<Path, byte[]> checksums = HashFunction.read(output, hashFunction);
                            HashFunction.write(checksum.resolve("checksums"), checksums);
                            return Map.of(identity, new BuildStatus(output, checksums));
                        } catch (Throwable t) {
                            throw new CompletionException(t);
                        }
                    }, executor);
                } else {
                    return CompletableFuture.completedStage(Map.of(identity, new BuildStatus(
                            output,
                            HashFunction.read(output))));
                }
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        };
    }

    public CompletionStage<Map<String, BuildResult>> execute(Executor executor) {
        taskGraph.execute(executor, CompletableFuture.completedStage(Map.of()));
        return null;
    }

    private record BuildStatus(Path folder, Map<Path, byte[]> checksums) { }

    private static class RecursiveFileDeletion extends SimpleFileVisitor<Path> {

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
        }
    }
}
