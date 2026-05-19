package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenDefaultRepositoryTest {

    @TempDir
    private Path repository, local, result;

    @Test
    public void can_fetch_dependency() throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        Path dependency = result.resolve("dependency.jar");
        try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(), null, Map.of(), _ -> {}).fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null).orElseThrow().toInputStream()) {
            Files.copy(inputStream, dependency);
        }
        assertThat(dependency).content().isEqualTo("foo");
    }

    @Test
    public void can_fetch_and_cache_dependency() throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        Path dependency = result.resolve("dependency.jar");
        try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(), local, Map.of(), _ -> {}).fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null).orElseThrow().toInputStream()) {
            Files.copy(inputStream, dependency);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).content().isEqualTo("foo");
    }

    @Test
    public void can_fetch_and_validate_dependency() throws IOException, NoSuchAlgorithmException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest("foo".getBytes(StandardCharsets.UTF_8));
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            writer.write(HexFormat.of().formatHex(hash));
        }
        Path dependency = result.resolve("dependency.jar");
        try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(),
                local,
                Map.of("MD5", repository.toUri()), _ -> {}).fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null).orElseThrow().toInputStream()) {
            Files.copy(inputStream, dependency);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).content().isEqualTo(HexFormat.of().formatHex(hash));
    }

    @Test
    public void can_fetch_and_fail_validate_dependency() throws IOException, NoSuchAlgorithmException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            writer.write(HexFormat.of().formatHex(digest.digest("bar".getBytes(StandardCharsets.UTF_8))));
        }
        MavenRepository repository = new MavenDefaultRepository(this.repository.toUri(),
                local,
                Map.of("MD5", this.repository.toUri()), _ -> {});
        assertThatThrownBy(() -> repository.fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null)).isInstanceOf(IllegalStateException.class);
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).doesNotExist();
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).doesNotExist();
    }

    @Test
    public void can_validate_cached_dependency() throws IOException, NoSuchAlgorithmException {
        Files.writeString(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest("foo".getBytes(StandardCharsets.UTF_8));
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            writer.write(HexFormat.of().formatHex(hash));
        }
        Path dependency = result.resolve("dependency.jar");
        try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(),
                local,
                Map.of("MD5", repository.toUri()), _ -> {}).fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null).orElseThrow().toInputStream()) {
            Files.copy(inputStream, dependency);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).content().isEqualTo(HexFormat.of().formatHex(hash));
    }

    @Test
    public void can_validate_cached_dependency_with_cached_hash() throws IOException, NoSuchAlgorithmException {
        Files.writeString(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest("foo".getBytes(StandardCharsets.UTF_8));
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            writer.write(HexFormat.of().formatHex(hash));
        }
        Path dependency = result.resolve("dependency.jar");
        try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(),
                local,
                Map.of("MD5", repository.toUri()), _ -> {}).fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null).orElseThrow().toInputStream()) {
            Files.copy(inputStream, dependency);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).content().isEqualTo(HexFormat.of().formatHex(hash));
    }

    @Test
    public void can_fail_validate_cached_dependency() throws IOException, NoSuchAlgorithmException {
        Files.writeString(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            writer.write(HexFormat.of().formatHex(digest.digest("bar".getBytes(StandardCharsets.UTF_8))));
        }
        MavenRepository repository = new MavenDefaultRepository(this.repository.toUri(),
                local,
                Map.of("MD5", this.repository.toUri()), _ -> {});
        assertThat(repository.fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null)).isEmpty();
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).doesNotExist();
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.md5")).doesNotExist();
    }

    @Test
    public void can_validate_dependency_when_stream_not_fully_drained() throws IOException, NoSuchAlgorithmException {
        String content = "foo\n";
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), content);
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar.md5"))) {
            writer.write(HexFormat.of().formatHex(hash));
        }
        try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(),
                null,
                Map.of("MD5", repository.toUri()), _ -> {}).fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null).orElseThrow().toInputStream()) {
            int read = inputStream.read();
            assertThat(read).isEqualTo('f');
        }
    }

    @Test
    public void versionResolver_swaps_version_in_path_and_filename() {
        URI substituted = MavenDefaultRepository.versionResolver().apply(
                URI.create("https://repo.maven.apache.org/maven2/org/assertj/assertj-core/4.0.0-M1/assertj-core-4.0.0-M1.jar"),
                "3.27.0").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/org/assertj/assertj-core/3.27.0/assertj-core-3.27.0.jar");
    }

    @Test
    public void versionResolver_handles_hyphenated_artifact_ids() {
        URI substituted = MavenDefaultRepository.versionResolver().apply(
                URI.create("https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/5.13.0-M3/junit-jupiter-5.13.0-M3.jar"),
                "5.11.3").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/5.11.3/junit-jupiter-5.11.3.jar");
    }

    @Test
    public void versionResolver_preserves_classifier() {
        URI substituted = MavenDefaultRepository.versionResolver().apply(
                URI.create("https://repo.maven.apache.org/maven2/org/assertj/assertj-core/4.0.0-M1/assertj-core-4.0.0-M1-sources.jar"),
                "3.27.0").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/org/assertj/assertj-core/3.27.0/assertj-core-3.27.0-sources.jar");
    }

    @Test
    public void versionResolver_preserves_non_jar_extension() {
        URI substituted = MavenDefaultRepository.versionResolver().apply(
                URI.create("https://repo.maven.apache.org/maven2/foo/bar/1.0/bar-1.0.pom"),
                "2.0").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/foo/bar/1.0/bar-1.0.pom".replace("1.0", "2.0"));
    }

    @Test
    public void versionResolver_handles_versions_with_dashes() {
        URI substituted = MavenDefaultRepository.versionResolver().apply(
                URI.create("https://example.test/g/a/1.0.0-SNAPSHOT/a-1.0.0-SNAPSHOT.jar"),
                "2.0.0-M5").orElseThrow();
        assertThat(substituted).hasToString(
                "https://example.test/g/a/2.0.0-M5/a-2.0.0-M5.jar");
    }

    @Test
    public void versionResolver_rejects_non_maven_layout() {
        assertThat(MavenDefaultRepository.versionResolver().apply(
                URI.create("https://example.test/some/random/path/foo.jar"),
                "9.9")).isEmpty();
    }

    @Test
    public void versionResolver_rejects_url_without_two_trailing_segments() {
        assertThat(MavenDefaultRepository.versionResolver().apply(
                URI.create("https://example.test/foo.jar"),
                "9.9")).isEmpty();
    }

    @Test
    public void can_fetch_metadata() throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact"))
                .resolve("maven-metadata.xml"), "foo");
        Path dependency = result.resolve("dependency.jar");
        try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(), null, Map.of(), _ -> {}).fetchMetadata(Runnable::run,
                "group",
                "artifact",
                null).orElseThrow().toInputStream()) {
            Files.copy(inputStream, dependency);
        }
        assertThat(dependency).content().isEqualTo("foo");
    }
}
