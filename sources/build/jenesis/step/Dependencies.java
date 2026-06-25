package build.jenesis.step;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.DependencyScope;
import build.jenesis.DependencyTreeReport;
import build.jenesis.License;
import build.jenesis.Pinning;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

import static java.util.Objects.requireNonNull;

public class Dependencies implements BuildStep {

    public static final String ARTIFACTS = "artifacts";

    private final transient Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final transient boolean printing;

    public Dependencies(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, false);
    }

    private Dependencies(Map<String, Repository> repositories,
                         Map<String, Resolver> resolvers,
                         Pinning pinning,
                         boolean printing) {
        this.repositories = repositories;
        this.resolvers = new LinkedHashMap<>(resolvers);
        this.pinning = pinning;
        this.printing = printing;
    }

    public Dependencies pinning(Pinning pinning) {
        return new Dependencies(repositories, resolvers, pinning, printing);
    }

    public Dependencies printing(boolean printing) {
        return new Dependencies(repositories, resolvers, pinning, printing);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
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
        SequencedProperties graph = new SequencedProperties();
        SequencedProperties licenses = new SequencedProperties();
        int edge = 0;
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
                    SequencedMap<String, SequencedMap<String, String>> groupVersions = versions
                            .getOrDefault(group, new LinkedHashMap<>());
                    List<SequencedMap<String, String>> scoped = new ArrayList<>();
                    scoped.add(groupVersions.getOrDefault(repo, new LinkedHashMap<>()));
                    for (String managed : resolver.managedPrefixes()) {
                        scoped.add(groupVersions.getOrDefault(managed, new LinkedHashMap<>()));
                    }
                    SequencedMap<String, String> bom = new LinkedHashMap<>();
                    for (SequencedMap<String, String> pins : scoped) {
                        if (pinning == Pinning.VERSIONS) {
                            pins.forEach((coordinate, value) -> {
                                int space = value.indexOf(' ');
                                bom.putIfAbsent(coordinate, space < 0 ? value : value.substring(0, space));
                            });
                        } else if (pinned) {
                            pins.forEach(bom::putIfAbsent);
                        } else {
                            pins.forEach((coordinate, value) -> {
                                if (value.startsWith(":")) {
                                    int space = value.indexOf(' ');
                                    String qualified = space < 0 ? value : value.substring(0, space);
                                    int divider = qualified.indexOf(':', 1);
                                    bom.putIfAbsent(coordinate, divider < 0
                                            ? qualified
                                            : qualified.substring(0, divider));
                                }
                            });
                        }
                    }
                    Resolver.Resolution resolution = resolver.dependencies(executor, repo, wrapped, coordinates, bom, intent);
                    if (printing) {
                        new DependencyTreeReport().render(resolution);
                    }
                    for (Map.Entry<String, Resolver.Resolved> entry : resolution.artifacts().entrySet()) {
                        String coordinate = entry.getKey().substring(entry.getKey().indexOf('/') + 1);
                        String declared = repoEntry.getValue().get(coordinate);
                        String value = declared != null && !declared.isEmpty() ? declared : entry.getValue().checksum();
                        String transitiveKey = group + "/" + scope + "/" + entry.getKey();
                        resolved.setProperty(transitiveKey, value);
                        materialized.putIfAbsent(transitiveKey, entry.getValue());
                    }
                    for (Resolver.Edge dependency : resolution.edges()) {
                        graph.setProperty("edge/" + edge++, String.join("\t",
                                group,
                                scope,
                                repo,
                                Boolean.toString(dependency.followed()),
                                text(dependency.scope()),
                                text(dependency.version()),
                                text(dependency.parent()),
                                dependency.coordinate()));
                    }
                    for (Map.Entry<String, Resolver.Vertex> entry : resolution.vertices().entrySet()) {
                        Resolver.Vertex node = entry.getValue();
                        graph.setProperty("vertex/" + group + "/" + scope + "/" + entry.getKey(), String.join("\t",
                                text(node.resolvedVersion()),
                                text(node.module()),
                                Boolean.toString(node.automatic())));
                        if (!node.licenses().isEmpty()) {
                            String licenseKey = node.resolvedVersion() == null
                                    ? entry.getKey()
                                    : entry.getKey() + "/" + node.resolvedVersion();
                            for (int i = 0; i < node.licenses().size(); i++) {
                                License license = node.licenses().get(i);
                                if (license.name() != null) {
                                    licenses.setProperty(licenseKey + "#" + i + "#name", license.name());
                                }
                                if (license.url() != null) {
                                    licenses.setProperty(licenseKey + "#" + i + "#url", license.url());
                                }
                            }
                        }
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
        graph.store(context.next().resolve("graph.properties"));
        licenses.store(context.next().resolve("licenses.properties"));
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static String text(String value) {
        return value == null ? "" : value;
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

    public static SequencedMap<String, Resolver.Resolution> graph(Iterable<Path> graphFiles,
                                                                  Iterable<Path> licenseFiles) throws IOException {
        SequencedMap<String, SequencedMap<Integer, String[]>> licenseEntries = new LinkedHashMap<>();
        for (Path file : licenseFiles) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            SequencedProperties properties = SequencedProperties.ofFiles(file);
            for (String key : properties.stringPropertyNames()) {
                int last = key.lastIndexOf('#');
                int prior = last < 1 ? -1 : key.lastIndexOf('#', last - 1);
                if (prior < 1) {
                    continue;
                }
                int index;
                try {
                    index = Integer.parseInt(key.substring(prior + 1, last));
                } catch (NumberFormatException _) {
                    continue;
                }
                String[] entry = licenseEntries
                        .computeIfAbsent(key.substring(0, prior), _ -> new TreeMap<>())
                        .computeIfAbsent(index, _ -> new String[2]);
                if (key.substring(last + 1).equals("name")) {
                    entry[0] = properties.getProperty(key);
                } else if (key.substring(last + 1).equals("url")) {
                    entry[1] = properties.getProperty(key);
                }
            }
        }
        SequencedMap<String, List<License>> licenses = new LinkedHashMap<>();
        licenseEntries.forEach((coordinate, byIndex) -> licenses.put(coordinate,
                byIndex.values().stream().map(entry -> new License(entry[0], entry[1])).toList()));
        SequencedMap<String, SequencedSet<Resolver.Edge>> edges = new LinkedHashMap<>();
        SequencedMap<String, SequencedMap<String, Resolver.Vertex>> vertices = new LinkedHashMap<>();
        for (Path file : graphFiles) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            SequencedProperties properties = SequencedProperties.ofFiles(file);
            for (String key : properties.stringPropertyNames()) {
                String value = properties.getProperty(key);
                if (key.startsWith("edge/")) {
                    String[] parts = value.split("\t", -1);
                    if (parts.length != 8) {
                        continue;
                    }
                    edges.computeIfAbsent(parts[0] + "/" + parts[1], _ -> new LinkedHashSet<>())
                            .add(new Resolver.Edge(
                                    parts[6].isEmpty() ? null : parts[6],
                                    parts[7],
                                    parts[5].isEmpty() ? null : parts[5],
                                    parts[4].isEmpty() ? null : parts[4],
                                    Boolean.parseBoolean(parts[3])));
                } else if (key.startsWith("vertex/")) {
                    String rest = key.substring("vertex/".length());
                    int first = rest.indexOf('/');
                    int second = first < 0 ? -1 : rest.indexOf('/', first + 1);
                    if (second < 0) {
                        continue;
                    }
                    String groupScope = rest.substring(0, second);
                    String coordinate = rest.substring(second + 1);
                    String[] parts = value.split("\t", -1);
                    String resolvedVersion = parts[0].isEmpty() ? null : parts[0];
                    List<License> declared = resolvedVersion == null
                            ? List.of()
                            : licenses.getOrDefault(coordinate + "/" + resolvedVersion, List.of());
                    vertices.computeIfAbsent(groupScope, _ -> new LinkedHashMap<>())
                            .putIfAbsent(coordinate, new Resolver.Vertex(
                                    resolvedVersion,
                                    parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null,
                                    parts.length > 2 && Boolean.parseBoolean(parts[2]),
                                    declared));
                }
            }
        }
        SequencedMap<String, Resolver.Resolution> result = new LinkedHashMap<>();
        SequencedSet<String> groupScopes = new LinkedHashSet<>(edges.sequencedKeySet());
        groupScopes.addAll(vertices.sequencedKeySet());
        for (String groupScope : groupScopes) {
            result.put(groupScope, new Resolver.Resolution(
                    new LinkedHashMap<>(),
                    new ArrayList<>(edges.getOrDefault(groupScope, new LinkedHashSet<>())),
                    vertices.getOrDefault(groupScope, new LinkedHashMap<>())));
        }
        return result;
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
