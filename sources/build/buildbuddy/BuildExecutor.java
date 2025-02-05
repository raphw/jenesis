package build.buildbuddy;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

public class BuildExecutor {

    public static final String BUILD_MARKER = ".buildbuddy.build";

    private static final Pattern
            VALIDATE_ORIGINAL = Pattern.compile("[a-zA-Z0-9-]+"),
            VALIDATE_RESOLVED = Pattern.compile("[a-zA-Z0-9/-]+");

    private final Path root;
    private final HashFunction hash;
    private final BuildExecutorCallback callback;
    private final String location;

    private final Map<String, StepSummary> inherited;
    private final SequencedMap<String, Registration> registrations = new LinkedHashMap<>();

    private BuildExecutor(Path root,
                          HashFunction hash,
                          BuildExecutorCallback callback,
                          String location,
                          Map<String, StepSummary> inherited) throws IOException {
        this.root = Files.isDirectory(root) ? root : Files.createDirectory(root);
        this.hash = hash;
        this.callback = callback;
        this.location = location;
        this.inherited = inherited;
    }

    public static BuildExecutor of(Path root) throws IOException {
        return of(root, new HashDigestFunction("MD5"), BuildExecutorCallback.printing(System.out));
    }

    public static BuildExecutor of(Path root, HashFunction hash, BuildExecutorCallback callback) throws IOException {
        BuildExecutor executor = new BuildExecutor(root, hash, callback, "", Map.of());
        if (!Files.exists(root.resolve(BUILD_MARKER))) {
            Files.createFile(root.resolve(BUILD_MARKER));
        }
        return executor;
    }

    public void addSource(String identity, Path path) {
        add(identity, bindSource(path), Set.of());
    }

    public void addSource(String identity, BuildStep step, Path... paths) {
        add(identity, bindStep(step).summaries(hash, sequencedSetOf(paths)), Set.of());
    }

    public void addSource(String identity, BuildStep step, SequencedSet<Path> paths) {
        add(identity, bindStep(step).summaries(hash, paths), Set.of());
    }

    public void replaceSource(String identity, Path path) {
        replace(identity, bindSource(path));
    }

    public void replaceSource(String identity, BuildStep step, Path... paths) {
        replace(identity, bindStep(step).summaries(hash, sequencedSetOf(paths)));
    }

    public void replaceSource(String identity, BuildStep step, SequencedSet<Path> paths) {
        replace(identity, bindStep(step).summaries(hash, paths));
    }

    private Bound bindSource(Path path) {
        return (identity, executor, _) -> {
            CompletableFuture<Map<String, Map<String, StepSummary>>> future = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    future.complete(Map.of(identity, Map.of(
                            identity,
                            new StepSummary(path, HashFunction.read(path, hash)))));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        };
    }

    public void addStep(String identity, BuildStep step, String... dependencies) {
        add(identity, bindStep(step), sequencedSetOf(dependencies));
    }

