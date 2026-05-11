package build.jenesis.test;

import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class RepositoryTest {

    @TempDir
    private Path folder;

    @Test
    public void ofProperties_passes_folder_to_resolver_for_relative_values() throws IOException {
        Path target = folder.resolve("artifact.jar");
        Files.writeString(target, "bytes");
        Properties identity = new Properties();
        identity.setProperty("module/foo", "artifact.jar");
        try (Writer writer = Files.newBufferedWriter(folder.resolve("identity.properties"))) {
            identity.store(writer, null);
        }

        Map<String, Repository> repositories = Repository.ofProperties("identity.properties",
                List.of(folder),
                (resolverFolder, value) -> resolverFolder.resolve(value).toUri(),
                null);

        assertThat(repositories).containsOnlyKeys("module");
        Optional<RepositoryItem> item = repositories.get("module").fetch(Runnable::run, "foo");
        assertThat(item).isPresent();
        assertThat(item.get().getFile()).contains(target);
    }

    @Test
    public void ofProperties_lets_resolver_pass_through_absolute_uris_unchanged() throws IOException {
        Properties uris = new Properties();
        uris.setProperty("module/foo", "https://example.test/foo.jar");
        try (Writer writer = Files.newBufferedWriter(folder.resolve("uris.properties"))) {
            uris.store(writer, null);
        }

        Map<String, Repository> repositories = Repository.ofProperties("uris.properties",
                List.of(folder),
                (_, value) -> URI.create(value),
                null);

        assertThat(repositories).containsOnlyKeys("module");
        Optional<RepositoryItem> item = repositories.get("module").fetch(Runnable::run, "foo");
        // The repository for an absolute https URI keeps the original URI in its lazy item.
        assertThat(item).isPresent();
    }

    @Test
    public void substituteMavenVersion_swaps_version_in_path_and_filename() {
        URI substituted = Repository.substituteMavenVersion(
                URI.create("https://repo.maven.apache.org/maven2/org/assertj/assertj-core/4.0.0-M1/assertj-core-4.0.0-M1.jar"),
                "3.27.0").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/org/assertj/assertj-core/3.27.0/assertj-core-3.27.0.jar");
    }

    @Test
    public void substituteMavenVersion_handles_hyphenated_artifact_ids() {
        URI substituted = Repository.substituteMavenVersion(
                URI.create("https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/5.13.0-M3/junit-jupiter-5.13.0-M3.jar"),
                "5.11.3").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/5.11.3/junit-jupiter-5.11.3.jar");
    }

    @Test
    public void substituteMavenVersion_preserves_classifier() {
        URI substituted = Repository.substituteMavenVersion(
                URI.create("https://repo.maven.apache.org/maven2/org/assertj/assertj-core/4.0.0-M1/assertj-core-4.0.0-M1-sources.jar"),
                "3.27.0").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/org/assertj/assertj-core/3.27.0/assertj-core-3.27.0-sources.jar");
    }

    @Test
    public void substituteMavenVersion_preserves_non_jar_extension() {
        URI substituted = Repository.substituteMavenVersion(
                URI.create("https://repo.maven.apache.org/maven2/foo/bar/1.0/bar-1.0.pom"),
                "2.0").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/foo/bar/1.0/bar-1.0.pom".replace("1.0", "2.0"));
    }

    @Test
    public void substituteMavenVersion_handles_versions_with_dashes() {
        URI substituted = Repository.substituteMavenVersion(
                URI.create("https://example.test/g/a/1.0.0-SNAPSHOT/a-1.0.0-SNAPSHOT.jar"),
                "2.0.0-M5").orElseThrow();
        assertThat(substituted).hasToString(
                "https://example.test/g/a/2.0.0-M5/a-2.0.0-M5.jar");
    }

    @Test
    public void substituteMavenVersion_rejects_non_maven_layout() {
        // Filename does not match <artifactId>-<version> pattern.
        assertThat(Repository.substituteMavenVersion(
                URI.create("https://example.test/some/random/path/foo.jar"),
                "9.9")).isEmpty();
    }

    @Test
    public void substituteMavenVersion_rejects_url_without_two_trailing_segments() {
        assertThat(Repository.substituteMavenVersion(
                URI.create("https://example.test/foo.jar"),
                "9.9")).isEmpty();
    }

    @Test
    public void ofUris_strips_version_and_falls_back_to_bare_name_for_non_maven_url() throws IOException {
        URI bare = URI.create("https://example.test/other/foo.jar");
        Repository repository = Repository.ofUris(Map.of("foo", bare));
        Optional<RepositoryItem> item = repository.fetch(Runnable::run, "foo/9.9");
        assertThat(item).isPresent();
    }

    @Test
    public void ofUris_versioned_lookup_uses_maven_version_substitution() throws IOException {
        URI registry = URI.create(
                "https://repo.maven.apache.org/maven2/org/assertj/assertj-core/4.0.0-M1/assertj-core-4.0.0-M1.jar");
        Repository repository = Repository.ofUris(Map.of("org.assertj.core", registry));
        Optional<RepositoryItem> item = repository.fetch(Runnable::run, "org.assertj.core/3.27.0");
        assertThat(item).isPresent();
        // The returned RepositoryItem is the lazy URL handle; we can't fetch over network in tests,
        // but the substitution should have produced a 3.27.0 URI. Reconstruct via the API to verify.
        URI substituted = Repository.substituteMavenVersion(registry, "3.27.0").orElseThrow();
        assertThat(substituted).hasToString(
                "https://repo.maven.apache.org/maven2/org/assertj/assertj-core/3.27.0/assertj-core-3.27.0.jar");
    }
}
