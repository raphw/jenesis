package build.jenesis;

import module java.base;

class BuildExecutorDefault implements BuildExecutor {

    private static final Pattern
            VALIDATE_ORIGINAL = Pattern.compile("[a-zA-Z0-9._%-]+"),
            VALIDATE_RESOLVED = Pattern.compile("[a-zA-Z0-9./_%-]+");

    private final Path target;
    private final Duration timeout;
    private final HashDigestFunction hash;
    private final BuildStepHashFunction stepHash;
    private final BuildExecutorCallback callback;
    private final BuildExecutorCache cache;
    private final String location;

    private final Map<String, StepSummary> inherited;
    private final SequencedMap<String, Registration> registrations = new LinkedHashMap<>();

    BuildExecutorDefault(Path target,
                         Duration timeout,
                         HashDigestFunction hash,
                         BuildStepHashFunction stepHash,
                         BuildExecutorCallback callback,
                         BuildExecutorCache cache,
                         String location,
                         Map<String, StepSummary> inherited) throws IOException {
        this.target = Files.isDirectory(target) ? target : Files.createDirectory(target);
        this.timeout = timeout;
        this.hash = hash;
        this.stepHash = stepHash;
        this.callback = callback;
        this.cache = cache;
        this.location = location;
        this.inherited = inherited;
    }

    @Override
    public void addSource(String identity, Path path) {
        add(identity, bindSource(path), Map.of());
    }

    @Override
    public void addSource(String identity, BuildStep step, SequencedSet<Path> paths) {
        add(identity, bindStep(step).summaries(hash, paths), Map.of());
    }

    @Override
    public void replaceSource(String identity, Path path) {
        replace(identity, bindSource(path));
    }

