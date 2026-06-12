package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.maven.MavenRepositoryExport;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenRepositoryExportTest {

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
    public void exports_jar_and_pom_at_maven_repository_path() throws IOException {
        stageArtifact("com.example", "foo", "1.2.3", "jar bytes");

        BuildStepResult result = run();

        assertThat(result.next()).isTrue();
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).hasContent("jar bytes");
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.pom")).exists();
    }

    @Test
    public void exports_sources_and_javadoc_jars_alongside_main() throws IOException {
        Path versionDir = stageArtifact("com.example", "foo", "1.2.3", "jar bytes");
        Files.writeString(versionDir.resolve("foo-1.2.3-sources.jar"), "sources bytes");
        Files.writeString(versionDir.resolve("foo-1.2.3-javadoc.jar"), "javadoc bytes");

        run();

        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).hasContent("jar bytes");
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3-sources.jar")).hasContent("sources bytes");
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3-javadoc.jar")).hasContent("javadoc bytes");
    }

    @Test
    public void writes_snapshot_metadata_for_snapshot_version() throws IOException {
        Path versionDir = stageArtifact("com.example", "foo", "2.0-SNAPSHOT", "jar bytes");
        Files.writeString(versionDir.resolve("foo-2.0-SNAPSHOT-sources.jar"), "sources bytes");

        run();

        Path snapshot = target.resolve("com/example/foo/2.0-SNAPSHOT/maven-metadata-local.xml");
        assertThat(snapshot).exists();
        String content = Files.readString(snapshot);
        assertThat(content).contains("modelVersion=\"1.1.0\"");
        assertThat(content).contains("<localCopy>true</localCopy>");
        assertThat(content).contains("<value>2.0-SNAPSHOT</value>");
        assertThat(content).contains("<extension>jar</extension>");
        assertThat(content).contains("<extension>pom</extension>");
        assertThat(content).contains("<classifier>sources</classifier>");
    }

    @Test
    public void omits_snapshot_metadata_for_release_version() throws IOException {
        stageArtifact("com.example", "foo", "1.0.0", "jar bytes");

        run();

        assertThat(target.resolve("com/example/foo/1.0.0/maven-metadata-local.xml")).doesNotExist();
    }

    @Test
    public void writes_remote_repositories_marker_listing_artifact_files() throws IOException {
        Path versionDir = stageArtifact("com.example", "foo", "1.2.3", "jar bytes");
        Files.writeString(versionDir.resolve("foo-1.2.3-sources.jar"), "sources bytes");

        run();

        Path marker = target.resolve("com/example/foo/1.2.3/_remote.repositories");
        assertThat(marker).exists();
        String content = Files.readString(marker);
        assertThat(content).contains("foo-1.2.3.jar>=");
        assertThat(content).contains("foo-1.2.3-sources.jar>=");
        assertThat(content).contains("foo-1.2.3.pom>=");
        assertThat(content).doesNotContain(".xml>=");
    }

    @Test
    public void aggregates_versions_in_artifact_metadata() throws IOException {
        stageArtifact("com.example", "multi", "1.0", "v10");
        stageArtifact("com.example", "multi", "1.1", "v11");
        stageArtifact("com.example", "multi", "2.0-SNAPSHOT", "v20snap");
        stageArtifact("com.example", "multi", "1.5", "v15");

        run();

        Path metadata = target.resolve("com/example/multi/maven-metadata-local.xml");
        assertThat(metadata).exists();
        String content = Files.readString(metadata);
        int positionOneZero = content.indexOf("<version>1.0</version>");
        int positionOneOne = content.indexOf("<version>1.1</version>");
        int positionOneFive = content.indexOf("<version>1.5</version>");
        int positionTwoZero = content.indexOf("<version>2.0-SNAPSHOT</version>");
        assertThat(positionOneZero).isPositive().isLessThan(positionOneOne);
        assertThat(positionOneOne).isLessThan(positionOneFive);
        assertThat(positionOneFive).isLessThan(positionTwoZero);
        assertThat(content).contains("<release>1.5</release>");
        assertThat(content).containsPattern("<lastUpdated>\\d{14}</lastUpdated>");
    }

    @Test
    public void second_export_preserves_previously_exported_versions() throws IOException {
        Path firstVersionDir = stageArtifact("com.example", "foo", "1.0", "v10");
        run();

        try (Stream<Path> staged = Files.list(firstVersionDir)) {
            for (Path file : staged.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(firstVersionDir);
        stageArtifact("com.example", "foo", "2.0", "v20");
        run();

        Path metadata = target.resolve("com/example/foo/maven-metadata-local.xml");
        String content = Files.readString(metadata);
        assertThat(content).contains("<version>1.0</version>");
        assertThat(content).contains("<version>2.0</version>");
        assertThat(content).contains("<release>2.0</release>");
        assertThat(content).contains("<latest>2.0</latest>");
    }

    @Test
    public void leaves_unrelated_files_in_target_untouched() throws IOException {
        Path otherArtifact = Files.createDirectories(target.resolve("org/other/lib/1.0"));
        Files.writeString(otherArtifact.resolve("lib-1.0.jar"), "untouched");

        stageArtifact("com.example", "foo", "1.2.3", "jar bytes");
        run();

        assertThat(otherArtifact.resolve("lib-1.0.jar")).hasContent("untouched");
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).hasContent("jar bytes");
    }

    @Test
    public void skips_directories_without_a_pom() throws IOException {
        Path moduleDir = Files.createDirectories(source.resolve("orphan"));
        Files.writeString(moduleDir.resolve("classes.jar"), "ignored");

        BuildStepResult result = run();

        assertThat(result.next()).isTrue();
        try (Stream<Path> contents = Files.list(target)) {
            assertThat(contents).isEmpty();
        }
    }

    @Test
    public void copies_pom_into_local_repository() throws IOException {
        stageArtifact("com.example", "foo", "1.2.3", "jar bytes");

        run();

        String pom = Files.readString(target.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        assertThat(pom).contains("<groupId>com.example</groupId>");
        assertThat(pom).contains("<artifactId>foo</artifactId>");
        assertThat(pom).contains("<version>1.2.3</version>");
    }

    @Test
    public void runs_unconditionally_even_when_arguments_unchanged() {
        BuildStep export = new MavenRepositoryExport(target);
        assertThat(export.shouldRun(new LinkedHashMap<>())).isTrue();
    }

    private Path stageArtifact(String groupId, String artifactId, String version, String jarBytes) throws IOException {
        Path versionDir = Files.createDirectories(source
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version));
        String prefix = artifactId + "-" + version;
        Files.writeString(versionDir.resolve(prefix + ".jar"), jarBytes);
        Files.writeString(versionDir.resolve(prefix + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version));
        return versionDir;
    }

    private BuildStepResult run() throws IOException {
        return new MavenRepositoryExport(target).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("."), Checksum.ADDED)))))
                .toCompletableFuture()
                .join();
    }
}
