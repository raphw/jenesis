package build.jenesis.maven;

import module java.base;
import java.util.jar.Attributes;
import build.jenesis.DependencyScope;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;

/**
 * Resolves module aliases declared as {@code @jenesis.alias <module-name>
 * <groupId>/<artifactId>[/<type>[/<classifier>]] [<version>]} over a Maven-backed module
 * resolver. An aliased module is synthesized locally: its discovery POM declares the target as
 * its only dependency, and its jar is empty apart from an {@code Automatic-Module-Name} manifest
 * entry carrying the alias. Requiring the alias thereby grants implied readability of the
 * (typically non-modular) target under a stable module name. The target follows the Maven pin
 * token grammar. Its version resolves like any Maven coordinate's: a pin or BOM entry for the
 * coordinate wins - and is the only place a checksum can be declared - then the inline version,
 * and without either the latest release is negotiated implicitly. The synthetic artifacts are
 * internal and never published.
 */
public class MavenAliasResolver implements Resolver {

    public static final String GROUP = "jenesis.alias";

    private static final String VERSION = "0";

    private final String mavenPrefix;
    private final Resolver delegate;

    public MavenAliasResolver(String mavenPrefix, Resolver delegate) {
        this.mavenPrefix = mavenPrefix;
        this.delegate = delegate;
    }

    @Override
    public SequencedSet<String> managedPrefixes() {
        return delegate.managedPrefixes();
    }

    @Override
    public Resolver.Resolution dependencies(Executor executor,
                                            String prefix,
                                            Map<String, Repository> repositories,
                                            SequencedMap<String, SequencedSet<String>> coordinates,
                                            SequencedMap<String, String> versions,
                                            DependencyScope scope) throws IOException {
        return delegate.dependencies(executor, prefix, repositories, coordinates, versions, scope);
    }

