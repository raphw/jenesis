package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.SequencedProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RepositoryTest {

    @TempDir
    private Path folder;

    @Test
    public void ofProperties_passes_folder_to_resolver_for_relative_values() throws IOException {
        Path target = folder.resolve("artifact.jar");
        Files.writeString(target, "bytes");
        SequencedProperties identity = new SequencedProperties();
        identity.setProperty("module/foo", "artifact.jar");
        identity.store(folder.resolve("identity.properties"));

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
        SequencedProperties uris = new SequencedProperties();
        uris.setProperty("module/foo", "https://example.test/foo.jar");
        uris.store(folder.resolve("uris.properties"));

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
        Repository repository = Repository.ofUris(Map.of("foo", bare), null, _ -> {});
        assertThat(repository.fetch(Runnable::run, "foo/9.9")).isEmpty();
    }

    @Test
    public void ofUris_with_version_resolver_falls_back_via_supplied_substitution() throws IOException {
        URI bare = URI.create("https://example.test/other/foo.jar");
        Repository repository = Repository.ofUris(Map.of("foo", bare),
                (BiFunction<URI, String, Optional<URI>> & Serializable) (uri, _) -> Optional.of(uri),
                _ -> {});
        Optional<RepositoryItem> item = repository.fetch(Runnable::run, "foo/9.9");
        assertThat(item).isPresent();
    }

    @Test
    public void ofUris_with_version_resolver_returning_empty_misses_instead_of_serving_bare_url() throws IOException {
        URI bare = URI.create("https://example.test/other/foo.jar");
        Repository repository = Repository.ofUris(Map.of("foo", bare),
                (BiFunction<URI, String, Optional<URI>> & Serializable) (_, _) -> Optional.empty(),
                _ -> {});
        assertThat(repository.fetch(Runnable::run, "foo/9.9")).isEmpty();
    }

    @Test
    public void cached_does_not_cache_internal_items_and_preserves_the_flag() throws IOException {
        Path source = Files.writeString(folder.resolve("live.jar"), "live");
        Path cache = Files.createDirectory(folder.resolve("cache"));
        Repository underlying = (_, _) -> Optional.of(RepositoryItem.ofFile(source, true));

        Optional<RepositoryItem> item = underlying.cached(cache).fetch(Runnable::run, "module/foo/1.0");

        assertThat(item).isPresent();
        assertThat(item.get().internal()).isTrue();
        assertThat(item.get().file()).contains(source);
        assertThat(cache.toFile().list()).isEmpty();
    }

    @Test
    public void cached_copies_external_items_into_the_cache() throws IOException {
        Path source = Files.writeString(folder.resolve("remote.jar"), "remote");
        Path cache = Files.createDirectory(folder.resolve("cache"));
        Repository underlying = (_, _) -> Optional.of(RepositoryItem.ofFile(source, false));

        Optional<RepositoryItem> item = underlying.cached(cache).fetch(Runnable::run, "module/foo/1.0");

        assertThat(item).isPresent();
        assertThat(item.get().internal()).isFalse();
        assertThat(item.get().file()).isPresent();
        assertThat(item.get().file().get().getParent()).isEqualTo(cache);
        assertThat(cache.toFile().list()).isNotEmpty();
    }

    @Test
    public void cached_failed_stream_copy_leaves_no_file_at_the_target() throws IOException {
        Path cache = Files.createDirectory(folder.resolve("cache"));
        RepositoryItem failing = () -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("interrupted");
            }
        };
        Repository underlying = (_, _) -> Optional.of(failing);

        assertThatThrownBy(() -> underlying.cached(cache).fetch(Runnable::run, "module/foo/1.0"))
                .isInstanceOf(IOException.class);

        assertThat(cache.toFile().list()).isEmpty();
    }
}