    @Override
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
                            new StepSummary(path, HashFunction.read(path, hash, executor)))));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        };
    }

    @Override
    public void addStep(String identity, BuildStep step, SequencedMap<String, String> dependencies) {
        add(identity, bindStep(step), dependencies);
    }

    @Override
    public void replaceStep(String identity, BuildStep step) {
        replace(identity, bindStep(step));
    }

    @Override
    public void prependStep(String identity, String prepended, BuildStep step) {
        prepend(identity, prepended, bindStep(step));
    }

    @Override
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
                Path previous = target.resolve(BuildExecutorModule.encode(identity)),
                        checksum = previous.resolve("checksum"),
                        output = previous.resolve("output");
                boolean exists = Files.exists(previous);
                Map<Path, byte[]> current = exists ? HashFunction.read(checksum.resolve("output.properties")) : Map.of();
                byte[] currentStepHash = stepHash.hash(step);
                Path stepFile = checksum.resolve("step.properties");
                boolean consistent;
                if (exists && Files.exists(stepFile)) {
                    SequencedProperties stepProperties = SequencedProperties.ofFiles(stepFile);
                    String serialization = stepProperties.getProperty("serialization");
                    consistent = serialization != null
                            && Arrays.equals(currentStepHash, HexFormat.of().parseHex(serialization))
                            && HashFunction.areConsistent(output, current, hash, executor);
                } else {
                    consistent = false;
                }
                SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
                SequencedMap<String, Map<Path, byte[]>> inputs = new LinkedHashMap<>();
                for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                    Path checksums = checksum.resolve("argument." + BuildExecutorModule.encode(entry.getKey()) + ".properties");
                    Map<Path, byte[]> argumentChecksums = entry.getValue().checksums();
                    inputs.put(entry.getKey(), argumentChecksums);
                    arguments.put(entry.getKey(), new BuildStepArgument(
                            entry.getValue().folder(),
                            consistent && Files.exists(checksums)
                                    ? Checksum.diff(HashFunction.read(checksums), argumentChecksums, hash)
                                    : Checksum.added(argumentChecksums, hash)));
                }
                BiConsumer<Boolean, Throwable> completion = callback.step(
                        location + identity,
                        new LinkedHashSet<>(summaries.keySet()));
                if (!consistent || step.shouldRun(arguments)) {
                    Path next = Files.createTempDirectory(target, BuildExecutorModule.encode(identity));
                    Path nextOutput = Files.createDirectory(next.resolve("output"));
                    Path nextSupplement = Files.createDirectory(next.resolve("supplement"));
                    long fetchStarted = System.nanoTime();
                    Optional<BuildStepResult> cached = cache.fetch(
                            executor,
                            location + identity,
                            currentStepHash,
                            inputs,
                            nextOutput);
                    boolean fromCache = cached.isPresent();
                    if (fromCache) {
                        callback.loaded(location + identity, System.nanoTime() - fetchStarted);
                    }
                    CompletionStage<BuildStepResult> stepStage;
                    if (fromCache) {
                        stepStage = CompletableFuture.completedStage(cached.get());
                    } else {
                        stepStage = step.apply(executor,
                                new BuildStepContext(consistent ? output : null, nextOutput, nextSupplement),
                                arguments);
                        if (!timeout.isZero()) {
                            stepStage = stepStage.toCompletableFuture().orTimeout(
                                    timeout.toNanos(),
                                    TimeUnit.NANOSECONDS);
                        }
                    }
                    return stepStage.thenComposeAsync(result -> {
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
                                        checksum.resolve("argument." + BuildExecutorModule.encode(entry.getKey()) + ".properties"),
                                        entry.getValue().checksums());
                            }
                            Map<Path, byte[]> checksums = HashFunction.read(output, hash, executor);
                            HashFunction.write(checksum.resolve("output.properties"), checksums);
                            SequencedProperties stepProperties = new SequencedProperties();
                            stepProperties.setProperty("serialization", HexFormat.of().formatHex(currentStepHash));
                            stepProperties.store(checksum.resolve("step.properties"));
                            if (cache.stores() && !fromCache && result.next()) {
                                String stored = location + identity;
                                try {
                                    executor.execute(() -> {
                                        long storeStarted = System.nanoTime();
                                        try {
                                            cache.store(executor, stored, currentStepHash, inputs, output);
                                        } catch (IOException _) {
                                        }
                                        callback.stored(stored, System.nanoTime() - storeStarted);
                                    });
                                } catch (RejectedExecutionException _) {
                                }
                            }
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
                    try {
                        cache.touch(executor, location + identity, currentStepHash, inputs);
                    } catch (IOException _) {
                    }
                    return CompletableFuture.completedStage(Map.of(identity, Map.of(
                            identity,
                            new StepSummary(output, current))));
                }
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(new BuildExecutorException(location + identity, t));
            }
        };
    }

    @Override
    public void addModule(String identity,
                          BuildExecutorModule module,
                          Function<String, Optional<String>> resolver,
                          SequencedMap<String, String> dependencies) {
        add(identity, bindModule(module, resolver), dependencies);
    }

    @Override
    public void replaceModule(String identity,
                              BuildExecutorModule module,
                              Function<String, Optional<String>> resolver) {
        replace(identity, bindModule(module, resolver));
    }

    @Override
    public void prependModule(String identity,
                              String prepended,
                              BuildExecutorModule module,
                              Function<String, Optional<String>> resolver) {
        prepend(identity, prepended, bindModule(module, resolver));
    }

    @Override
    public void appendModule(String identity,
                             String appended,
                             BuildExecutorModule module,
                             Function<String, Optional<String>> resolver) {
        append(identity, appended, bindModule(module, resolver));
    }

    private Bound bindModule(BuildExecutorModule module, Function<String, Optional<String>> resolver) {
        return new Bound() {
            @Override
            public boolean module() {
                return true;
            }

            @Override
            public CompletionStage<Map<String, Map<String, StepSummary>>> apply(String prefix,
                                                                                Executor executor,
                                                                                Map<String, StepSummary> summaries,
                                                                                Set<Selector> selectors) {
                Consumer<Throwable> resolution = callback.module(location + prefix);
                try {
                    SequencedMap<String, Path> folders = new LinkedHashMap<>();
                    SequencedMap<String, StepSummary> inherited = new LinkedHashMap<>();
                    for (Map.Entry<String, StepSummary> entry : summaries.entrySet()) {
                        String identity = BuildExecutorModule.PREVIOUS + entry.getKey();
                        folders.put(identity, entry.getValue().folder());
                        inherited.put(identity, entry.getValue());
                    }
                    BuildExecutorDefault buildExecutor = new BuildExecutorDefault(target.resolve(prefix),
                            timeout,
                            hash,
                            stepHash,
                            callback,
                            cache,
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
        registrations.replace(identity, new Registration(registration.bound(), new LinkedHashSet<>(Set.of(prepended)), Map.of(prepended, prepended)));
    }

    private void append(String identity, String appended, Bound bound) {
        Registration registration = registrations.get(identity);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown step: " + identity);
        }
        if (registrations.putIfAbsent(validated(appended, VALIDATE_ORIGINAL), registration) != null) {
            throw new IllegalArgumentException("Step already registered: " + appended);
        }
        registrations.replace(identity, new Registration(bound, new LinkedHashSet<>(Set.of(appended)), Map.of(appended, appended)));
    }

    @Override
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
            for (String identity : scheduled) {
                if (registrations.get(identity).bound().module() && pinned.add(identity)) {
                    prelimQueue.add(identity);
                }
            }
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

    private interface Bound {

        CompletionStage<Map<String, Map<String, StepSummary>>> apply(String identity,
                                                                     Executor executor,
                                                                     Map<String, StepSummary> summaries,
                                                                     Set<Selector> selectors)
                throws IOException;

        default boolean module() {
            return false;
        }

        default Bound summaries(HashFunction hash, Set<Path> paths) {
            Bound delegate = this;
            return new Bound() {
                @Override
                public CompletionStage<Map<String, Map<String, StepSummary>>> apply(String identity,
                                                                                    Executor executor,
                                                                                    Map<String, StepSummary> summaries,
                                                                                    Set<Selector> selectors)
                        throws IOException {
                    SequencedMap<String, StepSummary> extended = new LinkedHashMap<>(summaries);
                    for (Path path : paths) {
                        extended.put(
                                ":" + BuildExecutorModule.encode(path.toString()),
                                new StepSummary(path, HashFunction.read(path, hash, executor)));
                    }
                    return delegate.apply(identity, executor, extended, selectors);
                }

                @Override
                public boolean module() {
                    return delegate.module();
                }
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

    private record Registration(Bound bound, SequencedSet<String> preliminaries, Map<String, String> dependencies) {
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
