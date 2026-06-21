package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildExecutorFileCache;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildExecutorFileCacheTest implements Serializable {

    private static final AtomicInteger EXECUTIONS = new AtomicInteger();

    @TempDir
    private Path cacheRoot, output, target, source, firstTarget, secondTarget;
    private transient HashDigestFunction hash;

    @BeforeEach
    public void setUp() {
        hash = new HashDigestFunction("MD5");
    }

    @Test
    public void stores_and_fetches_round_trip() throws IOException {
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        byte[] step = {1, 2, 3, 4};
        SequencedMap<String, Map<Path, byte[]>> inputs = inputs("source", "file", new byte[]{9});
        Files.writeString(output.resolve("file"), "result");
        cache.store(Runnable::run, "step", step, inputs, output);
        Path stepFolder = cacheRoot.resolve(HexFormat.of().formatHex(step));
        assertThat(stepFolder).isDirectory();
        try (Stream<Path> entries = Files.list(stepFolder)) {
            List<Path> list = entries.toList();
            assertThat(list).hasSize(1);
            assertThat(list.getFirst().resolve("file")).content().isEqualTo("result");
        }
        Optional<BuildStepResult> result = cache.fetch(Runnable::run, "step", step, inputs, target);
        assertThat(result).isPresent();
        assertThat(target.resolve("file")).content().isEqualTo("result");
    }

    @Test
    public void fetch_returns_empty_on_miss() throws IOException {
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        Optional<BuildStepResult> result = cache.fetch(
                Runnable::run,
                "step",
                new byte[]{1},
                inputs("source", "file", new byte[]{9}),
                target);
        assertThat(result).isEmpty();
        try (Stream<Path> entries = Files.list(target)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    public void fetch_materializes_via_hard_link() throws IOException {
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        byte[] step = {1};
        SequencedMap<String, Map<Path, byte[]>> inputs = inputs("source", "file", new byte[]{9});
        Files.writeString(output.resolve("file"), "result");
        cache.store(Runnable::run, "step", step, inputs, output);
        cache.fetch(Runnable::run, "step", step, inputs, target);
        Path cached;
        try (Stream<Path> entries = Files.list(cacheRoot.resolve(HexFormat.of().formatHex(step)))) {
            cached = entries.findFirst().orElseThrow().resolve("file");
        }
        Object targetKey = Files.readAttributes(target.resolve("file"), BasicFileAttributes.class).fileKey();
        Object cachedKey = Files.readAttributes(cached, BasicFileAttributes.class).fileKey();
        Assumptions.assumeTrue(targetKey != null && cachedKey != null);
        assertThat(targetKey).isEqualTo(cachedKey);
    }

    @Test
    public void reads_digest_from_cache_properties() throws IOException {
        Files.writeString(cacheRoot.resolve("cache.properties"), "digest=MD5\n");
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        byte[] step = {1};
        Files.writeString(output.resolve("file"), "result");
        cache.store(Runnable::run, "step", step, inputs("source", "file", new byte[]{9}), output);
        try (Stream<Path> entries = Files.list(cacheRoot.resolve(HexFormat.of().formatHex(step)))) {
            String name = entries.findFirst().orElseThrow().getFileName().toString();
            assertThat(name).hasSize(32);
        }
    }

    @Test
    public void configuration_resolves_cache_from_property() throws IOException {
        String previous = System.getProperty("jenesis.executor.cache");
        try {
            System.clearProperty("jenesis.executor.cache");
            assertThat(new BuildExecutor.Configuration().cache()).isNotInstanceOf(BuildExecutorFileCache.class);
            System.setProperty("jenesis.executor.cache", cacheRoot.toString());
            assertThat(new BuildExecutor.Configuration().cache()).isInstanceOf(BuildExecutorFileCache.class);
        } finally {
            if (previous == null) {
                System.clearProperty("jenesis.executor.cache");
            } else {
                System.setProperty("jenesis.executor.cache", previous);
            }
        }
    }

    @Test
    public void serves_step_output_from_cache_across_builds() throws IOException {
        EXECUTIONS.set(0);
        Files.writeString(source.resolve("file"), "foo");
        BuildExecutorCache cache = new BuildExecutorFileCache(cacheRoot);
        BuildStep buildStep = (_, context, arguments) -> {
            EXECUTIONS.incrementAndGet();
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        };

        BuildExecutor first = BuildExecutor.of(firstTarget,
                Duration.ZERO,
                hash,
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(),
                cache,
                false);
        first.addSource("source", source);
        first.addStep("step", buildStep, "source");
        first.execute(Runnable::run).toCompletableFuture().join();
        assertThat(firstTarget.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foobar");
        assertThat(EXECUTIONS).hasValue(1);

        BuildExecutor second = BuildExecutor.of(secondTarget,
                Duration.ZERO,
                hash,
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(),
                cache,
                false);
        second.addSource("source", source);
        second.addStep("step", buildStep, "source");
        second.execute(Runnable::run).toCompletableFuture().join();
        assertThat(secondTarget.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foobar");
        assertThat(EXECUTIONS).hasValue(1);
    }

    @Test
    public void evicts_least_recently_updated_step_folder() throws IOException {
        Files.writeString(cacheRoot.resolve("cache.properties"), "steps=2\n");
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        store(cache, new byte[]{1}, new byte[]{9});
        store(cache, new byte[]{2}, new byte[]{9});
        Path a = cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{1}));
        Path b = cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{2}));
        Files.setLastModifiedTime(a, FileTime.from(Instant.now().minusSeconds(300)));
        Files.setLastModifiedTime(b, FileTime.from(Instant.now().minusSeconds(60)));
        store(cache, new byte[]{3}, new byte[]{9});
        assertThat(a).doesNotExist();
        assertThat(b).isDirectory();
        assertThat(cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{3}))).isDirectory();
    }

    @Test
    public void evicts_least_recently_updated_version() throws IOException {
        Files.writeString(cacheRoot.resolve("cache.properties"), "versions=2\n");
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        byte[] step = {1};
        Path v1 = store(cache, step, new byte[]{1});
        Path v2 = store(cache, step, new byte[]{2});
        Files.setLastModifiedTime(v1, FileTime.from(Instant.now().minusSeconds(300)));
        Files.setLastModifiedTime(v2, FileTime.from(Instant.now().minusSeconds(60)));
        Path v3 = store(cache, step, new byte[]{3});
        assertThat(v1).doesNotExist();
        assertThat(v2).isDirectory();
        assertThat(v3).isDirectory();
    }

    @Test
    public void evicts_most_recently_updated_when_configured() throws IOException {
        Files.writeString(cacheRoot.resolve("cache.properties"), "steps=2\nlru=false\n");
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        store(cache, new byte[]{1}, new byte[]{9});
        store(cache, new byte[]{2}, new byte[]{9});
        Path a = cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{1}));
        Path b = cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{2}));
        Files.setLastModifiedTime(a, FileTime.from(Instant.now().minusSeconds(300)));
        Files.setLastModifiedTime(b, FileTime.from(Instant.now().minusSeconds(200)));
        store(cache, new byte[]{3}, new byte[]{9});
        assertThat(cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{3}))).doesNotExist();
        assertThat(a).isDirectory();
        assertThat(b).isDirectory();
    }

    @Test
    public void touch_updates_timestamps_on_read() throws IOException {
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        byte[] step = {1};
        SequencedMap<String, Map<Path, byte[]>> in = inputs("source", "file", new byte[]{9});
        Path entry = store(cache, step, new byte[]{9});
        Path folder = cacheRoot.resolve(HexFormat.of().formatHex(step));
        Files.setLastModifiedTime(entry, FileTime.from(Instant.now().minusSeconds(300)));
        Files.setLastModifiedTime(folder, FileTime.from(Instant.now().minusSeconds(300)));
        FileTime entryBefore = Files.getLastModifiedTime(entry);
        FileTime folderBefore = Files.getLastModifiedTime(folder);
        cache.fetch(Runnable::run, "step", step, in, target);
        assertThat(Files.getLastModifiedTime(entry)).isGreaterThan(entryBefore);
        assertThat(Files.getLastModifiedTime(folder)).isGreaterThan(folderBefore);
    }

    @Test
    public void does_not_touch_when_disabled() throws IOException {
        Files.writeString(cacheRoot.resolve("cache.properties"), "touch=false\n");
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        byte[] step = {1};
        SequencedMap<String, Map<Path, byte[]>> in = inputs("source", "file", new byte[]{9});
        Path entry = store(cache, step, new byte[]{9});
        Files.setLastModifiedTime(entry, FileTime.from(Instant.now().minusSeconds(300)));
        FileTime before = Files.getLastModifiedTime(entry);
        cache.fetch(Runnable::run, "step", step, in, target);
        assertThat(Files.getLastModifiedTime(entry)).isEqualTo(before);
    }

    @Test
    public void reads_step_limit_from_cache_properties() throws IOException {
        Files.writeString(cacheRoot.resolve("cache.properties"), "steps=1\n");
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        store(cache, new byte[]{1}, new byte[]{9});
        Path a = cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{1}));
        Files.setLastModifiedTime(a, FileTime.from(Instant.now().minusSeconds(300)));
        store(cache, new byte[]{2}, new byte[]{9});
        assertThat(a).doesNotExist();
        assertThat(cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{2}))).isDirectory();
    }

    @Test
    public void frozen_cache_serves_reads_without_touching() throws IOException {
        byte[] step = {1};
        SequencedMap<String, Map<Path, byte[]>> in = inputs("source", "file", new byte[]{9});
        Path entry = store(new BuildExecutorFileCache(cacheRoot), step, new byte[]{9});
        Files.writeString(cacheRoot.resolve("cache.properties"), "frozen=true\n");
        Files.setLastModifiedTime(entry, FileTime.from(Instant.now().minusSeconds(300)));
        FileTime before = Files.getLastModifiedTime(entry);
        Optional<BuildStepResult> result = new BuildExecutorFileCache(cacheRoot)
                .fetch(Runnable::run, "step", step, in, target);
        assertThat(result).isPresent();
        assertThat(target.resolve("file")).content().isEqualTo("x");
        assertThat(Files.getLastModifiedTime(entry)).isEqualTo(before);
    }

    @Test
    public void reads_frozen_from_cache_properties() throws IOException {
        Files.writeString(cacheRoot.resolve("cache.properties"), "frozen=true\n");
        BuildExecutorFileCache cache = new BuildExecutorFileCache(cacheRoot);
        Files.writeString(output.resolve("file"), "result");
        cache.store(Runnable::run, "step", new byte[]{1}, inputs("source", "file", new byte[]{9}), output);
        assertThat(cacheRoot.resolve(HexFormat.of().formatHex(new byte[]{1}))).doesNotExist();
    }

    private Path store(BuildExecutorFileCache cache, byte[] step, byte[] inputHash) throws IOException {
        Files.writeString(output.resolve("file"), "x");
        Path folder = cacheRoot.resolve(HexFormat.of().formatHex(step));
        Set<Path> before = children(folder);
        cache.store(Runnable::run, "step", step, inputs("source", "file", inputHash), output);
        Set<Path> after = children(folder);
        after.removeAll(before);
        return after.isEmpty() ? null : after.iterator().next();
    }

    private static Set<Path> children(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return new HashSet<>();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.collect(Collectors.toCollection(HashSet::new));
        }
    }

    private static SequencedMap<String, Map<Path, byte[]>> inputs(String argument, String file, byte[] hash) {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        files.put(Path.of(file), hash);
        SequencedMap<String, Map<Path, byte[]>> inputs = new LinkedHashMap<>();
        inputs.put(argument, files);
        return inputs;
    }
}
