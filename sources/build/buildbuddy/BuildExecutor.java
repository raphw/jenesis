package build.buildbuddy;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildExecutor {

    private final Path root;
    private final HashFunction hash;

    final Map<String, Registration> registrations = new LinkedHashMap<>();

    public BuildExecutor(Path root, HashFunction hash) {
        this.root = root;
        this.hash = hash;
    }

    public void addSource(String identity, Path path) {
        add(identity, wrapSource(identity, path), Set.of());
    }

    public void replaceSource(String identity, Path path) {
        replace(identity, wrapSource(identity, path));
    }

    private BiFunction<Executor, Map<String, StepSummary>, CompletionStage<Map<String, StepSummary>>> wrapSource(
            String identity,
            Path path) {
        return (executor, _) -> {
            CompletableFuture<Map<String, StepSummary>> future = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    future.complete(Map.of(identity, new StepSummary(path, HashFunction.read(path, hash))));
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
        add(identity, wrapStep(identity, step), dependencies);
    }

    public void addStepAtEnd(String identity, BuildStep step) {
        addStep(identity, step, registrations.keySet());
    }

    public void replaceStep(String identity, BuildStep step) {
        replace(identity, wrapStep(identity, step));
    }

    private void add(String identity,
                     BiFunction<Executor, Map<String, StepSummary>, CompletionStage<Map<String, StepSummary>>> step,
                     Set<String> dependencies) {
        if (!registrations.keySet().containsAll(dependencies)) {
            throw new IllegalArgumentException("Unknown dependencies: " + dependencies.stream()
                    .filter(dependency -> !registrations.containsKey(dependency))
                    .distinct()
                    .toList());
        }
        if (registrations.putIfAbsent(identity, new Registration(step, dependencies)) != null) {
            throw new IllegalArgumentException("Step already registered: " + identity);
        }
    }

    private void replace(String identity, BiFunction<Executor,
            Map<String, StepSummary>,
            CompletionStage<Map<String, StepSummary>>> step) {
        Registration registration = registrations.get(identity);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown step: " + identity);
        }
        registrations.replace(identity, new Registration(step, registration.dependencies()));
    }

    private BiFunction<Executor, Map<String, StepSummary>, CompletionStage<Map<String, StepSummary>>> wrapStep(
            String identity,
            BuildStep step) {
        return (executor, states) -> {
            try {
                Path previous = root.resolve(identity),
                        checksum = previous.resolve("checksum"),
                        output = previous.resolve("output");
                boolean exists = Files.exists(previous);
                Map<Path, byte[]> current = exists ? HashFunction.read(checksum.resolve("checksums")) : Map.of();
                boolean consistent = exists && HashFunction.areConsistent(output, current, hash);
                Map<String, BuildStepArgument> arguments = new HashMap<>();
                for (Map.Entry<String, StepSummary> entry : states.entrySet()) {
                    Path checksums = checksum.resolve("checksums." + entry.getKey());
                    arguments.put(entry.getKey(), new BuildStepArgument(
                            entry.getValue().folder(),
                            consistent && Files.exists(checksums)
                                    ? ChecksumStatus.diff(HashFunction.read(checksums), entry.getValue().checksums())
                                    : ChecksumStatus.added(entry.getValue().checksums().keySet())));
                }
                if (!consistent
                        || step.isAlwaysRun()
                        || arguments.values().stream().anyMatch(BuildStepArgument::hasChanged)) {
                    Path next = Files.createTempDirectory(identity);
                    return step.apply(executor,
                            new BuildStepContext(
                                    consistent ? output : null,
                                    Files.createDirectory(next.resolve("output")),
                                    Files.createDirectory(next.resolve("supplement"))),
                            arguments).handleAsync((result, throwable) -> {
                        try {
                            if (throwable != null) {
                                Files.delete(Files.walkFileTree(next, new RecursiveFolderDeletion(next)));
                                throw throwable;
                            } else if (result.next()) {
                                Files.move(next, exists
                                        ? Files.walkFileTree(previous, new RecursiveFolderDeletion(next))
                                        : previous);
                                Files.createDirectory(checksum);
                            } else if (consistent) {
                                Files.delete(Files.walkFileTree(next, new RecursiveFolderDeletion(next)));
                                Files.walkFileTree(checksum, new RecursiveFolderDeletion(checksum));
                            } else {
                                throw new IllegalStateException("Cannot reuse non-existing location for " + identity);
                            }
                            for (Map.Entry<String, StepSummary> entry : states.entrySet()) {
                                HashFunction.write(
                                        checksum.resolve("checksums." + entry.getKey()),
                                        entry.getValue().checksums());
                            }
                            Map<Path, byte[]> checksums = HashFunction.read(output, hash);
                            HashFunction.write(checksum.resolve("checksums"), checksums);
                            return Map.of(identity, new StepSummary(output, checksums));
                        } catch (Throwable t) {
                            throw new CompletionException(t);
                        }
                    }, executor);
                } else {
                    return CompletableFuture.completedStage(Map.of(identity, new StepSummary(output, current)));
                }
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        };
    }

    public CompletionStage<Map<String, Path>> execute(Executor executor) {
        CompletionStage<Map<String, StepSummary>> initial = CompletableFuture.completedStage(Map.of());
        Map<String, Registration> pending = new LinkedHashMap<>(registrations);
        Map<String, CompletionStage<Map<String, StepSummary>>> dispatched = new HashMap<>();
        while (!pending.isEmpty()) {
            Iterator<Map.Entry<String, Registration>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Registration> entry = it.next();
                if (dispatched.keySet().containsAll(entry.getValue().dependencies())) {
                    CompletionStage<Map<String, StepSummary>> completionStage = initial;
                    for (String dependency : entry.getValue().dependencies()) {
                        completionStage = completionStage.thenCombineAsync(
                                dispatched.get(dependency),
                                (left, right) -> Stream
                                        .concat(left.entrySet().stream(), right.entrySet().stream())
                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                                executor);
                    }
                    dispatched.put(entry.getKey(), completionStage.thenComposeAsync(input -> entry.getValue()
                            .step()
                            .apply(executor, input), executor));
                    it.remove();
                }
            }
        }
        CompletionStage<Map<String, Path>> result = CompletableFuture.completedStage(Map.of());
        for (String identity : registrations.keySet()) {
            result = result.thenCombineAsync(dispatched.get(identity), (left, right) -> {
                Map<String, Path> folders = new LinkedHashMap<>(left); // TODO: return more complex result.
                for (Map.Entry<String, StepSummary> entry : right.entrySet()) {
                    folders.put(entry.getKey(), entry.getValue().folder());
                }
                return folders;
            }, executor);
        }
        return result;
    }

    private record Registration(BiFunction<Executor,
            Map<String, StepSummary>,
            CompletionStage<Map<String, StepSummary>>> step, Set<String> dependencies) {
    }

    private record StepSummary(Path folder, Map<Path, byte[]> checksums) {
    }

    private static class RecursiveFolderDeletion extends SimpleFileVisitor<Path> {

        private final Path root;

        private RecursiveFolderDeletion(Path root) {
            this.root = root;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (!dir.equals(root)) {
                Files.delete(dir);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
