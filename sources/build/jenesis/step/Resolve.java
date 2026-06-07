package build.jenesis.step;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.DependencyScope;
import build.jenesis.Pinning;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.ResolutionListener;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

import static java.util.Objects.requireNonNull;

public class Resolve implements BuildStep {

    private final transient Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final transient Supplier<ResolutionListener> listener;

    public Resolve(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, null);
    }

    private Resolve(Map<String, Repository> repositories,
                    Map<String, Resolver> resolvers,
                    Pinning pinning,
                    Supplier<ResolutionListener> listener) {
        this.repositories = repositories;
        this.resolvers = new LinkedHashMap<>(resolvers);
        this.pinning = pinning;
        this.listener = listener;
    }

    public Resolve pinning(Pinning pinning) {
        return new Resolve(repositories, resolvers, pinning, listener);
    }

    public Resolve listening(Supplier<ResolutionListener> listener) {
        return new Resolve(repositories, resolvers, pinning, listener);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        if (listener != null) {
            return true;
        }
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(REQUIRES),
                Path.of(VERSIONS),
                Path.of(EXCLUSIONS)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        boolean pinned = pinning != Pinning.IGNORE;
        SequencedMap<String, SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>>> requires = new LinkedHashMap<>();
        SequencedMap<String, SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>>> versions = new LinkedHashMap<>();
        SequencedMap<String, SequencedMap<String, SequencedMap<String, SequencedMap<String, SequencedSet<String>>>>> exclusions = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path requiresFile = argument.folder().resolve(REQUIRES);
            if (Files.exists(requiresFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(requiresFile);
                for (String key : properties.stringPropertyNames()) {
                    String[] parts = split(key);
                    if (parts == null) {
                        continue;
                    }
                    requires.computeIfAbsent(parts[0], _ -> new LinkedHashMap<>())
                            .computeIfAbsent(parts[1], _ -> new LinkedHashMap<>())
                            .computeIfAbsent(parts[2], _ -> new LinkedHashMap<>())
                            .merge(parts[3], properties.getProperty(key), (left, right) -> left.isEmpty() ? right : left);
                }
            }
            Path versionsFile = argument.folder().resolve(VERSIONS);
            if (Files.exists(versionsFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(versionsFile);
                for (String key : properties.stringPropertyNames()) {
                    String[] parts = split(key);
                    if (parts == null) {
                        throw new IllegalArgumentException("Malformed version pin '"
                                + key
                                + "' in "
                                + versionsFile
                                + ": expected <group>/<scope>/<repository>/<coordinate>");
                    }
                    versions.computeIfAbsent(parts[0], _ -> new LinkedHashMap<>())
                            .computeIfAbsent(parts[1], _ -> new LinkedHashMap<>())
                            .computeIfAbsent(parts[2], _ -> new LinkedHashMap<>())
                            .putIfAbsent(parts[3], properties.getProperty(key));
                }
            }
            Path exclusionsFile = argument.folder().resolve(EXCLUSIONS);
            if (Files.exists(exclusionsFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(exclusionsFile);
                for (String key : properties.stringPropertyNames()) {
                    String[] parts = split(key);
                    if (parts == null) {
                        continue;
                    }
                    SequencedSet<String> excludes = new LinkedHashSet<>();
                    String value = properties.getProperty(key);
                    if (!value.isEmpty()) {
                        for (String entry : value.split(",")) {
                            excludes.add(entry);
                        }
                    }
                    exclusions.computeIfAbsent(parts[0], _ -> new LinkedHashMap<>())
                            .computeIfAbsent(parts[1], _ -> new LinkedHashMap<>())
                            .computeIfAbsent(parts[2], _ -> new LinkedHashMap<>())
                            .put(parts[3], excludes);
                }
            }
        }
        Path cache = Files.createDirectories(context.next().resolve("cache"));
        Path previousCache = context.previous() == null ? null : context.previous().resolve("cache");
        Map<String, Repository> wrapped = new LinkedHashMap<>();
        repositories.forEach((name, repository) -> {
            Repository effective = repository;
            if (previousCache != null) {
                effective = effective.prepend((_, coordinate) -> {
                    Path file = previousCache.resolve(BuildExecutorModule.encode(coordinate) + ".jar");
                    return Files.exists(file) ? Optional.of(RepositoryItem.ofFile(file)) : Optional.empty();
                });
            }
            wrapped.put(name, effective.cached(cache));
        });
        SequencedProperties resolved = new SequencedProperties();
        SequencedMap<String, Resolver.Resolved> materialized = new LinkedHashMap<>();
        for (Map.Entry<String, SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>>> groupEntry : requires.entrySet()) {
            String group = groupEntry.getKey();
            SequencedMap<String, SequencedMap<String, String>> compileVersions = new LinkedHashMap<>();
            List<String> order = new ArrayList<>(groupEntry.getValue().sequencedKeySet());
            order.sort((left, right) -> left.equals("compile") == right.equals("compile")
                    ? left.compareTo(right)
                    : (left.equals("compile") ? -1 : 1));
            for (String scope : order) {
                DependencyScope intent = scope.equals("compile") ? DependencyScope.COMPILE : DependencyScope.RUNTIME;
                for (Map.Entry<String, SequencedMap<String, String>> repoEntry : groupEntry.getValue().get(scope).entrySet()) {
                    String repo = repoEntry.getKey();
                    Resolver resolver = requireNonNull(resolvers.get(Resolver.base(repo)), "Unknown resolver: " + Resolver.base(repo));
                    SequencedMap<String, SequencedSet<String>> repoExclusions = exclusions
                            .getOrDefault(group, new LinkedHashMap<>())
                            .getOrDefault(scope, new LinkedHashMap<>())
                            .getOrDefault(repo, new LinkedHashMap<>());
                    SequencedMap<String, SequencedSet<String>> coordinates = new LinkedHashMap<>();
                    for (String coordinate : repoEntry.getValue().sequencedKeySet()) {
                        coordinates.put(coordinate, repoExclusions.getOrDefault(coordinate, Collections.emptyNavigableSet()));
                    }
                    SequencedMap<String, String> bom = new LinkedHashMap<>();
                    if (pinned) {
                        SequencedMap<String, SequencedMap<String, String>> scopeVersions = versions
                                .getOrDefault(group, new LinkedHashMap<>())
                                .getOrDefault(scope, new LinkedHashMap<>());
                        bom.putAll(scopeVersions.getOrDefault(repo, new LinkedHashMap<>()));
                        for (String managed : resolver.managedPrefixes()) {
                            scopeVersions.getOrDefault(managed, new LinkedHashMap<>()).forEach(bom::putIfAbsent);
                        }
                        if (!scope.equals("compile")) {
                            compileVersions.getOrDefault(repo, new LinkedHashMap<>()).forEach(bom::putIfAbsent);
                        }
                    }
                    SequencedMap<String, Resolver.Resolved> result;
                    if (listener == null) {
                        result = resolver.dependencies(executor, repo, wrapped, coordinates, bom, intent);
                    } else {
                        ResolutionListener current = listener.get();
                        result = resolver.dependencies(executor, repo, wrapped, coordinates, bom, intent, current);
                        current.onResolved();
                    }
                    for (Map.Entry<String, Resolver.Resolved> entry : result.entrySet()) {
                        String coordinate = entry.getKey().substring(entry.getKey().indexOf('/') + 1);
                        String declared = repoEntry.getValue().get(coordinate);
                        String value = declared != null && !declared.isEmpty() ? declared : entry.getValue().checksum();
                        String transitiveKey = scope + "/" + entry.getKey();
                        resolved.setProperty(transitiveKey, value);
                        materialized.putIfAbsent(transitiveKey, entry.getValue());
                        if (scope.equals("compile")) {
                            String key = entry.getKey();
                            int first = key.indexOf('/'), last = key.lastIndexOf('/');
                            if (last > first) {
                                compileVersions.computeIfAbsent(repo, _ -> new LinkedHashMap<>())
                                        .put(key.substring(first + 1, last), key.substring(last + 1));
                            }
                        }
                    }
                }
            }
        }
        resolved.store(context.next().resolve(TRANSITIVES));
        Path libs = Files.createDirectory(context.next().resolve(DEPENDENCIES));
        SequencedProperties index = new SequencedProperties();
        SequencedMap<String, Resolver.Resolved> placements = new LinkedHashMap<>();
        SequencedMap<String, String> checksums = new LinkedHashMap<>();
        for (Map.Entry<String, Resolver.Resolved> entry : materialized.entrySet()) {
            String key = entry.getKey();
            int first = key.indexOf('/'), second = key.indexOf('/', first + 1);
            if (first < 0 || second < 0) {
                continue;
            }
            String dependency = key.substring(first + 1), name = dependency.replace('/', '-') + ".jar";
            index.setProperty(key, DEPENDENCIES + name);
            String value = resolved.getProperty(key);
            placements.putIfAbsent(name, entry.getValue());
            checksums.merge(name, value, (left, right) -> {
                if (right.isEmpty()) {
                    return left;
                }
                if (!left.isEmpty() && !left.equals(right)) {
                    throw new IllegalStateException("Conflicting checksums pinned for " + name + ": " + left + " and " + right);
                }
                return left.isEmpty() ? right : left;
            });
        }
        for (Map.Entry<String, Resolver.Resolved> entry : placements.entrySet()) {
            String name = entry.getKey();
            Resolver.Resolved artifact = entry.getValue();
            String value = pinning == Pinning.IGNORE ? "" : checksums.get(name);
            if (value.isEmpty() && pinning == Pinning.STRICT && !artifact.internal()) {
                throw new IllegalStateException("No checksum pinned for " + name + " (strict pinning is enabled)");
            }
            BuildStep.linkOrCopy(libs.resolve(name), artifact.file());
        }
        index.store(context.next().resolve(DEPENDENCY_INDEX));
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String[] split(String key) {
        int first = key.indexOf('/');
        if (first < 1) {
            return null;
        }
        int second = key.indexOf('/', first + 1);
        if (second < 0) {
            return null;
        }
        int third = key.indexOf('/', second + 1);
        if (third < 0) {
            return null;
        }
        return new String[] {
                key.substring(0, first),
                key.substring(first + 1, second),
                key.substring(second + 1, third),
                key.substring(third + 1)};
    }
}
