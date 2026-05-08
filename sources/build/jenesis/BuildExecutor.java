package build.jenesis;

import module java.base;

public class BuildExecutor {

    public static final String BUILD_MARKER = ".jenesis.build";

    private static final Pattern
            VALIDATE_ORIGINAL = Pattern.compile("[a-zA-Z0-9-]+"),
            VALIDATE_RESOLVED = Pattern.compile("[a-zA-Z0-9/-]+");

    private final Path target;
    private final HashFunction hash;
    private final BuildStepHashFunction stepHash;
    private final BuildExecutorCallback callback;
    private final String location;

    private final Map<String, StepSummary> inherited;
    private final SequencedMap<String, Registration> registrations = new LinkedHashMap<>();

    private BuildExecutor(Path target,
                          HashFunction hash,
                          BuildStepHashFunction stepHash,
                          BuildExecutorCallback callback,
                          String location,
                          Map<String, StepSummary> inherited) throws IOException {
        this.target = Files.isDirectory(target) ? target : Files.createDirectory(target);
        this.hash = hash;
        this.stepHash = stepHash;
        this.callback = callback;
        this.location = location;
        this.inherited = inherited;
    }

    public static BuildExecutor of(Path target) throws IOException {
        return of(target,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofDigest("MD5"),
                BuildExecutorCallback.printing(System.out, Boolean.getBoolean("jenesis.debug"), target));
    }

    public static BuildExecutor of(Path target, HashFunction hash, BuildExecutorCallback callback) throws IOException {
        return of(target, hash, BuildStepHashFunction.ofDigest("MD5"), callback);
    }

    public static BuildExecutor of(Path target,
                                   HashFunction hash,
                                   BuildStepHashFunction stepHash,
                                   BuildExecutorCallback callback) throws IOException {
        BuildExecutor executor = new BuildExecutor(target, hash, stepHash, callback, "", Map.of());
        if (!Files.exists(target.resolve(BUILD_MARKER))) {
            Files.createFile(target.resolve(BUILD_MARKER));
        }
        return executor;
    }

    public void addSource(String identity, Path path) {
        add(identity, bindSource(path), Map.of());
    }

    public void addSource(String identity, BuildStep step, Path... paths) {
        add(identity, bindStep(step).summaries(hash, sequencedSetOf(paths)), Map.of());
    }

    public void addSource(String identity, BuildStep step, SequencedSet<Path> paths) {
        add(identity, bindStep(step).summaries(hash, paths), Map.of());
    }

    public void replaceSource(String identity, Path path) {
        replace(identity, bindSource(path));
    }

    public void replaceSource(String identity, BuildStep step, Path... paths) {
        replace(identity, bindStep(step).summaries(hash, sequencedSetOf(paths)));
    }

    public void replaceSource(String identity, BuildStep step, Stream<Path> paths) {
        replace(identity, bindStep(step).summaries(hash, paths.collect(Collectors.toCollection(LinkedHashSet::new))));
    }

    public void replaceSource(String identity, BuildStep step, SequencedSet<Path> paths) {
        replace(identity, bindStep(step).summaries(hash, paths));
    }

