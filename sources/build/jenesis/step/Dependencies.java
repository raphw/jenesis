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
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

import static java.util.Objects.requireNonNull;

public class Dependencies implements BuildStep {

    public static final String SPDX = "spdx.properties";

    private final transient Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;

    public Dependencies(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null);
    }

    private Dependencies(Map<String, Repository> repositories,
                         Map<String, Resolver> resolvers,
                         Pinning pinning) {
        this.repositories = repositories;
        this.resolvers = new LinkedHashMap<>(resolvers);
        this.pinning = pinning;
    }

    public Dependencies pinning(Pinning pinning) {
        return new Dependencies(repositories, resolvers, pinning);
    }

    public static SequencedMap<String, String> bomEntries(SequencedProperties properties, String group) {
        SequencedMap<String, String> entries = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key).trim();
            if (value.endsWith("]")) {
                throw new IllegalArgumentException("Malformed BOM entry '"
                        + key
                        + "': platform guards are not supported in BOM files,"
                        + " guard the @jenesis.bom declaration instead");
            }
            int firstSlash = key.indexOf('/');
            int secondSlash = firstSlash < 0 ? -1 : key.indexOf('/', firstSlash + 1);
            String expanded;
            if (firstSlash < 0) {
                expanded = "module/" + key;
            } else if (secondSlash < 0) {
                if (firstSlash < 1 || firstSlash == key.length() - 1) {
                    throw new IllegalArgumentException("Malformed BOM entry '"
                            + key
                            + "': expected <module>, <groupId>/<artifactId>,"
                            + " or <repository>/<coordinate>");
                }
                expanded = "maven/" + key;
            } else {
                if (firstSlash < 1 || secondSlash == firstSlash + 1 || secondSlash == key.length() - 1) {
                    throw new IllegalArgumentException("Malformed BOM entry '"
                            + key
                            + "': expected <module>, <groupId>/<artifactId>,"
                            + " or <repository>/<coordinate>");
                }
                expanded = key;
            }
            entries.put(group + "/" + expanded, value);
        }
        return entries;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(
                Path.of(REQUIRES),
                Path.of(VERSIONS),
                Path.of(BOMS),
                Path.of(EXCLUSIONS),
                Path.of(SPDX)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        boolean pinned = pinning != Pinning.IGNORE;
        Map<String, String> aliases = new LinkedHashMap<>(DEFAULT_ALIASES);
        Map<String, String> categories = new LinkedHashMap<>(DEFAULT_CATEGORIES);
        for (BuildStepArgument argument : arguments.values()) {
            Path file = argument.folder().resolve(SPDX);
            if (Files.isRegularFile(file)) {
                SequencedProperties properties = SequencedProperties.ofFiles(file);
                for (String key : properties.stringPropertyNames()) {
                    String value = properties.getProperty(key).trim();
                    if (key.startsWith("alias/")) {
                        String name = key.substring("alias/".length()).toLowerCase(Locale.ROOT).trim();
                        if (value.isEmpty()) {
                            aliases.remove(name);
                        } else {
                            aliases.put(name, value);
                        }
                    } else if (key.startsWith("category/")) {
                        String identifier = key.substring("category/".length()).trim();
                        if (value.isEmpty()) {
                            categories.remove(identifier);
                        } else {
                            categories.put(identifier, value);
                        }
                    } else {
                        throw new IllegalArgumentException("Expected key to be prefixed: " + key);
                    }
                }
            }
        }
        SequencedMap<String, SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>>> requires = new LinkedHashMap<>();
        SequencedMap<String, SequencedMap<String, SequencedMap<String, String>>> versions = new LinkedHashMap<>();
        SequencedMap<String, String> bomTokens = new LinkedHashMap<>();
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
            Path bomsFile = argument.folder().resolve(BOMS);
            if (Files.exists(bomsFile)) {
                SequencedProperties properties = SequencedProperties.ofFiles(bomsFile);
                for (String key : properties.stringPropertyNames()) {
                    String reference = key.startsWith("bom/")
                            ? key.substring(4)
                            : key.startsWith("entry/") ? key.substring(6) : null;
                    int first = reference == null ? -1 : reference.indexOf('/');
                    int second = first < 1 ? -1 : reference.indexOf('/', first + 1);
                    if (first < 1 || second <= first || second == reference.length() - 1) {
                        throw new IllegalArgumentException("Malformed BOM reference '"
                                + key
                                + "' in "
                                + bomsFile
                                + ": expected bom/<group>/<repository>/<coordinate>"
                                + " or entry/<group>/<repository>/<coordinate>");
                    }
                    bomTokens.putIfAbsent(key, properties.getProperty(key));
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
        if (!bomTokens.isEmpty()) {
            SequencedMap<String, String> managed = new LinkedHashMap<>();
            SequencedProperties resolvedBoms = new SequencedProperties();
            for (Map.Entry<String, String> token : bomTokens.entrySet()) {
                if (token.getKey().startsWith("entry/")) {
                    managed.putIfAbsent(token.getKey().substring(6), token.getValue());
                    continue;
                }
                String reference = token.getKey().substring(4);
                int first = reference.indexOf('/');
                int second = reference.indexOf('/', first + 1);
                String group = reference.substring(0, first);
                String repo = reference.substring(first + 1, second);
                String coordinate = reference.substring(second + 1);
                String value = token.getValue();
                int space = value.indexOf(' ');
                String version = space < 0 ? value : value.substring(0, space);
                String checksum = space < 0 ? "" : value.substring(space + 1).trim();
                Repository repository = wrapped.get(Resolver.base(repo));
                if (repository == null) {
                    throw new IllegalArgumentException("Unknown repository for BOM: " + reference);
                }
                boolean verify = pinning != Pinning.VERSIONS && pinning != Pinning.IGNORE;
                Resolver.Resolved bom;
                try {
                    bom = Resolver.materialize(executor,
                            repository,
                            version.isEmpty()
                                    ? coordinate + ":properties"
                                    : coordinate + "/" + version + ":properties",
                            verify ? checksum : null);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("Failed to fetch BOM " + reference, e);
                }
                if (pinning == Pinning.STRICT && checksum.isEmpty() && !bom.internal()) {
                    throw new IllegalStateException("No checksum pinned for BOM "
                            + reference
                            + " (strict pinning is enabled)");
                }
                if (!version.isEmpty()) {
                    resolvedBoms.setProperty("bom/" + reference + "/" + version,
                            context.next().toAbsolutePath().relativize(bom.file().toAbsolutePath())
                                    .toString().replace(File.separatorChar, '/'));
                }
                bomEntries(SequencedProperties.ofFiles(bom.file()), group).forEach(managed::putIfAbsent);
            }
            for (Map.Entry<String, String> entry : managed.entrySet()) {
                String key = entry.getKey();
                int first = key.indexOf('/');
                int second = key.indexOf('/', first + 1);
                resolvedBoms.setProperty("entry/" + key, entry.getValue());
                versions.computeIfAbsent(key.substring(0, first), _ -> new LinkedHashMap<>())
                        .computeIfAbsent(key.substring(first + 1, second), _ -> new LinkedHashMap<>())
                        .putIfAbsent(key.substring(second + 1), entry.getValue());
            }
            if (!resolvedBoms.isEmpty()) {
                resolvedBoms.store(context.next().resolve(BOMS));
            }
        }
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
                                String id = license.id();
                                if (id == null && license.name() != null) {
                                    id = aliases.get(license.name().toLowerCase(Locale.ROOT).trim());
                                }
                                String category = license.category();
                                if (category == null && id != null) {
                                    category = categories.get(id);
                                }
                                if (id != null) {
                                    licenses.setProperty(licenseKey + "#" + i + "#id", id);
                                }
                                if (category != null) {
                                    licenses.setProperty(licenseKey + "#" + i + "#category", category);
                                }
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

    private static final Map<String, String> DEFAULT_ALIASES = Map.ofEntries(
            Map.entry("apache license 2.0", "Apache-2.0"),
            Map.entry("apache license, version 2.0", "Apache-2.0"),
            Map.entry("apache license version 2.0", "Apache-2.0"),
            Map.entry("apache software license - version 2.0", "Apache-2.0"),
            Map.entry("the apache software license, version 2.0", "Apache-2.0"),
            Map.entry("the apache license, version 2.0", "Apache-2.0"),
            Map.entry("apache 2.0", "Apache-2.0"),
            Map.entry("apache-2.0", "Apache-2.0"),
            Map.entry("asl 2.0", "Apache-2.0"),
            Map.entry("apache license", "Apache-2.0"),
            Map.entry("mit license", "MIT"),
            Map.entry("the mit license", "MIT"),
            Map.entry("mit", "MIT"),
            Map.entry("mit-0", "MIT-0"),
            Map.entry("bsd license", "BSD-2-Clause"),
            Map.entry("the bsd license", "BSD-2-Clause"),
            Map.entry("bsd-2-clause", "BSD-2-Clause"),
            Map.entry("bsd 2-clause license", "BSD-2-Clause"),
            Map.entry("bsd-3-clause", "BSD-3-Clause"),
            Map.entry("bsd 3-clause license", "BSD-3-Clause"),
            Map.entry("the bsd 3-clause license", "BSD-3-Clause"),
            Map.entry("new bsd license", "BSD-3-Clause"),
            Map.entry("revised bsd license", "BSD-3-Clause"),
            Map.entry("eclipse distribution license - v 1.0", "BSD-3-Clause"),
            Map.entry("eclipse distribution license (new bsd license)", "BSD-3-Clause"),
            Map.entry("eclipse public license - v 1.0", "EPL-1.0"),
            Map.entry("eclipse public license 1.0", "EPL-1.0"),
            Map.entry("eclipse public license v1.0", "EPL-1.0"),
            Map.entry("epl-1.0", "EPL-1.0"),
            Map.entry("eclipse public license - v 2.0", "EPL-2.0"),
            Map.entry("eclipse public license 2.0", "EPL-2.0"),
            Map.entry("eclipse public license v2.0", "EPL-2.0"),
            Map.entry("eclipse public license", "EPL-2.0"),
            Map.entry("epl-2.0", "EPL-2.0"),
            Map.entry("gnu lesser general public license", "LGPL-2.1-or-later"),
            Map.entry("lesser general public license", "LGPL-2.1-or-later"),
            Map.entry("gnu lesser general public license, version 2.1", "LGPL-2.1-only"),
            Map.entry("lgpl-2.1", "LGPL-2.1-only"),
            Map.entry("lgpl 2.1", "LGPL-2.1-only"),
            Map.entry("lgpl-3.0", "LGPL-3.0-only"),
            Map.entry("lgpl", "LGPL-2.1-or-later"),
            Map.entry("gnu general public license, version 2", "GPL-2.0-only"),
            Map.entry("gnu general public license v2.0", "GPL-2.0-only"),
            Map.entry("gpl-2.0", "GPL-2.0-only"),
            Map.entry("gnu general public license, version 2 with the classpath exception", "GPL-2.0-with-classpath-exception"),
            Map.entry("gpl-2.0-with-classpath-exception", "GPL-2.0-with-classpath-exception"),
            Map.entry("gnu general public license, version 3", "GPL-3.0-only"),
            Map.entry("gpl-3.0", "GPL-3.0-only"),
            Map.entry("gnu affero general public license", "AGPL-3.0-or-later"),
            Map.entry("affero general public license", "AGPL-3.0-or-later"),
            Map.entry("agpl-3.0", "AGPL-3.0-only"),
            Map.entry("mozilla public license 2.0", "MPL-2.0"),
            Map.entry("mozilla public license, version 2.0", "MPL-2.0"),
            Map.entry("mpl 2.0", "MPL-2.0"),
            Map.entry("mpl-2.0", "MPL-2.0"),
            Map.entry("mozilla public license 1.1", "MPL-1.1"),
            Map.entry("common development and distribution license 1.0", "CDDL-1.0"),
            Map.entry("cddl 1.0", "CDDL-1.0"),
            Map.entry("cddl-1.0", "CDDL-1.0"),
            Map.entry("common development and distribution license 1.1", "CDDL-1.1"),
            Map.entry("cddl 1.1", "CDDL-1.1"),
            Map.entry("cddl-1.1", "CDDL-1.1"),
            Map.entry("isc license", "ISC"),
            Map.entry("isc", "ISC"),
            Map.entry("boost software license 1.0", "BSL-1.0"),
            Map.entry("boost software license", "BSL-1.0"),
            Map.entry("bsl-1.0", "BSL-1.0"),
            Map.entry("the unlicense", "Unlicense"),
            Map.entry("unlicense", "Unlicense"),
            Map.entry("cc0 1.0 universal", "CC0-1.0"),
            Map.entry("cc0", "CC0-1.0"),
            Map.entry("public domain", "CC0-1.0"),
            Map.entry("the zlib/libpng license", "Zlib"),
            Map.entry("zlib", "Zlib"),
            Map.entry("python software foundation license", "PSF-2.0"),
            Map.entry("wtfpl", "WTFPL"));

    private static final Map<String, String> DEFAULT_CATEGORIES = Map.ofEntries(
            Map.entry("Apache-2.0", "permissive"),
            Map.entry("MIT", "permissive"),
            Map.entry("MIT-0", "permissive"),
            Map.entry("BSD-2-Clause", "permissive"),
            Map.entry("BSD-3-Clause", "permissive"),
            Map.entry("ISC", "permissive"),
            Map.entry("BSL-1.0", "permissive"),
            Map.entry("Zlib", "permissive"),
            Map.entry("PSF-2.0", "permissive"),
            Map.entry("Unlicense", "public-domain"),
            Map.entry("CC0-1.0", "public-domain"),
            Map.entry("WTFPL", "public-domain"),
            Map.entry("EPL-1.0", "weak-copyleft"),
            Map.entry("EPL-2.0", "weak-copyleft"),
            Map.entry("MPL-1.1", "weak-copyleft"),
            Map.entry("MPL-2.0", "weak-copyleft"),
            Map.entry("CDDL-1.0", "weak-copyleft"),
            Map.entry("CDDL-1.1", "weak-copyleft"),
            Map.entry("LGPL-2.1-only", "weak-copyleft"),
            Map.entry("LGPL-2.1-or-later", "weak-copyleft"),
            Map.entry("LGPL-3.0-only", "weak-copyleft"),
            Map.entry("LGPL-3.0-or-later", "weak-copyleft"),
            Map.entry("GPL-2.0-with-classpath-exception", "weak-copyleft"),
            Map.entry("GPL-2.0-only", "strong-copyleft"),
            Map.entry("GPL-2.0-or-later", "strong-copyleft"),
            Map.entry("GPL-3.0-only", "strong-copyleft"),
            Map.entry("GPL-3.0-or-later", "strong-copyleft"),
            Map.entry("AGPL-3.0-only", "network-copyleft"),
            Map.entry("AGPL-3.0-or-later", "network-copyleft"));

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
                        .computeIfAbsent(index, _ -> new String[4]);
                switch (key.substring(last + 1)) {
                    case "id" -> entry[0] = properties.getProperty(key);
                    case "category" -> entry[1] = properties.getProperty(key);
                    case "name" -> entry[2] = properties.getProperty(key);
                    case "url" -> entry[3] = properties.getProperty(key);
                    default -> {
                    }
                }
            }
        }
        SequencedMap<String, List<License>> licenses = new LinkedHashMap<>();
        licenseEntries.forEach((coordinate, byIndex) -> licenses.put(coordinate,
                byIndex.values().stream().map(entry -> new License(entry[0], entry[1], entry[2], entry[3])).toList()));
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