    @Override
    public Resolver.Resolution dependencies(Executor executor,
                                            String prefix,
                                            Map<String, Repository> repositories,
                                            SequencedMap<String, SequencedSet<String>> coordinates,
                                            SequencedMap<String, String> versions,
                                            SequencedMap<String, String> aliases,
                                            DependencyScope scope) throws IOException {
        if (aliases.isEmpty()) {
            return delegate.dependencies(executor, prefix, repositories, coordinates, versions, scope);
        }
        String base = Resolver.base(prefix);
        SequencedMap<String, byte[]> poms = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String alias = entry.getKey(), declaration = entry.getValue();
            int space = declaration.indexOf(' ');
            String token = space < 0 ? declaration : declaration.substring(0, space);
            String inline = space < 0 ? null : declaration.substring(space + 1).trim();
            if (versions.containsKey(alias)) {
                throw new IllegalArgumentException("Module " + alias + " is an alias for " + token
                        + " - pin the target instead: @jenesis.pin " + token + " <version>");
            }
            MavenDependencyKey key;
            try {
                key = MavenDependencyKey.parseKey(token);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Malformed alias target for " + alias + ": " + token, e);
            }
            // A pin or BOM entry for the target coordinate wins - and is where a checksum is
            // declared - then the inline version; without either, the latest release is
            // negotiated, as for any Maven coordinate that names no version.
            String pinned = versions.get(token);
            String version;
            if (pinned != null) {
                version = version(pinned, alias, token);
            } else if (inline != null) {
                version = version(inline, alias, token);
            } else {
                version = MavenDefaultVersionNegotiator.maven().get().resolve(executor,
                        MavenRepository.of(repositories.getOrDefault(mavenPrefix, Repository.empty())),
                        key.groupId(),
                        key.artifactId(),
                        key.type() == null ? "jar" : key.type(),
                        key.classifier(),
                        "RELEASE");
            }
            poms.put(alias, pom(alias, key, version));
        }
        Map<String, Repository> wrapped = new LinkedHashMap<>(repositories);
        Repository discovery = (_, coordinate) -> {
            if (coordinate.endsWith(":pom")) {
                byte[] bytes = poms.get(coordinate.substring(0, coordinate.length() - ":pom".length()));
                if (bytes != null) {
                    return Optional.of(() -> new ByteArrayInputStream(bytes));
                }
            }
            return Optional.empty();
        };
        wrapped.merge(base, discovery, (existing, overlay) -> existing.prepend(overlay));
        ConcurrentMap<String, Path> jars = new ConcurrentHashMap<>();
        Repository artifacts = (_, coordinate) -> {
            if (!coordinate.startsWith(GROUP + "/")) {
                return Optional.empty();
            }
            String tail = coordinate.substring(GROUP.length() + 1);
            int slash = tail.indexOf('/');
            String alias = slash < 0 ? tail : tail.substring(0, slash);
            if (slash < 0 || !tail.substring(slash + 1).equals(VERSION) || !poms.containsKey(alias)) {
                return Optional.empty();
            }
            Path jar;
            try {
                jar = jars.computeIfAbsent(alias, name -> {
                    try {
                        return emptyJar(name);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            return Optional.of(RepositoryItem.ofFile(jar, true));
        };
        wrapped.merge(mavenPrefix, artifacts, (existing, overlay) -> MavenRepository.of(existing).prepend(overlay));
        Resolver.Resolution resolution = delegate.dependencies(executor, prefix, wrapped, coordinates, versions, scope);
        SequencedMap<String, Resolver.Resolved> renamed = new LinkedHashMap<>();
        resolution.artifacts().forEach((coordinate, resolved) ->
                renamed.put(rename(coordinate, base, poms.sequencedKeySet()), resolved));
        List<Resolver.Edge> edges = new ArrayList<>();
        for (Resolver.Edge edge : resolution.edges()) {
            edges.add(new Resolver.Edge(rename(edge.parent(), base, poms.sequencedKeySet()),
                    rename(edge.coordinate(), base, poms.sequencedKeySet()),
                    edge.version(),
                    edge.scope(),
                    edge.followed()));
        }
        SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
        resolution.vertices().forEach((coordinate, vertex) ->
                vertices.put(rename(coordinate, base, poms.sequencedKeySet()), vertex));
        return new Resolver.Resolution(renamed, edges, vertices);
    }

    /**
     * Maps a synthetic alias coordinate back to a module coordinate: the synthetic Maven identity
     * is a resolution detail that must not leak into descriptors, reports or published POMs. The
     * renamed coordinate carries no version, so a repin never generates a pin for the alias.
     */
    private String rename(String coordinate, String base, SequencedCollection<String> aliases) {
        if (coordinate == null) {
            return null;
        }
        for (String alias : aliases) {
            String synthetic = mavenPrefix + "/" + GROUP + "/" + alias;
            if (coordinate.equals(synthetic) || coordinate.startsWith(synthetic + "/")) {
                return base + "/" + alias;
            }
        }
        return coordinate;
    }

    private static String version(String value, String alias, String target) {
        int space = value.indexOf(' ');
        String version = space < 0 ? value : value.substring(0, space);
        if (version.isEmpty() || version.startsWith(":")) {
            throw new IllegalArgumentException("Malformed version '" + value + "' for " + target
                    + ", aliased as " + alias);
        }
        return version;
    }

    private static byte[] pom(String alias, MavenDependencyKey key, String version) {
        String type = key.type() == null || key.type().equals("jar")
                ? ""
                : "\n            <type>" + key.type() + "</type>";
        String classifier = key.classifier() == null
                ? ""
                : "\n            <classifier>" + key.classifier() + "</classifier>";
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <dependencies>
                        <dependency>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>%s</version>%s%s
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(GROUP, alias, VERSION, key.groupId(), key.artifactId(), version, type, classifier);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static Path emptyJar(String alias) throws IOException {
        Path file = Files.createTempFile("alias-" + alias, ".jar");
        file.toFile().deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", alias);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file))) {
            // The jar is written deterministically so its bytes - and with them the containing
            // build step's output checksums - are stable across builds.
            JarEntry entry = new JarEntry(JarFile.MANIFEST_NAME);
            entry.setTime(0L);
            output.putNextEntry(entry);
            manifest.write(output);
            output.closeEntry();
        }
        return file;
    }
}
