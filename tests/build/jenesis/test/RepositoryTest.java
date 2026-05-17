package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

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
        assertThat(item.get().file()).contains(target);
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
        assertThat(item).isPresent();
    }

    @Test
    public void ofUris_without_version_resolver_does_not_attempt_fallback() throws IOException {
        URI bare = URI.create("https://example.test/other/foo.jar");
        Repository repository = Repository.ofUris(Map.of("foo", bare));
        assertThat(repository.fetch(Runnable::run, "foo/9.9")).isEmpty();
    }

    @Test
    public void ofUris_with_version_resolver_falls_back_via_supplied_substitution() throws IOException {
        URI bare = URI.create("https://example.test/other/foo.jar");
        Repository repository = Repository.ofUris(Map.of("foo", bare),
                (BiFunction<URI, String, Optional<URI>> & Serializable) (uri, _) -> Optional.of(uri));
        Optional<RepositoryItem> item = repository.fetch(Runnable::run, "foo/9.9");
        assertThat(item).isPresent();
    }
}
