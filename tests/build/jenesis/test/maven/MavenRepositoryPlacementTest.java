package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.maven.MavenRepositoryPlacement;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenRepositoryPlacementTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, source, target;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        source = Files.createDirectory(root.resolve("source"));
        target = Files.createDirectory(root.resolve("target"));
    }

    @Test
    public void routes_jar_and_pom_under_groupId_artifactId_version() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-x"));
        Files.writeString(module.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>foo</artifactId>
                    <version>1.2.3</version>
                </project>
                """);
        Files.writeString(module.resolve("metadata.properties"),
                "project=com.example\nartifact=foo\nversion=1.2.3\n");
        Files.writeString(module.resolve("classes.jar"), "jar bytes");

        BuildStep export = MavenRepositoryPlacement.toRepository(target);
        BuildStepResult result = export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("module-x/classes.jar"), ChecksumStatus.ADDED,
                                        Path.of("module-x/pom.xml"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).hasContent("jar bytes");
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.pom")).exists();
    }

    @Test
    public void routes_sources_and_javadoc_jars_with_classifier_suffix() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-x"));
        Files.writeString(module.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>foo</artifactId>
                    <version>1.2.3</version>
                </project>
                """);
        Files.writeString(module.resolve("metadata.properties"),
                "project=com.example\nartifact=foo\nversion=1.2.3\n");
        Files.writeString(module.resolve("sources.jar"), "sources bytes");
        Files.writeString(module.resolve("javadoc.jar"), "javadoc bytes");

        BuildStepResult result = MavenRepositoryPlacement.toRepository(target).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("module-x/sources.jar"), ChecksumStatus.ADDED,
                                        Path.of("module-x/javadoc.jar"), ChecksumStatus.ADDED,
                                        Path.of("module-x/pom.xml"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3-sources.jar")).hasContent("sources bytes");
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3-javadoc.jar")).hasContent("javadoc bytes");
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.pom")).exists();
    }

    @Test
    public void writes_artifact_metadata_with_release_and_versions() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-x"));
        Files.writeString(module.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>foo</artifactId>
                    <version>1.2.3</version>
                </project>
                """);
        Files.writeString(module.resolve("metadata.properties"),
                "project=com.example\nartifact=foo\nversion=1.2.3\n");
        Files.writeString(module.resolve("classes.jar"), "jar bytes");

        MavenRepositoryPlacement.toRepository(target).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("module-x/classes.jar"), ChecksumStatus.ADDED,
                                        Path.of("module-x/pom.xml"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();

        Path metadata = target.resolve("com/example/foo/maven-metadata-local.xml");
        assertThat(metadata).exists();
        String content = Files.readString(metadata);
        assertThat(content).contains("<groupId>com.example</groupId>");
        assertThat(content).contains("<artifactId>foo</artifactId>");
        assertThat(content).contains("<release>1.2.3</release>");
        assertThat(content).contains("<version>1.2.3</version>");
        assertThat(content).containsPattern("<lastUpdated>\\d{14}</lastUpdated>");
    }

    @Test
    public void writes_remote_repositories_marker() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-x"));
        Files.writeString(module.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>foo</artifactId>
                    <version>1.2.3</version>
                </project>
                """);
        Files.writeString(module.resolve("metadata.properties"),
                "project=com.example\nartifact=foo\nversion=1.2.3\n");
        Files.writeString(module.resolve("classes.jar"), "jar bytes");

        MavenRepositoryPlacement.toRepository(target).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("module-x/classes.jar"), ChecksumStatus.ADDED,
                                        Path.of("module-x/pom.xml"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();

        Path marker = target.resolve("com/example/foo/1.2.3/_remote.repositories");
        assertThat(marker).exists();
        String content = Files.readString(marker);
        assertThat(content).contains("foo-1.2.3.jar>=");
        assertThat(content).contains("foo-1.2.3.pom>=");
    }

    @Test
    public void writes_snapshot_metadata_for_snapshot_versions() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-x"));
        Files.writeString(module.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>foo</artifactId>
                    <version>2.0-SNAPSHOT</version>
                </project>
                """);
        Files.writeString(module.resolve("metadata.properties"),
                "project=com.example\nartifact=foo\nversion=2.0-SNAPSHOT\n");
        Files.writeString(module.resolve("classes.jar"), "jar bytes");

        MavenRepositoryPlacement.toRepository(target).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("module-x/classes.jar"), ChecksumStatus.ADDED,
                                        Path.of("module-x/pom.xml"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();

        Path snapshot = target.resolve("com/example/foo/2.0-SNAPSHOT/maven-metadata-local.xml");
        assertThat(snapshot).exists();
        String content = Files.readString(snapshot);
        assertThat(content).contains("<localCopy>true</localCopy>");
        assertThat(content).contains("<extension>jar</extension>");
        assertThat(content).contains("<extension>pom</extension>");
        assertThat(content).contains("<value>2.0-SNAPSHOT</value>");
        assertThat(content).contains("<version>2.0-SNAPSHOT</version>");

        Path artifact = target.resolve("com/example/foo/maven-metadata-local.xml");
        assertThat(artifact).exists();
        String artifactContent = Files.readString(artifact);
        assertThat(artifactContent).contains("<version>2.0-SNAPSHOT</version>");
        assertThat(artifactContent).doesNotContain("<release>");
    }

    @Test
    public void aggregates_versions_for_same_artifact() throws IOException {
        for (String version : List.of("1.0", "1.1", "2.0-SNAPSHOT", "1.5")) {
            Path module = Files.createDirectory(source.resolve("module-" + version));
            Files.writeString(module.resolve("pom.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>multi</artifactId>
                        <version>%s</version>
                    </project>
                    """.formatted(version));
            Files.writeString(module.resolve("metadata.properties"),
                    "project=com.example\nartifact=multi\nversion=" + version + "\n");
            Files.writeString(module.resolve("classes.jar"), "jar bytes");
        }

        Map<Path, ChecksumStatus> sources = new LinkedHashMap<>();
        for (String version : List.of("1.0", "1.1", "2.0-SNAPSHOT", "1.5")) {
            sources.put(Path.of("module-" + version + "/classes.jar"), ChecksumStatus.ADDED);
            sources.put(Path.of("module-" + version + "/pom.xml"), ChecksumStatus.ADDED);
        }
        MavenRepositoryPlacement.toRepository(target).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(source, sources))))
                .toCompletableFuture()
                .join();

        Path metadata = target.resolve("com/example/multi/maven-metadata-local.xml");
        String content = Files.readString(metadata);
        int positionOneZero = content.indexOf("<version>1.0</version>");
        int positionOneOne = content.indexOf("<version>1.1</version>");
        int positionOneFive = content.indexOf("<version>1.5</version>");
        int positionTwoZero = content.indexOf("<version>2.0-SNAPSHOT</version>");
        assertThat(positionOneZero).isLessThan(positionOneOne);
        assertThat(positionOneOne).isLessThan(positionOneFive);
        assertThat(positionOneFive).isLessThan(positionTwoZero);
        assertThat(content).contains("<release>1.5</release>");
    }

    @Test
    public void createMavenLocalMetadata_is_idempotent_on_re_export() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-x"));
        Files.writeString(module.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>foo</artifactId>
                    <version>1.2.3</version>
                </project>
                """);
        Files.writeString(module.resolve("metadata.properties"),
                "project=com.example\nartifact=foo\nversion=1.2.3\n");
        Files.writeString(module.resolve("classes.jar"), "jar bytes");
        LinkedHashMap<String, BuildStepArgument> arguments = new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                source,
                Map.of(Path.of("module-x/classes.jar"), ChecksumStatus.ADDED,
                        Path.of("module-x/pom.xml"), ChecksumStatus.ADDED))));
        BuildStep step = MavenRepositoryPlacement.toRepository(target);
        step.apply(Runnable::run, new BuildStepContext(previous, next, supplement), arguments).toCompletableFuture().join();
        step.apply(Runnable::run, new BuildStepContext(previous, next, supplement), arguments).toCompletableFuture().join();

        Path metadata = target.resolve("com/example/foo/maven-metadata-local.xml");
        String content = Files.readString(metadata);
        assertThat(content.split("<version>1\\.2\\.3</version>")).hasSize(2); // exactly one occurrence
        assertThat(content).contains("<release>1.2.3</release>");
    }

    @Test
    public void skips_files_without_sibling_pom() throws IOException {
        Files.writeString(source.resolve("classes.jar"), "ignored");

        BuildStep export = MavenRepositoryPlacement.toRepository(target);
        export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("classes.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        try (Stream<Path> contents = Files.list(target)) {
            assertThat(contents).isEmpty();
        }
    }
}
