package codes.rafael.buildbuddy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildExecutor {

    private final Path root;
    private final ChecksumDiff diff;

    private final TaskGraph<String, Map<String, Map<Path, ChecksumStatus>>> taskGraph = new TaskGraph<>((left, right) -> Stream.concat(
        left.entrySet().stream(),
        right.entrySet().stream()
    ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

    public BuildExecutor(Path root, ChecksumDiff diff) {
        this.root = root;
        this.diff = diff;
    }

    public void source(String identity, Path path) {
        taskGraph.add(identity, (executor, states) -> {
            CompletableFuture<Map<String, Map<Path, ChecksumStatus>>> future = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    future.complete(Map.of(identity, diff.read(root.resolve(identity + ".diff"), path)));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        });
    }

    public void task(String identity, BiFunction<Executor, Map<String, Map<Path, ChecksumStatus>>, CompletionStage<Set<Path>>> task, String... dependencies) {
        taskGraph.add(identity, (executor, states) -> task.apply(executor, states).thenApplyAsync(paths -> {
            try {
                return Map.of(identity, diff.update(root.resolve(identity + ".diff"), Files.createDirectory(root.resolve(identity))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, executor), dependencies);
    }

    public void execute(Executor executor) {
        taskGraph.execute(executor, CompletableFuture.completedStage(Map.of()));
    }
}
