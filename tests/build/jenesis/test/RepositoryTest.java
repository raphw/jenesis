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
}