    private Bound bindSource(Path path) {
        return (identity, executor, _, selectors) -> {
            if (!selectors.isEmpty()) {
                selectors.stream().filter(selector -> !selector.lenient()).findFirst().ifPresent(selector -> {
                    throw new IllegalArgumentException("Unknown selector: " + selector.path());
                });
                return CompletableFuture.completedStage(Map.of(identity, Map.of()));
            }
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
        add(identity, bindStep(step), sequencedMapOf(dependencies));
    }

    public void addStep(String identity, BuildStep step, Stream<String> dependencies) {
        add(identity, bindStep(step), sequencedMapOf(dependencies));
    }

    public void addStep(String identity, BuildStep step, SequencedSet<String> dependencies) {
        add(identity, bindStep(step), sequencedMapOf(dependencies));
    }

    public void addStep(String identity, BuildStep step, SequencedMap<String, String> dependencies) {
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
        return (identity, executor, summaries, selectors) -> {
            try {
                if (!selectors.isEmpty()) {
                    selectors.stream().filter(selector -> !selector.lenient()).findFirst().ifPresent(selector -> {
                        throw new IllegalArgumentException("Unknown selector: " + selector.path());
                    });
                    return CompletableFuture.completedStage(Map.of(identity, Map.of()));
                }
                Path previous = target.resolve(URLEncoder.encode(identity, StandardCharsets.UTF_8)),
                        checksum = previous.resolve("checksum"),
                        output = previous.resolve("output");
                boolean exists = Files.exists(previous);
                Map<Path, byte[]> current = exists ? HashFunction.read(checksum.resolve("checksums")) : Map.of();
                byte[] currentStepHash = stepHash.hash(step);
                Path stepFile = checksum.resolve("step");
                boolean consistent = exists
                        && Files.exists(stepFile)
                        && Arrays.equals(currentStepHash, HexFormat.of().parseHex(Files.readString(stepFile).trim()))
                        && HashFunction.areConsistent(output, current, hash);
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
                            Files.writeString(checksum.resolve("step"), HexFormat.of().formatHex(currentStepHash));
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
        add(identity, bindModule(module, Optional::of), sequencedMapOf(dependencies));
    }

    public void addModule(String identity,
                          BuildExecutorModule module,
                          Function<String, Optional<String>> resolver,
                          String... dependencies) {
        add(identity, bindModule(module, resolver), sequencedMapOf(dependencies));
    }

    public void addModule(String identity, BuildExecutorModule module, Stream<String> dependencies) {
        add(identity, bindModule(module, Optional::of), sequencedMapOf(dependencies));
    }

    public void addModule(String identity,
                          BuildExecutorModule module,
                          Function<String, Optional<String>> resolver,
                          Stream<String> dependencies) {
        add(identity, bindModule(module, resolver), sequencedMapOf(dependencies));
    }

    public void addModule(String identity, BuildExecutorModule module, SequencedSet<String> dependencies) {
        add(identity, bindModule(module, Optional::of), sequencedMapOf(dependencies));
    }

    public void addModule(String identity,
                          BuildExecutorModule module,
                          Function<String, Optional<String>> resolver,
                          SequencedSet<String> dependencies) {
        add(identity, bindModule(module, resolver), sequencedMapOf(dependencies));
    }

    public void addModule(String identity, BuildExecutorModule module, SequencedMap<String, String> dependencies) {
        add(identity, bindModule(module, Optional::of), dependencies);
    }

    public void addModule(String identity,
                          BuildExecutorModule module,
                          Function<String, Optional<String>> resolver,
                          SequencedMap<String, String> dependencies) {
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
        return (prefix, executor, summaries, selectors) -> {
            Consumer<Throwable> resolution = callback.module(location + prefix);
            try {
                SequencedMap<String, Path> folders = new LinkedHashMap<>();
                SequencedMap<String, StepSummary> inherited = new LinkedHashMap<>();
                for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                    String identity = BuildExecutorModule.PREVIOUS + entry.getKey();
                    folders.put(identity, entry.getValue().folder());
                    inherited.put(identity, entry.getValue());
                }
                BuildExecutor buildExecutor = new BuildExecutor(target.resolve(prefix),
                        hash,
                        stepHash,
                        callback,
                        location + prefix + "/",
                        inherited);
                module.accept(buildExecutor, folders);
                resolution.accept(null);
                return buildExecutor.doExecute(executor, selectors).thenComposeAsync(results -> {
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
                resolution.accept(t);
                return CompletableFuture.failedStage(new BuildExecutorException(location + prefix, t));
            }
        };
    }

    private void add(String identity, Bound bound, Map<String, String> dependencies) {
        SequencedSet<String> preliminaries = new LinkedHashSet<>();
        Set<String> synonyms = new HashSet<>();
        dependencies.forEach((dependency, synonym) -> {
            if (!synonyms.add(synonym)) {
                throw new IllegalArgumentException("Duplicated synonym: " + synonym);
            }
            int index, limit = dependency.length();
            while ((index = dependency.lastIndexOf('/', limit - 1)) != -1) {
                if (dependencies.containsKey(dependency.substring(0, index))) {
                    throw new IllegalArgumentException("Redundant root dependency: " + dependency.substring(0, index));
                }
                limit = index;
            }
            if (dependency.startsWith(BuildExecutorModule.PREVIOUS)) {
                if (!inherited.containsKey(dependency)) {
                    throw new IllegalArgumentException("Did not inherit: " + dependency);
                }
            } else if (registrations.containsKey(dependency.substring(0, limit))) {
                preliminaries.add(dependency.substring(0, limit));
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
        registrations.replace(identity, new Registration(registration.bound(), Set.of(prepended), Map.of(prepended, prepended)));
    }

    private void append(String identity, String appended, Bound bound) {
        Registration registration = registrations.get(identity);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown step: " + identity);
        }
        if (registrations.putIfAbsent(validated(appended, VALIDATE_ORIGINAL), registration) != null) {
            throw new IllegalArgumentException("Step already registered: " + appended);
        }
        registrations.replace(identity, new Registration(bound, Set.of(appended), Map.of(appended, appended)));
    }

    public SequencedMap<String, Path> execute(String... selectors) {
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            return execute(executorService, selectors).toCompletableFuture().join();
        }
    }

    public CompletionStage<SequencedMap<String, Path>> execute(Executor executor, String... selectors) {
        BiConsumer<Boolean, Throwable> completion = callback.step(null, registrations.sequencedKeySet());
        Set<Selector> initial = Arrays.stream(selectors)
                .map(s -> new Selector(s, false))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return doExecute(executor, initial).thenApplyAsync(summaries -> {
            SequencedMap<String, Path> translated = new LinkedHashMap<>();
            for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                translated.put(entry.getKey(), entry.getValue().folder());
            }
            return translated;
        }, executor).whenComplete((_, throwable) -> completion.accept(null, throwable));
    }

    private CompletionStage<Map<String, StepSummary>> doExecute(Executor executor, Set<Selector> selectors) {
        SequencedSet<String> scheduled = new LinkedHashSet<>();
        Set<String> pinned = new HashSet<>(), direct = new HashSet<>();
        Map<String, Set<Selector>> forwarded = new LinkedHashMap<>();
        if (selectors.isEmpty()) {
            scheduled.addAll(registrations.keySet());
        } else {
            Queue<Selector> queue = new ArrayDeque<>(selectors);
            while (!queue.isEmpty()) {
                Selector selector = queue.poll(), tail = selector.tail();
                String first = selector.first();
                if (first.equals(":") || first.equals("::")) {
                    scheduled.addAll(registrations.keySet());
                    if (tail == null) {
                        direct.addAll(registrations.keySet());
                        pinned.addAll(registrations.keySet());
                    } else {
                        boolean anyDepth = first.equals("::");
                        if (anyDepth) {
                            queue.add(tail.asLenient());
                        }
                        Selector descend = (anyDepth ? selector : tail).asLenient();
                        registrations.keySet().forEach(identity ->
                                forwarded.computeIfAbsent(identity, _ -> new LinkedHashSet<>())
                                        .add(descend));
                    }
                } else if (!registrations.containsKey(first)) {
                    if (!selector.lenient()) {
                        throw new IllegalArgumentException("Unknown selector: " + selector.path());
                    }
                } else {
                    scheduled.add(first);
                    pinned.add(first);
                    if (tail == null) {
                        direct.add(first);
                    } else {
                        forwarded.computeIfAbsent(first, _ -> new LinkedHashSet<>()).add(tail);
                    }
                }
            }
            ArrayDeque<String> prelimQueue = new ArrayDeque<>(pinned);
            while (!prelimQueue.isEmpty()) {
                for (String preliminary : registrations.get(prelimQueue.poll()).preliminaries()) {
                    scheduled.add(preliminary);
                    direct.add(preliminary);
                    if (pinned.add(preliminary)) {
                        prelimQueue.add(preliminary);
                    }
                }
            }
            for (String identity : direct) {
                forwarded.remove(identity);
            }
        }
        CompletionStage<Map<String, Map<String, StepSummary>>> initial = CompletableFuture.completedStage(Map.of());
        SequencedMap<String, Registration> pending = new LinkedHashMap<>();
        for (Map.Entry<String, Registration> entry : registrations.entrySet()) {
            if (scheduled.contains(entry.getKey())) {
                pending.put(entry.getKey(), entry.getValue());
            }
        }
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
                            entry.getValue().dependencies().forEach((dependency, synonym) -> {
                                if (dependency.startsWith(BuildExecutorModule.PREVIOUS)) {
                                    propagated.put(synonym, inherited.get(dependency));
                                } else {
                                    int index = dependency.indexOf('/');
                                    if (index != -1) {
                                        StepSummary summary = summaries.getOrDefault(
                                                dependency.substring(0, index),
                                                Map.of()).get(dependency);
                                        if (summary == null) {
                                            throw new IllegalArgumentException("Did not find dependency: " + dependency);
                                        }
                                        propagated.put(synonym, summary);
                                    } else {
                                        summaries.getOrDefault(dependency, Map.of()).forEach((key, value) -> propagated.put(
                                                synonym + key.substring(dependency.length()),
                                                value));
                                    }
                                }
                            });
                            return entry.getValue().bound().apply(
                                    entry.getKey(),
                                    executor,
                                    propagated,
                                    forwarded.getOrDefault(entry.getKey(), Set.of()));
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
        for (String identity : scheduled) {
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

    @SafeVarargs
    private static <T> SequencedMap<T, T> sequencedMapOf(T... values) {
        SequencedMap<T, T> map = new LinkedHashMap<>();
        for (T value : values) {
            map.put(value, value);
        }
        return map;
    }

    private static <T> SequencedMap<T, T> sequencedMapOf(Stream<T> values) {
        SequencedMap<T, T> map = new LinkedHashMap<>();
        values.forEach(value -> map.put(value, value));
        return map;
    }

    private static <T> SequencedMap<T, T> sequencedMapOf(Set<T> values) {
        SequencedMap<T, T> map = new LinkedHashMap<>();
        for (T value : values) {
            map.put(value, value);
        }
        return map;
    }

    @FunctionalInterface
    private interface Bound {

        CompletionStage<Map<String, Map<String, StepSummary>>> apply(String identity,
                                                                     Executor executor,
                                                                     Map<String, StepSummary> summaries,
                                                                     Set<Selector> selectors)
                throws IOException;

        default Bound summaries(HashFunction hash, Set<Path> paths) {
            return (identity, executor, summaries, selectors) -> {
                SequencedMap<String, StepSummary> extended = new LinkedHashMap<>(summaries);
                for (Path path : paths) {
                    extended.put(
                            ":" + URLEncoder.encode(path.toString(), StandardCharsets.UTF_8),
                            new StepSummary(path, HashFunction.read(path, hash)));
                }
                return apply(identity, executor, extended, selectors);
            };
        }
    }

    private record Selector(String path, boolean lenient) {

        String first() {
            int slash = path.indexOf('/');
            return slash == -1 ? path : path.substring(0, slash);
        }

        Selector tail() {
            int slash = path.indexOf('/');
            return slash == -1 ? null : new Selector(path.substring(slash + 1), lenient);
        }

        Selector asLenient() {
            return lenient ? this : new Selector(path, true);
        }
    }

    private record Registration(Bound bound, Set<String> preliminaries, Map<String, String> dependencies) {
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
