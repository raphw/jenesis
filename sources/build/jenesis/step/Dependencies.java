package build.jenesis.step;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.DependencyScope;
import build.jenesis.License;
import build.jenesis.Pinning;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.ResolutionContext;
import build.jenesis.ResolutionListener;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

import static java.util.Objects.requireNonNull;

public class Dependencies implements BuildStep {

    public static final String ARTIFACTS = "artifacts";

    private final transient Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final transient Supplier<ResolutionListener> listener;
    private final boolean capturing;

    public Dependencies(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, null, false);
    }

    private Dependencies(Map<String, Repository> repositories,
                         Map<String, Resolver> resolvers,
                         Pinning pinning,
                         Supplier<ResolutionListener> listener,
                         boolean capturing) {
        this.repositories = repositories;
        this.resolvers = new LinkedHashMap<>(resolvers);
        this.pinning = pinning;
        this.listener = listener;
        this.capturing = capturing;
    }

    public Dependencies pinning(Pinning pinning) {
        return new Dependencies(repositories, resolvers, pinning, listener, capturing);
    }

    public Dependencies listening(Supplier<ResolutionListener> listener) {
        return new Dependencies(repositories, resolvers, pinning, listener, capturing);
    }

    public Dependencies capturing(boolean capturing) {
        return new Dependencies(repositories, resolvers, pinning, listener, capturing);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        if (listener != null || capturing) {
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
        SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>> versions = new LinkedHashMap<>();
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
                    int first = key.indexOf('/');
                    int second = first < 1 ? -1 : key.indexOf('/', first + 1);
                    if (first < 1 || second <= first || second == key.length() - 1) {
                        throw new IllegalArgumentException("Malformed version pin '"
                                + key
                                + "' in "
                                + versionsFile
                                + ": expected <group>/<repository>/<coordinate>");
                    }
                    versions.computeIfAbsent(key.substring(0, first), _ -> new LinkedHashMap<>())
                            .computeIfAbsent(key.substring(first + 1, second), _ -> new LinkedHashMap<>())
                            .putIfAbsent(key.substring(second + 1), properties.getProperty(key));
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
        Path libs = Files.createDirectories(context.next().resolve("resolved"));
        Path previousLibs = context.previous() == null ? null : context.previous().resolve("resolved");
        Map<String, Repository> wrapped = new LinkedHashMap<>();
        repositories.forEach((name, repository) -> {
            Repository effective = repository;
            if (previousLibs != null) {
                effective = effective.prepend((_, coordinate) -> {
                    Path file = previousLibs.resolve(BuildExecutorModule.encode(coordinate) + ".jar");
                    return Files.exists(file) ? Optional.of(RepositoryItem.ofFile(file)) : Optional.empty();
                });
            }
            wrapped.put(name, effective.cached(libs));
        });
        SequencedProperties resolved = new SequencedProperties();
        SequencedMap<String, Resolver.Resolved> materialized = new LinkedHashMap<>();
        SequencedMap<String, List<License>> capturedLicenses = capturing ? new LinkedHashMap<>() : null;
        for (Map.Entry<String, SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>>> groupEntry : requires.entrySet()) {
            String group = groupEntry.getKey();
            for (String scope : groupEntry.getValue().sequencedKeySet()) {
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
                        SequencedMap<String, SequencedMap<String, String>> groupVersions = versions
                                .getOrDefault(group, new LinkedHashMap<>());
                        bom.putAll(groupVersions.getOrDefault(repo, new LinkedHashMap<>()));
                        for (String managed : resolver.managedPrefixes()) {
                            groupVersions.getOrDefault(managed, new LinkedHashMap<>()).forEach(bom::putIfAbsent);
                        }
                    }
                    SequencedMap<String, Resolver.Resolved> result;
                    ResolutionListener external = listener == null ? null : listener.get();
                    ResolutionListener current = capturedLicenses == null
                            ? external
                            : new LicenseCapture(external, capturedLicenses);
                    if (current == null) {
                        result = resolver.dependencies(executor, repo, wrapped, coordinates, bom, intent);
                    } else {
                        result = resolver.dependencies(executor, repo, wrapped, coordinates, bom, intent, current);
                        current.onResolved();
                    }
                    for (Map.Entry<String, Resolver.Resolved> entry : result.entrySet()) {
                        String coordinate = entry.getKey().substring(entry.getKey().indexOf('/') + 1);
                        String declared = repoEntry.getValue().get(coordinate);
                        String value = declared != null && !declared.isEmpty() ? declared : entry.getValue().checksum();
                        String transitiveKey = group + "/" + scope + "/" + entry.getKey();
                        resolved.setProperty(transitiveKey, value);
                        materialized.putIfAbsent(transitiveKey, entry.getValue());
                    }
                }
            }
        }
        SequencedProperties index = new SequencedProperties();
        SequencedMap<String, Path> placed = new LinkedHashMap<>();
        SequencedMap<String, String> checksums = new LinkedHashMap<>();
        SequencedMap<String, Boolean> internals = new LinkedHashMap<>();
        for (Map.Entry<String, Resolver.Resolved> entry : materialized.entrySet()) {
            String key = entry.getKey();
            int first = key.indexOf('/'), second = key.indexOf('/', first + 1);
            if (first < 0 || second < 0) {
                continue;
            }
            String dependency = key.substring(second + 1), name = dependency.replace('/', '-') + ".jar";
            Resolver.Resolved artifact = entry.getValue();
            String value = resolved.getProperty(key);
            Path file = placed.get(name);
            if (file == null) {
                if (artifact.internal()) {
                    file = libs.resolve(name);
                    if (!Files.exists(file)) {
                        BuildStep.linkOrCopy(file, artifact.file());
                    }
                } else {
                    file = artifact.file();
                }
                placed.put(name, file);
                internals.put(name, artifact.internal());
            }
            String relative = context.next().relativize(file).toString().replace(File.separatorChar, '/');
            index.setProperty(key, value.isEmpty() ? relative : relative + " " + value);
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
        if (pinning == Pinning.STRICT) {
            Set<Path> pinnedFiles = new HashSet<>();
            for (Map.Entry<String, String> entry : checksums.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    pinnedFiles.add(placed.get(entry.getKey()));
                }
            }
            for (Map.Entry<String, String> entry : checksums.entrySet()) {
                if (entry.getValue().isEmpty()
                        && !internals.get(entry.getKey())
                        && !pinnedFiles.contains(placed.get(entry.getKey()))) {
                    throw new IllegalStateException("No checksum pinned for " + entry.getKey() + " (strict pinning is enabled)");
                }
            }
        }
        index.store(context.next().resolve(DEPENDENCIES));
        if (capturedLicenses != null) {
            SequencedProperties licenses = new SequencedProperties();
            for (Map.Entry<String, List<License>> entry : capturedLicenses.entrySet()) {
                List<License> list = entry.getValue();
                for (int index2 = 0; index2 < list.size(); index2++) {
                    License license = list.get(index2);
                    if (license.name() != null) {
                        licenses.setProperty(entry.getKey() + "#" + index2 + "#name", license.name());
                    }
                    if (license.url() != null) {
                        licenses.setProperty(entry.getKey() + "#" + index2 + "#url", license.url());
                    }
                }
            }
            licenses.store(context.next().resolve("licenses.properties"));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private record LicenseCapture(ResolutionListener delegate,
                                  SequencedMap<String, List<License>> licenses) implements ResolutionListener {

        @Override
        public void onDependency(String prefix,
                                 String parent,
                                 String coordinate,
                                 String version,
                                 String scope,
                                 boolean followed,
                                 Supplier<ResolutionContext> context) {
            if (delegate != null) {
                delegate.onDependency(prefix, parent, coordinate, version, scope, followed, context);
            }
        }

        @Override
        public void onResolution(String prefix, String coordinate, String version) {
            if (delegate != null) {
                delegate.onResolution(prefix, coordinate, version);
            }
        }

        @Override
        public void onLicenses(String prefix, String coordinate, String version, List<License> licenses) {
            this.licenses.putIfAbsent(coordinate, licenses);
            if (delegate != null) {
                delegate.onLicenses(prefix, coordinate, version, licenses);
            }
        }

        @Override
        public void onResolved() {
            if (delegate != null) {
                delegate.onResolved();
            }
        }
    }

    public static List<Path> select(Path folder, String scope) throws IOException {
        return select(folder, "main", scope);
    }

    public static List<Path> select(Path folder, String group, String scope) throws IOException {
        Path file = folder.resolve(BuildStep.DEPENDENCIES);
        if (!Files.exists(file)) {
            return List.of();
        }
        SequencedProperties properties = SequencedProperties.ofFiles(file);
        SequencedSet<Path> selected = new LinkedHashSet<>();
        String prefix = group + "/" + scope + "/";
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String value = properties.getProperty(key);
                int space = value.indexOf(' ');
                Path jar = folder.resolve(space < 0 ? value : value.substring(0, space)).normalize();
                if (Files.exists(jar)) {
                    selected.add(jar);
                }
            }
        }
        return new ArrayList<>(selected);
    }

    public static List<Path> all(Path folder) throws IOException {
        Path file = folder.resolve(BuildStep.DEPENDENCIES);
        if (!Files.exists(file)) {
            return List.of();
        }
        SequencedProperties properties = SequencedProperties.ofFiles(file);
        SequencedSet<Path> selected = new LinkedHashSet<>();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            int space = value.indexOf(' ');
            Path jar = folder.resolve(space < 0 ? value : value.substring(0, space)).normalize();
            if (Files.exists(jar)) {
                selected.add(jar);
            }
        }
        return new ArrayList<>(selected);
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
