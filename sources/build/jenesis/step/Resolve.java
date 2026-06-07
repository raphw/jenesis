package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.DependencyScope;
import build.jenesis.Repository;
import build.jenesis.ResolutionListener;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

import static java.util.Objects.requireNonNull;

public class Resolve implements BuildStep {

    private final transient Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean pinned;
    private final transient Supplier<ResolutionListener> listener;

    public Resolve(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, true, null);
    }

    private Resolve(Map<String, Repository> repositories,
                    Map<String, Resolver> resolvers,
                    boolean pinned,
                    Supplier<ResolutionListener> listener) {
        this.repositories = repositories;
        this.resolvers = new LinkedHashMap<>(resolvers);
        this.pinned = pinned;
        this.listener = listener;
    }

    public Resolve pinned(boolean pinned) {
        return new Resolve(repositories, resolvers, pinned, listener);
    }

    public Resolve listening(Supplier<ResolutionListener> listener) {
        return new Resolve(repositories, resolvers, pinned, listener);
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
        SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>> requires = new LinkedHashMap<>();
        SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>> versions = new LinkedHashMap<>();
        SequencedMap<String, SequencedMap<String, SequencedMap<String, SequencedSet<String>>>> exclusions = new LinkedHashMap<>();
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
                            .merge(parts[2], properties.getProperty(key), (left, right) -> left.isEmpty() ? right : left);
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
                                + ": expected <scope>/<repository>/<coordinate>");
                    }
                    versions.computeIfAbsent(parts[0], _ -> new LinkedHashMap<>())
                            .computeIfAbsent(parts[1], _ -> new LinkedHashMap<>())
                            .putIfAbsent(parts[2], properties.getProperty(key));
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
                            .put(parts[2], excludes);
                }
            }
        }
        SequencedProperties resolved = new SequencedProperties();
        SequencedMap<String, SequencedMap<String, String>> compileVersions = new LinkedHashMap<>();
        List<String> order = new ArrayList<>(requires.sequencedKeySet());
        order.sort((left, right) -> left.equals("compile") == right.equals("compile")
                ? left.compareTo(right)
                : (left.equals("compile") ? -1 : 1));
        for (String scope : order) {
            DependencyScope intent = scope.equals("compile") ? DependencyScope.COMPILE : DependencyScope.RUNTIME;
            for (Map.Entry<String, SequencedMap<String, String>> repoEntry : requires.get(scope).entrySet()) {
                String repo = repoEntry.getKey();
                Resolver resolver = requireNonNull(resolvers.get(Resolver.base(repo)), "Unknown resolver: " + Resolver.base(repo));
                SequencedMap<String, SequencedSet<String>> repoExclusions = exclusions
                        .getOrDefault(scope, new LinkedHashMap<>())
                        .getOrDefault(repo, new LinkedHashMap<>());
                SequencedMap<String, SequencedSet<String>> coordinates = new LinkedHashMap<>();
                for (String coordinate : repoEntry.getValue().sequencedKeySet()) {
                    coordinates.put(coordinate, repoExclusions.getOrDefault(coordinate, Collections.emptyNavigableSet()));
                }
                SequencedMap<String, String> bom = new LinkedHashMap<>();
                if (pinned) {
                    bom.putAll(versions.getOrDefault(scope, new LinkedHashMap<>()).getOrDefault(repo, new LinkedHashMap<>()));
                    for (String managed : resolver.managedPrefixes()) {
                        versions.getOrDefault(scope, new LinkedHashMap<>())
                                .getOrDefault(managed, new LinkedHashMap<>())
                                .forEach(bom::putIfAbsent);
                    }
                    if (scope.equals("runtime")) {
                        compileVersions.getOrDefault(repo, new LinkedHashMap<>()).forEach(bom::putIfAbsent);
                    }
                }
                SequencedMap<String, String> result;
                if (listener == null) {
                    result = resolver.dependencies(executor, repo, repositories, coordinates, bom, intent);
                } else {
                    ResolutionListener current = listener.get();
                    result = resolver.dependencies(executor, repo, repositories, coordinates, bom, intent, current);
                    current.onResolved();
                }
                for (Map.Entry<String, String> entry : result.entrySet()) {
                    String coordinate = entry.getKey().substring(entry.getKey().indexOf('/') + 1);
                    String declared = repoEntry.getValue().get(coordinate);
                    resolved.setProperty(scope + "/" + entry.getKey(),
                            declared != null && !declared.isEmpty() ? declared : entry.getValue());
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
        resolved.store(context.next().resolve(REQUIRES));
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
        return new String[] {key.substring(0, first), key.substring(first + 1, second), key.substring(second + 1)};
    }
}