    public void addStep(String identity, BuildStep step, SequencedSet<String> dependencies) {
        add(identity, bindStep(step), dependencies);
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
                    Path checksums = checksum.resolve("checksums." + URLEncoder.encode(
                            entry.getKey(),
                            StandardCharsets.UTF_8));
                    arguments.put(entry.getKey(), new BuildStepArgument(
                            entry.getValue().folder(),
                            consistent && Files.exists(checksums)
                                    ? ChecksumStatus.diff(HashFunction.read(checksums), entry.getValue().checksums())
                                    : ChecksumStatus.added(entry.getValue().checksums().keySet())));
                }
                BiConsumer<Boolean, Throwable> completion = callback.step(
                        location + identity,
                        new LinkedHashSet<>(summaries.keySet()));
                if (!consistent || step.shouldRun(arguments)) {
                    Path next = Files.createTempDirectory(URLEncoder.encode(identity, StandardCharsets.UTF_8));
                    return step.apply(executor,
                            new BuildStepContext(
                                    consistent ? output : null,
                                    Files.createDirectory(next.resolve("output")),
                                    Files.createDirectory(next.resolve("supplement"))),
                            arguments).thenComposeAsync(result -> {
                        try {
                            if (result.next()) {
                                Files.move(next, exists
                                        ? Files.walkFileTree(previous, new RecursiveFolderDeletion(null))
                                        : previous);
                                Files.createDirectory(checksum);
                            } else if (consistent) {
                                Files.delete(Files.walkFileTree(next, new RecursiveFolderDeletion(next)));
                                Files.walkFileTree(checksum, new RecursiveFolderDeletion(checksum));
                            } else {
                                throw new IllegalStateException("Cannot reuse initial run for " + location + identity);
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
                            completion.accept(result.next(), null);
                            return CompletableFuture.completedStage(Map.of(
                                    identity,
                                    Map.of(identity, new StepSummary(output, checksums))));
                        } catch (Throwable t) {
                            return CompletableFuture.failedStage(new BuildExecutorException(location + identity, t));
                        }
                    }, executor).exceptionallyComposeAsync(t -> {
                        BuildExecutorException wrapped = switch (t) {
                            case BuildExecutorException e -> e;
                            case CompletionException e -> new BuildExecutorException(location + identity, e.getCause());
                            default -> new BuildExecutorException(location + identity, t);
                        };
                        try {
                            Files.delete(Files.walkFileTree(next, new RecursiveFolderDeletion(next)));
                        } catch (IOException e) {
                            wrapped.addSuppressed(e);
                        }
                        completion.accept(null, t);
                        return CompletableFuture.failedStage(wrapped);
                    }, executor);
                } else {
                    completion.accept(false, null);
                    return CompletableFuture.completedStage(Map.of(identity, Map.of(
                            identity,
                            new StepSummary(output, current))));
                }
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(new BuildExecutorException(location + identity, t));
            }
        };
    }

    public void addModule(String identity, BuildExecutorModule module, String... dependencies) {
        add(identity, bindModule(module, Optional::of), sequencedSetOf(dependencies));
    }

    public void addModule(String identity,
                          BuildExecutorModule module,
                          Function<String, Optional<String>> resolver,
                          String... dependencies) {
        add(identity, bindModule(module, resolver), sequencedSetOf(dependencies));
    }

    public void addModule(String identity, BuildExecutorModule module, SequencedSet<String> dependencies) {
        add(identity, bindModule(module, Optional::of), dependencies);
    }

    public void addModule(String identity,
                          BuildExecutorModule module,
                          Function<String, Optional<String>> resolver,
                          SequencedSet<String> dependencies) {
        add(identity, bindModule(module, resolver), dependencies);
    }

    public void replaceModule(String identity, BuildExecutorModule module) {
        replace(identity, bindModule(module, Optional::of));
    }

    public void replaceModule(String identity,
                              BuildExecutorModule module,
                              Function<String, Optional<String>> resolver) {
        replace(identity, bindModule(module, resolver));
    }

    public void prependModule(String identity, String prepended, BuildExecutorModule module) {
        prepend(identity, prepended, bindModule(module, Optional::of));
    }

    public void prependModule(String identity,
                              String prepended,
                              BuildExecutorModule module,
                              Function<String, Optional<String>> resolver) {
        prepend(identity, prepended, bindModule(module, resolver));
    }

    public void appendModule(String identity, String appended, BuildExecutorModule module) {
        append(identity, appended, bindModule(module, Optional::of));
    }

    public void appendModule(String identity,
                             String appended,
                             BuildExecutorModule module,
                             Function<String, Optional<String>> resolver) {
        append(identity, appended, bindModule(module, resolver));
    }

    private Bound bindModule(BuildExecutorModule module, Function<String, Optional<String>> resolver) {
        return (prefix, executor, summaries) -> {
            try {
                SequencedMap<String, Path> folders = new LinkedHashMap<>();
                SequencedMap<String, StepSummary> inherited = new LinkedHashMap<>();
                for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                    String identity = BuildExecutorModule.PREVIOUS + entry.getKey();
                    folders.put(identity, entry.getValue().folder());
                    inherited.put(identity, entry.getValue());
                }
                BuildExecutor buildExecutor = new BuildExecutor(root.resolve(prefix),
                        hash,
                        callback,
                        location + prefix + "/",
                        inherited);
                module.accept(buildExecutor, folders);
                return buildExecutor.doExecute(executor).thenComposeAsync(results -> {
                    try {
                        Map<String, StepSummary> prefixed = new LinkedHashMap<>();
                        results.forEach((identity, values) -> {
                            String resolved = module.resolve(identity).flatMap(resolver).orElse(null);
                            if (resolved != null && prefixed.putIfAbsent(
                                    resolved.isEmpty() ? prefix : prefix + "/" + validated(resolved, VALIDATE_RESOLVED),
                                    values) != null) {
                                throw new IllegalArgumentException("Duplicate resolution " + resolved);
                            }
                        });
                        return CompletableFuture.completedStage(Map.of(prefix, prefixed));
                    } catch (Throwable t) {
                        return CompletableFuture.failedStage(new BuildExecutorException(location + prefix, t));
                    }
                }, executor);
            } catch (Throwable t) {
                return CompletableFuture.failedStage(new BuildExecutorException(location + prefix, t));
            }
        };
    }

    private void add(String identity, Bound bound, Set<String> dependencies) {
        SequencedSet<String> preliminaries = new LinkedHashSet<>();
        dependencies.forEach(dependency -> {
            if (dependency.startsWith(BuildExecutorModule.PREVIOUS)) {
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
                validated(identity, VALIDATE_ORIGINAL),
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
        if (registrations.putIfAbsent(validated(prepended, VALIDATE_ORIGINAL), new Registration(bound,
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
        if (registrations.putIfAbsent(validated(appended, VALIDATE_ORIGINAL), registration) != null) {
            throw new IllegalArgumentException("Step already registered: " + appended);
        }
        registrations.replace(identity, new Registration(bound, Set.of(appended), Set.of(appended)));
    }

    public SequencedMap<String, Path> execute() {
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            return execute(executorService).toCompletableFuture().join();
        }
    }

    public CompletionStage<SequencedMap<String, Path>> execute(Executor executor) {
        BiConsumer<Boolean, Throwable> completion = callback.step(null, registrations.sequencedKeySet());
        return doExecute(executor).thenApplyAsync(summaries -> {
            SequencedMap<String, Path> translated = new LinkedHashMap<>();
            for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                translated.put(entry.getKey(), entry.getValue().folder());
            }
            return translated;
        }, executor).whenComplete((_, throwable) -> completion.accept(null, throwable));
    }

    private CompletionStage<Map<String, StepSummary>> doExecute(Executor executor) {
        CompletionStage<Map<String, Map<String, StepSummary>>> initial = CompletableFuture.completedStage(Map.of());
        SequencedMap<String, Registration> pending = new LinkedHashMap<>(registrations);
        SequencedMap<String, CompletionStage<Map<String, Map<String, StepSummary>>>> dispatched = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            Iterator<Map.Entry<String, Registration>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Registration> entry = it.next();
                if (dispatched.keySet().containsAll(entry.getValue().preliminaries())) {
                    CompletionStage<Map<String, Map<String, StepSummary>>> completionStage = initial;
                    for (String dependency : entry.getValue().preliminaries()) {
                        completionStage = completionStage.thenCombineAsync(
                                dispatched.get(dependency),
                                (left, right) -> {
                                    SequencedMap<String, Map<String, StepSummary>> merged = new LinkedHashMap<>(left);
                                    merged.putAll(right);
                                    return merged;
                                },
                                executor);
                    }
                    dispatched.put(entry.getKey(), completionStage.thenComposeAsync(summaries -> {
                        try {
                            SequencedMap<String, StepSummary> propagated = new LinkedHashMap<>();
                            entry.getValue().dependencies().forEach(dependency -> {
                                if (dependency.startsWith(BuildExecutorModule.PREVIOUS)) {
                                    propagated.put(dependency, inherited.get(dependency));
                                } else {
                                    propagated.putAll(summaries.get(dependency));
                                }
                            });
                            return entry.getValue().bound().apply(entry.getKey(), executor, propagated);
                        } catch (Throwable t) {
                            return CompletableFuture.failedStage(new BuildExecutorException(
                                    location + entry.getKey(),
                                    t));
                        }
                    }, executor));
                    it.remove();
                }
            }
        }
        CompletionStage<Map<String, StepSummary>> result = CompletableFuture.completedStage(Map.of());
        for (String identity : registrations.keySet()) {
            result = result.thenCombineAsync(dispatched.get(identity), (left, right) -> {
                SequencedMap<String, StepSummary> merged = new LinkedHashMap<>(left);
                right.values().forEach(merged::putAll);
                return merged;
            }, executor);
        }
        return result;
    }

    private static String validated(String identity, Pattern pattern) {
        if (pattern.matcher(identity).matches()) {
            return identity;
        }
        throw new IllegalArgumentException(identity + " does not match pattern: " + pattern.pattern());
    }

    @SafeVarargs
    private static <T> SequencedSet<T> sequencedSetOf(T... values) {
        SequencedSet<T> set = new LinkedHashSet<>();
        for (T value : values) {
            if (!set.add(value)) {
                throw new IllegalArgumentException("Duplicated argument: " + value);
            }
        }
        return set;
    }

    @FunctionalInterface
    private interface Bound {

        CompletionStage<Map<String, Map<String, StepSummary>>> apply(String identity,
                                                                     Executor executor,
                                                                     Map<String, StepSummary> summaries)
                throws IOException;

        default Bound summaries(HashFunction hash, Set<Path> paths) {
            return (identity, executor, summaries) -> {
                SequencedMap<String, StepSummary> extended = new LinkedHashMap<>(summaries);
                for (Path path : paths) {
                    extended.put(
                            ":" + URLEncoder.encode(path.toString(), StandardCharsets.UTF_8),
                            new StepSummary(path, HashFunction.read(path, hash)));
                }
                return apply(identity, executor, extended);
            };
        }
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

}
