package build.jenesis.test;

import module java.base;
import module jdk.httpserver;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutorHttpCache;
import build.jenesis.BuildStepResult;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildExecutorHttpCacheTest {

    @TempDir
    private Path output, target;
    private HttpServer server;
    private URI uri;
    private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            keys.add(String.valueOf(exchange.getRequestHeaders().getFirst(BuildExecutorHttpCache.HEADER)));
            String identifier = exchange.getRequestURI().getPath();
            try {
                switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        byte[] blob = blobs.get(identifier);
                        if (blob == null) {
                            exchange.sendResponseHeaders(404, -1);
                        } else {
                            exchange.sendResponseHeaders(200, blob.length);
                            try (OutputStream out = exchange.getResponseBody()) {
                                out.write(blob);
                            }
                        }
                    }
                    case "PUT" -> {
                        try (InputStream in = exchange.getRequestBody()) {
                            blobs.put(identifier, in.readAllBytes());
                        }
                        exchange.sendResponseHeaders(201, -1);
                    }
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        uri = URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void stores_and_fetches_round_trip() throws IOException {
        BuildExecutorHttpCache cache = new BuildExecutorHttpCache(uri, "team-alpha");
        byte[] step = {1};
        SequencedMap<String, Map<Path, byte[]>> in = inputs("source", "file", new byte[]{9});
        Files.writeString(output.resolve("file"), "result");
        Files.createDirectory(output.resolve("nested"));
        Files.writeString(output.resolve("nested").resolve("inner"), "deep");
        cache.store(Runnable::run, "step", step, in, output);
        Optional<BuildStepResult> result = cache.fetch(Runnable::run, "step", step, in, target);
        assertThat(result).isPresent();
        assertThat(target.resolve("file")).content().isEqualTo("result");
        assertThat(target.resolve("nested").resolve("inner")).content().isEqualTo("deep");
        assertThat(keys).contains("team-alpha");
    }

    @Test
    public void fetch_returns_empty_on_miss() throws IOException {
        BuildExecutorHttpCache cache = new BuildExecutorHttpCache(uri, "team-alpha");
        Optional<BuildStepResult> result = cache.fetch(
                Runnable::run,
                "step",
                new byte[]{1},
                inputs("source", "file", new byte[]{9}),
                target);
        assertThat(result).isEmpty();
    }

    @Test
    public void write_disabled_does_not_store() throws IOException {
        byte[] step = {1};
        SequencedMap<String, Map<Path, byte[]>> in = inputs("source", "file", new byte[]{9});
        Files.writeString(output.resolve("file"), "result");
        new BuildExecutorHttpCache(uri, "team-alpha", "SHA-256", Duration.ofSeconds(1), true, false)
                .store(Runnable::run, "step", step, in, output);
        assertThat(blobs).isEmpty();
        assertThat(new BuildExecutorHttpCache(uri, "team-alpha").fetch(Runnable::run, "step", step, in, target)).isEmpty();
    }

    @Test
    public void read_disabled_does_not_fetch() throws IOException {
        BuildExecutorHttpCache cache = new BuildExecutorHttpCache(uri, "team-alpha", "SHA-256", Duration.ofSeconds(1), false, true);
        byte[] step = {1};
        SequencedMap<String, Map<Path, byte[]>> in = inputs("source", "file", new byte[]{9});
        Files.writeString(output.resolve("file"), "result");
        cache.store(Runnable::run, "step", step, in, output);
        assertThat(blobs).isNotEmpty();
        assertThat(cache.fetch(Runnable::run, "step", step, in, target)).isEmpty();
    }

    @Test
    public void default_connect_timeout_is_short() {
        assertThat(new BuildExecutorHttpCache(uri, "team-alpha").connectTimeout())
                .isEqualTo(Duration.ofSeconds(1));
    }

    private static SequencedMap<String, Map<Path, byte[]>> inputs(String argument, String file, byte[] hash) {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        files.put(Path.of(file), hash);
        SequencedMap<String, Map<Path, byte[]>> inputs = new LinkedHashMap<>();
        inputs.put(argument, files);
        return inputs;
    }
}
