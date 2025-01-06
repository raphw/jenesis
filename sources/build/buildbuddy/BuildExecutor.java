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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildExecutor {

    private static final Pattern VALIDATE = Pattern.compile("[a-zA-Z0-9]+");

    private final Path root;
    private final HashFunction hash;

    final SequencedMap<String, Registration> registrations = new LinkedHashMap<>();

    private BuildExecutor(Path root, HashFunction hash) {
        this.root = root;
        this.hash = hash;
    }

    public static BuildExecutor of(Path root, HashFunction hash) throws IOException {
        return new BuildExecutor(Files.isDirectory(root) ? root : Files.createDirectory(root), hash);
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

    public void addStep(String identity, BuildStep step, SequencedSet<String> dependencies) {
        add(identity, wrapStep(identity, step), dependencies);
    }

    public void addStepAtEnd(String identity, BuildStep step) {
        addStep(identity, step, new LinkedHashSet<>(registrations.keySet()));
    }

    private void addStep(String identity, BuildStep step, Set<String> dependencies) {
        add(identity, wrapStep(identity, step), dependencies);
    }

    public void replaceStep(String identity, BuildStep step) {
        replace(identity, wrapStep(identity, step));
    }

    public void appendStep(String identity, BuildStep step) {
        append(identity, wrapStep(identity, step));
    }

    private BiFunction<Executor,
            Map<String, StepSummary>,
            CompletionStage<Map<String, StepSummary>>> wrapStep(String identity, BuildStep step) {
        return (executor, summaries) -> {
            try {
                Path previous = root.resolve(identity),
                        checksum = previous.resolve("checksum"),
                        output = previous.resolve("output");
                boolean exists = Files.exists(previous);
                Map<Path, byte[]> current = exists ? HashFunction.read(checksum.resolve("checksums")) : Map.of();
                boolean consistent = exists && HashFunction.areConsistent(output, current, hash);
                SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
                for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
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
                                        ? Files.walkFileTree(previous, new RecursiveFolderDeletion(null))
                                        : previous);
                                Files.createDirectory(checksum);
                            } else if (consistent) {
                                Files.delete(Files.walkFileTree(next, new RecursiveFolderDeletion(next)));
                                Files.walkFileTree(checksum, new RecursiveFolderDeletion(checksum));
                            } else {
                                throw new IllegalStateException("Cannot reuse non-existing location for " + identity);
                            }
                            for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
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

    public void add(String identity, IOConsumer consumer, String... dependencies) {
        add(identity, wrapConsumer(identity, consumer), Set.of(dependencies));
    }

    public void add(String identity, IOConsumer consumer, SequencedSet<String> dependencies) {
        add(identity, wrapConsumer(identity, consumer), dependencies);
    }

    public void addAtEnd(String identity, IOConsumer consumer) {
        add(identity, wrapConsumer(identity, consumer), new LinkedHashSet<>(registrations.keySet()));
    }

    public void replace(String identity, IOConsumer consumer) {
        replace(identity, wrapConsumer(identity, consumer));
    }

    public void append(String identity, IOConsumer consumer) {
        append(identity, wrapConsumer(identity, consumer));
    }

    private BiFunction<Executor,
            Map<String, StepSummary>,
            CompletionStage<Map<String, StepSummary>>> wrapConsumer(String prefix, IOConsumer consumer) {
        return (executor, summaries) -> {
            try {
                SequencedMap<String, Path> folders = new LinkedHashMap<>();
                for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                    folders.put(entry.getKey(), entry.getValue().folder());
                }
                BuildExecutor buildExecutor = of(root.resolve(prefix), hash);
                consumer.accept(buildExecutor, folders);
                return buildExecutor.execute(executor, summaries).thenApplyAsync(results -> {
                    SequencedMap<String, StepSummary> prefixed = new LinkedHashMap<>();
                    results.forEach((identity, values) -> prefixed.put(prefix + "/" + identity, values));
                    return prefixed;
                }, executor);
            } catch (Throwable t) {
                return CompletableFuture.failedStage(t);
            }
        };
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
        if (registrations.putIfAbsent(validated(identity), new Registration(step, dependencies)) != null) {
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

    private void append(String identity, BiFunction<Executor,
            Map<String, StepSummary>,
            CompletionStage<Map<String, StepSummary>>> step) {
        Registration registration = registrations.remove(identity);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown step: " + identity);
        }
        int index = 0;
        String previous;
        do {
            previous = identity + "_" + index++;
        } while (registrations.containsKey(previous));
        registrations.put(previous, registration);
        registrations.put(identity, new Registration(step, Set.of(previous)));
    }

    public CompletionStage<SequencedMap<String, Path>> execute(Executor executor) {
        return execute(executor, Map.of()).thenApplyAsync(summaries -> {
            SequencedMap<String, Path> translated = new LinkedHashMap<>();
            for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                translated.put(entry.getKey(), entry.getValue().folder());
            }
            return translated;
        }, executor);
    }

    private CompletionStage<Map<String, StepSummary>> execute(Executor executor, Map<String, StepSummary> initials) {
        CompletionStage<Map<String, StepSummary>> initial = CompletableFuture.completedStage(Map.of());
        Map<String, Registration> pending = new LinkedHashMap<>(registrations);
        Map<String, CompletionStage<Map<String, StepSummary>>> dispatched = new LinkedHashMap<>();
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
                    dispatched.put(entry.getKey(), completionStage.thenComposeAsync(summaries -> {
                        Map<String, StepSummary> merged = new LinkedHashMap<>(initials);
                        merged.putAll(summaries);
                        return entry.getValue().step().apply(executor, merged);
                    }, executor));
                    it.remove();
                }
            }
        }
        CompletionStage<Map<String, StepSummary>> result = CompletableFuture.completedStage(Map.of());
        for (String identity : registrations.keySet()) {
            result = result.thenCombineAsync(dispatched.get(identity), (left, right) -> {
                SequencedMap<String, StepSummary> merged = new LinkedHashMap<>(left);
                merged.putAll(right);
                return merged;
            }, executor);
        }
        return result;
    }

    private static String validated(String identity) {
        if (VALIDATE.matcher(identity).matches()) {
            return identity;
        }
        throw new IllegalArgumentException("Identity '" + identity + "' does not match pattern: " + VALIDATE.pattern());
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

    @FunctionalInterface
    public interface IOConsumer {

        void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> paths) throws IOException;
    }
}
