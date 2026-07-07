package build.jenesis.test;

import module java.base;
import module jdk.httpserver;
import module org.junit.jupiter.api;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.SequencedProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class RepositoryTest {

    @TempDir
    private Path folder;

    @AfterEach
    public void clear() {
        System.clearProperty("jenesis.repository.insecure");
        System.clearProperty("jenesis.repository.retries");
        System.clearProperty("jenesis.repository.backoff");
    }

    private HttpServer serve(IntFunction<Integer> statusOfHit, Map<String, String> headers, AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            int status = statusOfHit.apply(hits.incrementAndGet());
            headers.forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            byte[] body = status == 200 ? "payload".getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    public void open_retries_a_server_error_until_success() throws IOException {
        System.setProperty("jenesis.repository.insecure", "true");
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(hit -> hit < 3 ? 502 : 200, Map.of(), hits);
        try {
            URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/artifact.jar");
            try (InputStream stream = Repository.open(uri, null, new Repository.Retry(2, Duration.ofMillis(1)))) {
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("payload");
            }
            assertThat(hits.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void open_gives_up_after_the_configured_retries() throws IOException {
        System.setProperty("jenesis.repository.insecure", "true");
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(_ -> 502, Map.of(), hits);
        try {
            URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/artifact.jar");
            assertThatThrownBy(() -> Repository.open(uri, null, new Repository.Retry(1, Duration.ofMillis(1))).close())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("502");
            assertThat(hits.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void open_does_not_retry_a_missing_resource() throws IOException {
        System.setProperty("jenesis.repository.insecure", "true");
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(_ -> 404, Map.of(), hits);
        try {
            URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/artifact.jar");
            assertThatThrownBy(() -> Repository.open(uri, null, new Repository.Retry(2, Duration.ofMillis(1))).close())
                    .isInstanceOf(FileNotFoundException.class);
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void open_honors_the_retry_after_header() throws IOException {
        System.setProperty("jenesis.repository.insecure", "true");
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(hit -> hit < 2 ? 429 : 200, Map.of("Retry-After", "0"), hits);
        try {
            URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/artifact.jar");
            try (InputStream stream = Repository.open(uri, null, new Repository.Retry(2, Duration.ofSeconds(30)))) {
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("payload");
            }
            assertThat(hits.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void retry_defaults_read_the_system_properties() {
        System.setProperty("jenesis.repository.retries", "7");
        System.setProperty("jenesis.repository.backoff", "9");
        assertThat(new Repository.Retry()).isEqualTo(new Repository.Retry(7, Duration.ofMillis(9)));
        assertThat(new Repository.Retry().retries(1)).isEqualTo(new Repository.Retry(1, Duration.ofMillis(9)));
        assertThat(new Repository.Retry().backoff(Duration.ofMillis(2))).isEqualTo(new Repository.Retry(7, Duration.ofMillis(2)));
    }

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
        Repository repository = Repository.ofUris(Map.of("foo", bare), null, new Repository.Retry(), _ -> {});
        assertThat(repository.fetch(Runnable::run, "foo/9.9")).isEmpty();
    }

    @Test
    public void ofUris_with_version_resolver_falls_back_via_supplied_substitution() throws IOException {
        URI bare = URI.create("https://example.test/other/foo.jar");
        Repository repository = Repository.ofUris(Map.of("foo", bare),
                (BiFunction<URI, String, Optional<URI>> & Serializable) (uri, _) -> Optional.of(uri),
                new Repository.Retry(),
                _ -> {});
        Optional<RepositoryItem> item = repository.fetch(Runnable::run, "foo/9.9");
        assertThat(item).isPresent();
    }

    @Test
    public void ofUris_with_version_resolver_returning_empty_misses_instead_of_serving_bare_url() throws IOException {
        URI bare = URI.create("https://example.test/other/foo.jar");
        Repository repository = Repository.ofUris(Map.of("foo", bare),
                (BiFunction<URI, String, Optional<URI>> & Serializable) (_, _) -> Optional.empty(),
                new Repository.Retry(),
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
    public void cached_references_local_items_in_place_without_caching() throws IOException {
        Path source = Files.writeString(folder.resolve("local.jar"), "local");
        Path cache = Files.createDirectory(folder.resolve("cache"));
        Repository underlying = (_, _) -> Optional.of(new RepositoryItem() {
            @Override
            public Optional<Path> file() {
                return Optional.of(source);
            }

            @Override
            public boolean local() {
                return true;
            }

            @Override
            public InputStream toInputStream() throws IOException {
                return Files.newInputStream(source);
            }
        });

        Optional<RepositoryItem> item = underlying.cached(cache).fetch(Runnable::run, "module/foo/1.0");

        assertThat(item).isPresent();
        assertThat(item.get().file()).contains(source);
        assertThat(item.get().internal()).isFalse();
        assertThat(item.get().local()).isFalse();
        assertThat(cache.toFile().list()).isEmpty();
    }

    @Test
    public void cached_local_item_skips_inner_cache_but_outer_cache_snapshots_it() throws IOException {
        Path source = Files.writeString(folder.resolve("local.jar"), "local");
        Path inner = Files.createDirectory(folder.resolve("inner"));
        Path outer = Files.createDirectory(folder.resolve("outer"));
        Repository underlying = (_, _) -> Optional.of(new RepositoryItem() {
            @Override
            public Optional<Path> file() {
                return Optional.of(source);
            }

            @Override
            public boolean local() {
                return true;
            }

            @Override
            public InputStream toInputStream() throws IOException {
                return Files.newInputStream(source);
            }
        });

        Optional<RepositoryItem> item = underlying.cached(inner).cached(outer).fetch(Runnable::run, "module/foo/1.0");

        assertThat(item).isPresent();
        assertThat(item.get().file()).isPresent();
        assertThat(item.get().file().get().getParent()).isEqualTo(outer);
        assertThat(inner.toFile().list()).isEmpty();
        assertThat(outer.toFile().list()).isNotEmpty();
    }

    @Test
    public void materialized_snapshots_local_items_into_the_folder() throws IOException {
        Path source = Files.writeString(folder.resolve("local.jar"), "local");
        Path snapshot = Files.createDirectory(folder.resolve("snapshot"));
        Repository underlying = (_, _) -> Optional.of(new RepositoryItem() {
            @Override
            public Optional<Path> file() {
                return Optional.of(source);
            }

            @Override
            public boolean local() {
                return true;
            }

            @Override
            public InputStream toInputStream() throws IOException {
                return Files.newInputStream(source);
            }
        });

        Optional<RepositoryItem> item = underlying.materialized(snapshot).fetch(Runnable::run, "module/foo/1.0");

        assertThat(item).isPresent();
        assertThat(item.get().file()).isPresent();
        assertThat(item.get().file().get().getParent()).isEqualTo(snapshot);
        assertThat(item.get().file().get()).hasContent("local");
        assertThat(snapshot.toFile().list()).isNotEmpty();
    }

    @Test
    public void materialized_keeps_internal_items_in_place_and_preserves_the_flag() throws IOException {
        Path source = Files.writeString(folder.resolve("live.jar"), "live");
        Path snapshot = Files.createDirectory(folder.resolve("snapshot"));
        Repository underlying = (_, _) -> Optional.of(RepositoryItem.ofFile(source, true));

        Optional<RepositoryItem> item = underlying.materialized(snapshot).fetch(Runnable::run, "module/foo/1.0");

        assertThat(item).isPresent();
        assertThat(item.get().internal()).isTrue();
        assertThat(item.get().file()).contains(source);
        assertThat(snapshot.toFile().list()).isEmpty();
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
