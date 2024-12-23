package build.buildbuddy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildExecutor {

    private final Path root;
    private final ChecksumDiff diff;

    private final TaskGraph<String, Map<String, BuildResult>> taskGraph = new TaskGraph<>((left, right) -> Stream
            .concat(left.entrySet().stream(), right.entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));

    public BuildExecutor(Path root, ChecksumDiff diff) {
        this.root = root;
        this.diff = diff;
    }

    public void addSource(String identity, Path path) {
        taskGraph.add(identity, wrapSource(identity, path));
    }

    public void replaceSource(String identity, Path path) {
        taskGraph.replace(identity, wrapSource(identity, path));
    }

    private BiFunction<Executor, Map<String, BuildResult>, CompletionStage<Map<String, BuildResult>>> wrapSource(
            String identity,
            Path path) {
        return (executor, states) -> {
            CompletableFuture<Map<String, BuildResult>> future = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    future.complete(Map.of(identity, new BuildResult(path, diff.read(
                            root.resolve(identity + ".diff"),
                            path))));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        };
    }

    public void addStep(String identity, BuildStep step, String... dependencies) {
        taskGraph.add(identity, wrapStep(identity, step) , dependencies);
    }

    public void replaceStep(String identity, BuildStep step) {
        taskGraph.replace(identity, wrapStep(identity, step));
    }

    private BiFunction<Executor, Map<String, BuildResult>, CompletionStage<Map<String, BuildResult>>> wrapStep(
            String identity,
            BuildStep step) {
        return (executor, states) -> {
            try {
                Path source = root.resolve(identity), target = Files.createTempDirectory(identity);
                return step.apply(executor, source, target, states).thenComposeAsync(result -> {
                    try {
                        System.out.println(identity + " -> " + result);
                        return CompletableFuture.completedStage(Map.of(identity, new BuildResult(source, diff.update(
                                root.resolve(identity + ".diff"),
                                Files.move(target, source, StandardCopyOption.REPLACE_EXISTING)))));
                    } catch (Throwable t) {
                        return CompletableFuture.failedFuture(t);
                    }
                }, executor);
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        };
    }

    public CompletionStage<Map<String, BuildResult>> execute(Executor executor) {
        return taskGraph.execute(executor, CompletableFuture.completedStage(Map.of()));
    }
}
