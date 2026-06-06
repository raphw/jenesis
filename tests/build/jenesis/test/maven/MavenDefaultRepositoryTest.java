package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenDefaultRepositoryTest {

    @TempDir
    private Path repository, local, result;

    @Test
    public void cached_repository_prepended_with_overlay_resolves_sibling_without_caching() throws IOException {
        Path sibling = local.resolve("sibling.jar");
        Files.writeString(sibling, "sibling-content");
        Repository overlay = (_, coordinate) -> coordinate.equals("group/artifact/1")
                ? Optional.of(RepositoryItem.ofFile(sibling))
                : Optional.empty();
        int[] remoteCalls = {0};
        MavenRepository remote = (_, _, _, _, _, _, _) -> {
            remoteCalls[0]++;
            return Optional.empty();
        };
        Path cache = Files.createDirectory(result.resolve("cache"));
        MavenRepository merged = remote.cached(cache).prepend(overlay);

        Optional<RepositoryItem> first = merged.fetch(Runnable::run, "group", "artifact", "1", "jar", null, null);
        assertThat(first).isPresent();
        assertThat(first.get().file()).contains(sibling);
        assertThat(remoteCalls[0]).isZero();
        try (Stream<Path> stream = Files.list(cache)) {
            assertThat(stream).as("sibling must not enter the upstream cache").isEmpty();
        }

        Files.writeString(sibling, "updated-content");
        Optional<RepositoryItem> second = merged.fetch(Runnable::run, "group", "artifact", "1", "jar", null, null);
        assertThat(second).isPresent();
        assertThat(Files.readString(second.get().file().orElseThrow())).isEqualTo("updated-content");
        assertThat(remoteCalls[0]).isZero();
    }

    @Test
    public void cached_repository_prepended_with_overlay_falls_through_to_remote_on_overlay_miss() throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "remote-content");
        Repository emptyOverlay = (_, _) -> Optional.empty();
        Path cache = Files.createDirectory(result.resolve("cache"));
        MavenRepository merged = new MavenDefaultRepository(repository.toUri(), null, Map.of(), _ -> {})
                .cached(cache)
                .prepend(emptyOverlay);

        Optional<RepositoryItem> first = merged.fetch(Runnable::run, "group", "artifact", "1", "jar", null, null);
        assertThat(first).isPresent();
        try (InputStream stream = first.get().toInputStream()) {
            assertThat(new String(stream.readAllBytes())).isEqualTo("remote-content");
        }
        try (Stream<Path> stream = Files.list(cache)) {
            assertThat(stream).as("cache must populate for upstream fetches").isNotEmpty();
        }
    }

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
    public void fetches_without_caching_when_local_repository_is_read_only() throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        local.toFile().setWritable(false, false);
        try {
            Assumptions.assumeFalse(Files.isWritable(local), "read-only enforcement requires a non-root user");
            Path dependency = result.resolve("dependency.jar");
            try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(), local, Map.of(), _ -> {})
                    .fetch(Runnable::run, "group", "artifact", "1", "jar", null, null).orElseThrow().toInputStream()) {
                Files.copy(inputStream, dependency);
            }
            assertThat(dependency).content().isEqualTo("foo");
            assertThat(local.resolve("group/artifact/1/artifact-1.jar")).doesNotExist();
        } finally {
            local.toFile().setWritable(true, false);
        }
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
    public void rejects_coordinate_escaping_local_root() {
        MavenRepository repository = new MavenDefaultRepository(this.repository.toUri(), local, Map.of(), _ -> {});
        assertThatThrownBy(() -> repository.fetch(Runnable::run,
                "..",
                "artifact",
                "1",
                "jar",
                null,
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escapes the local repository root");
    }

    @Test
    public void fails_closed_when_validation_requested_but_sidecar_missing() throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        MavenRepository repository = new MavenDefaultRepository(this.repository.toUri(),
                local,
                Map.of("MD5", this.repository.toUri()), _ -> {});
        assertThatThrownBy(() -> repository.fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No checksum sidecar available");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar")).doesNotExist();
    }

    @Test
    public void fails_closed_when_cached_artifact_has_no_sidecar() throws IOException {
        Files.writeString(Files
                .createDirectories(local.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        MavenRepository repository = new MavenDefaultRepository(this.repository.toUri(),
                local,
                Map.of("MD5", this.repository.toUri()), _ -> {});
        assertThatThrownBy(() -> repository.fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No checksum sidecar available");
    }

    @Test
    public void validates_with_weaker_sidecar_when_stronger_absent() throws IOException, NoSuchAlgorithmException {
        Files.writeString(Files
                .createDirectories(repository.resolve("group/artifact/1"))
                .resolve("artifact-1.jar"), "foo");
        byte[] hash = MessageDigest.getInstance("SHA-1").digest("foo".getBytes(StandardCharsets.UTF_8));
        try (Writer writer = Files.newBufferedWriter(repository
                .resolve("group/artifact/1")
                .resolve("artifact-1.jar.sha1"))) {
            writer.write(HexFormat.of().formatHex(hash));
        }
        Map<String, URI> validations = new LinkedHashMap<>();
        validations.put("SHA256", repository.toUri());
        validations.put("SHA1", repository.toUri());
        Path dependency = result.resolve("dependency.jar");
        try (InputStream inputStream = new MavenDefaultRepository(repository.toUri(),
                local,
                validations, _ -> {}).fetch(Runnable::run,
                "group",
                "artifact",
                "1",
                "jar",
                null,
                null).orElseThrow().toInputStream()) {
            Files.copy(inputStream, dependency);
        }
        assertThat(dependency).content().isEqualTo("foo");
        assertThat(local.resolve("group/artifact/1/artifact-1.jar.sha1"))
                .content().isEqualTo(HexFormat.of().formatHex(hash));
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
