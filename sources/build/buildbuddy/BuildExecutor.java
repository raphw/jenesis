package build.buildbuddy;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BuildExecutor {

    private static final Pattern VALIDATE = Pattern.compile("[a-zA-Z0-9]+");

    private final Path root;
    private final HashFunction hash;

    private final Map<String, StepSummary> inherited;
    private final SequencedMap<String, Registration> registrations = new LinkedHashMap<>();

    private BuildExecutor(Path root, HashFunction hash, Map<String, StepSummary> inherited) throws IOException {
        this.root = Files.isDirectory(root) ? root : Files.createDirectory(root);
        this.hash = hash;
        this.inherited = inherited;
    }

    public static BuildExecutor of(Path root, HashFunction hash) throws IOException {
        return new BuildExecutor(root, hash, Map.of());
    }

    public void addSource(String identity, Path path) {
        add(identity, bindSource(path), Set.of());
    }

    public void replaceSource(String identity, Path path) {
        replace(identity, bindSource(path));
    }

    private Bound bindSource(Path path) {
        return (identity, executor, _) -> {
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
        add(identity, bindStep(step), Set.of(dependencies));
    }

    public void addStep(String identity, BuildStep step, SequencedSet<String> dependencies) {
        add(identity, bindStep(step), dependencies);
    }

    public void addStepAtEnd(String identity, BuildStep step) {
        addStep(identity, step, new LinkedHashSet<>(registrations.keySet()));
    }

    public void replaceStep(String identity, BuildStep step) {
        replace(identity, bindStep(step));
    }

    public void prependStep(String identity, String prepended, BuildStep step) {
        prepend(identity, prepended, bindStep(step));
    }

    public void appendStep(String identity, String original, BuildStep step) {
        append(identity, original, bindStep(step));
    }

    private Bound bindStep(BuildStep step) {
        return (identity, executor, summaries) -> {
            try {
                Path previous = root.resolve(URLEncoder.encode(identity, StandardCharsets.UTF_8)),
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
                    Path next = Files.createTempDirectory(URLEncoder.encode(identity, StandardCharsets.UTF_8));
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
                                        checksum.resolve("checksums." + URLEncoder.encode(
                                                entry.getKey(),
                                                StandardCharsets.UTF_8)),
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
        add(identity, bindConsumer(consumer), Set.of(dependencies));
    }

    public void add(String identity, IOConsumer consumer, SequencedSet<String> dependencies) {
        add(identity, bindConsumer(consumer), dependencies);
    }

    public void addAtEnd(String identity, IOConsumer consumer) {
        add(identity, bindConsumer(consumer), new LinkedHashSet<>(registrations.keySet()));
    }

    public void replace(String identity, IOConsumer consumer) {
        replace(identity, bindConsumer(consumer));
    }

    public void prepend(String identity, String prepended, IOConsumer consumer) {
        prepend(identity, prepended, bindConsumer(consumer));
    }

    public void append(String identity, String appended, IOConsumer consumer) {
        append(identity, appended, bindConsumer(consumer));
    }

    private Bound bindConsumer(IOConsumer consumer) {
        return (prefix, executor, summaries) -> {
            try {
                SequencedMap<String, Path> folders = new LinkedHashMap<>();
                SequencedMap<String, StepSummary> inherited = new LinkedHashMap<>();
                for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                    folders.put(entry.getKey(), entry.getValue().folder());
                    inherited.put("../" + entry.getKey(), entry.getValue());
                }
                BuildExecutor buildExecutor = new BuildExecutor(root.resolve(prefix), hash, inherited);
                consumer.accept(buildExecutor, folders);
                return buildExecutor.doExecute(executor).thenApplyAsync(results -> {
                    SequencedMap<String, StepSummary> prefixed = new LinkedHashMap<>();
                    results.forEach((identity, values) -> prefixed.put(prefix + "/" + identity, values));
                    return prefixed;
                }, executor);
            } catch (Throwable t) {
                return CompletableFuture.failedStage(t);
            }
        };
    }

    private void add(String identity, Bound bound, Set<String> dependencies) {
        Set<String> preliminaries = new HashSet<>();
        dependencies.forEach(dependency -> {
            if (dependency.startsWith("../")) {
                if (!inherited.containsKey(dependency)) {
                    throw new IllegalArgumentException("Did not inherit: " + dependency);
                }
            } else if (registrations.containsKey(dependency)) {
                preliminaries.add(dependency);
            } else {
                throw new IllegalArgumentException("Did not find dependency: " + dependency);
            }
        });
        if (registrations.putIfAbsent(
                validated(identity),
                new Registration(bound, preliminaries, dependencies)) != null) {
            throw new IllegalArgumentException("Step already registered: " + identity);
        }
    }

    private void replace(String identity, Bound bound) {
        Registration registration = registrations.get(identity);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown step: " + identity);
        }
        registrations.replace(identity, new Registration(bound,
                registration.preliminaries(),
                registration.dependencies()));
    }

    private void prepend(String identity, String prepended, Bound bound) {
        Registration registration = registrations.get(identity);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown step: " + identity);
        }
        if (registrations.putIfAbsent(validated(prepended), new Registration(bound,
                registration.preliminaries(),
                registration.dependencies())) != null) {
            throw new IllegalArgumentException("Step already registered: " + prepended);
        }
        registrations.replace(identity, new Registration(registration.bound(), Set.of(prepended), Set.of(prepended)));
    }

    private void append(String identity, String appended, Bound bound) {
        Registration registration = registrations.get(identity);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown step: " + identity);
        }
        if (registrations.putIfAbsent(validated(appended), registration) != null) {
            throw new IllegalArgumentException("Step already registered: " + appended);
        }
        registrations.replace(identity, new Registration(bound, Set.of(appended), Set.of(appended)));
    }

    public CompletionStage<SequencedMap<String, Path>> execute(Executor executor) {
        return doExecute(executor).thenApplyAsync(summaries -> {
            SequencedMap<String, Path> translated = new LinkedHashMap<>();
            for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                translated.put(entry.getKey(), entry.getValue().folder());
            }
            return translated;
        }, executor);
    }

    private CompletionStage<Map<String, StepSummary>> doExecute(Executor executor) {
        CompletionStage<Map<String, StepSummary>> initial = CompletableFuture.completedStage(Map.of());
        SequencedMap<String, Registration> pending = new LinkedHashMap<>(registrations);
        SequencedMap<String, CompletionStage<Map<String, StepSummary>>> dispatched = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            Iterator<Map.Entry<String, Registration>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Registration> entry = it.next();
                if (dispatched.keySet().containsAll(entry.getValue().preliminaries())) {
                    CompletionStage<Map<String, StepSummary>> completionStage = initial;
                    for (String dependency : entry.getValue().preliminaries()) {
                        completionStage = completionStage.thenCombineAsync(
                                dispatched.get(dependency),
                                (left, right) -> Stream
                                        .concat(left.entrySet().stream(), right.entrySet().stream())
                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                                executor);
                    }
                    dispatched.put(entry.getKey(), completionStage.thenComposeAsync(summaries -> {
                        SequencedMap<String, StepSummary> merged = new LinkedHashMap<>(inherited);
                        merged.putAll(summaries);
                        return entry.getValue().bound().apply(entry.getKey(), executor, merged);
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

    @FunctionalInterface
    private interface Bound {

        CompletionStage<Map<String, StepSummary>> apply(String identity,
                                                        Executor executor,
                                                        Map<String, StepSummary> summaries);
    }

    private record Registration(Bound bound, Set<String> preliminaries, Set<String> dependencies) {
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

        void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException;
    }
}
