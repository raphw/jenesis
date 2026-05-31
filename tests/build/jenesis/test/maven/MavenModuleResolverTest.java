package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenDefaultVersionNegotiator;
import build.jenesis.maven.MavenModuleResolver;
import build.jenesis.maven.MavenPomResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenModuleResolverTest {

    @TempDir
    private Path mavenRepoFolder;

    private MavenPomResolver mavenPomResolver;

    @BeforeEach
    public void setUp() {
        mavenPomResolver = new MavenPomResolver(MavenDefaultVersionNegotiator.maven());
    }

    @Test
    public void resolves_from_unpinned_discovery_pom() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        Repository discovery = stubRepository(fetched, Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                    <version>1.2.3</version>
                </project>"""));

        SequencedMap<String, String> resolved = new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                true);

        assertThat(resolved).containsExactly(Map.entry("maven/org.example/example-core/1.2.3", ""));
        assertThat(fetched).containsOnlyKeys("foo.bar:pom");
    }

    @Test
    public void pinned_version_forces_versioned_fetch() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        Repository discovery = stubRepository(fetched, Map.of("foo.bar/9.9:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                    <version>9.9</version>
                </project>"""));

        SequencedMap<String, String> resolved = new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("foo.bar", "9.9")),
                true);

        assertThat(resolved).containsExactly(Map.entry("maven/org.example/example-core/9.9", ""));
        assertThat(fetched).containsOnlyKeys("foo.bar/9.9:pom");
    }

    @Test
    public void threads_pinned_checksum_into_resolved_root() throws IOException {
        Repository discovery = stubRepository(new LinkedHashMap<>(), Map.of("foo.bar/1.0:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                    <version>1.0</version>
                </project>"""));

        SequencedMap<String, String> resolved = new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("foo.bar", "1.0 SHA-256/deadbeef")),
                true);

        assertThat(resolved).containsExactly(Map.entry("maven/org.example/example-core/1.0", "SHA-256/deadbeef"));
    }

    @Test
    public void walks_transitives_via_maven_repository() throws IOException {
        addToMavenRepository("org.transitive", "lib", "2.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.transitive</groupId>
                    <artifactId>lib</artifactId>
                    <version>2.0</version>
                </project>""");
        Repository discovery = stubRepository(new LinkedHashMap<>(), Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.transitive</groupId>
                            <artifactId>lib</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </project>"""));

        SequencedMap<String, String> resolved = new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                true);

        assertThat(resolved).containsExactly(
                Map.entry("maven/org.example/example-core/1.0", ""),
                Map.entry("maven/org.transitive/lib/2.0", ""));
    }

    @Test
    public void does_not_refetch_root_pom_from_maven_repository() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        Repository discovery = stubRepository(fetched, Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                    <version>1.0</version>
                </project>"""));

        new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                true);

        assertThat(Files.exists(mavenRepoFolder.resolve("org.example/example-core/1.0/example-core-1.0.pom"))).isFalse();
    }

    @Test
    public void throws_when_discovery_pom_is_missing() {
        Repository discovery = stubRepository(new LinkedHashMap<>(), Map.of());

        assertThatThrownBy(() -> new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No POM found for foo.bar");
    }

    @Test
    public void rejects_exclusions_on_module_coordinates() {
        Repository discovery = stubRepository(new LinkedHashMap<>(), Map.of());

        assertThatThrownBy(() -> new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", new LinkedHashSet<>(List.of("org.x/y")))),
                new LinkedHashMap<>(),
                true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exclusions");
    }

    @Test
    public void does_not_hoist_declared_module_dependency_management() throws IOException {
        addToMavenRepository("org.mid", "mid", "1.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.mid</groupId>
                    <artifactId>mid</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.transitive</groupId>
                            <artifactId>lib</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </project>""");
        addToMavenRepository("org.transitive", "lib", "2.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.transitive</groupId>
                    <artifactId>lib</artifactId>
                    <version>2.0</version>
                </project>""");
        Repository discovery = stubRepository(new LinkedHashMap<>(), Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                    <version>1.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.transitive</groupId>
                                <artifactId>lib</artifactId>
                                <version>1.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.mid</groupId>
                            <artifactId>mid</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                </project>"""));

        SequencedMap<String, String> resolved = new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                true);

        assertThat(resolved).containsExactly(
                Map.entry("maven/org.example/example-core/1.0", ""),
                Map.entry("maven/org.mid/mid/1.0", ""),
                Map.entry("maven/org.transitive/lib/2.0", ""));
    }

    @Test
    public void applies_non_declared_pin_as_dependency_management() throws IOException {
        addToMavenRepository("org.mid", "mid", "1.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.mid</groupId>
                    <artifactId>mid</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.transitive</groupId>
                            <artifactId>lib</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                </project>""");
        addToMavenRepository("org.transitive", "lib", "2.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.transitive</groupId>
                    <artifactId>lib</artifactId>
                    <version>2.0</version>
                </project>""");
        Map<String, String> fetched = new LinkedHashMap<>();
        Repository discovery = stubRepository(fetched, Map.of(
                "foo.bar:pom", """
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                            <groupId>org.example</groupId>
                            <artifactId>example-core</artifactId>
                            <version>1.0</version>
                            <dependencies>
                                <dependency>
                                    <groupId>org.mid</groupId>
                                    <artifactId>mid</artifactId>
                                    <version>1.0</version>
                                </dependency>
                            </dependencies>
                        </project>""",
                "lib.module/2.0:pom", """
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                            <groupId>org.transitive</groupId>
                            <artifactId>lib</artifactId>
                            <version>2.0</version>
                        </project>"""));

        SequencedMap<String, String> resolved = new MavenModuleResolver("maven", mavenPomResolver, discovery).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), null, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("lib.module", "2.0")),
                true);

        assertThat(resolved).containsExactly(
                Map.entry("maven/org.example/example-core/1.0", ""),
                Map.entry("maven/org.mid/mid/1.0", ""),
                Map.entry("maven/org.transitive/lib/2.0", ""));
        assertThat(fetched).containsOnlyKeys("foo.bar:pom", "lib.module/2.0:pom");
    }

    private static Repository stubRepository(Map<String, String> fetched, Map<String, String> bodies) {
        return (_, coordinate) -> {
            fetched.put(coordinate, "");
            String body = bodies.get(coordinate);
            if (body == null) {
                return Optional.empty();
            }
            return Optional.of((RepositoryItem) () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        };
    }

    private void addToMavenRepository(String groupId, String artifactId, String version, String pom) throws IOException {
        Files.writeString(Files
                .createDirectories(mavenRepoFolder.resolve(groupId.replace('.', '/') + "/" + artifactId + "/" + version))
                .resolve(artifactId + "-" + version + ".pom"), pom);
    }
}
