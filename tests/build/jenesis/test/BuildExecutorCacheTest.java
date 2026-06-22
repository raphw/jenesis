package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildExecutorCacheTest implements Serializable {

    @TempDir
    private Path root, source;
    private transient HashDigestFunction hash;

    @BeforeEach
    public void setUp() {
        hash = new HashDigestFunction("MD5");
    }

    @Test
    public void fetches_result_from_cache_instead_of_executing() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        RecordingCache cache = new RecordingCache(true);
        BuildExecutor buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                hash,
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(),
                cache,
                false);
        BuildStep buildStep = (_, _, _) -> {
            throw new AssertionError("Did not expect that step is executed");
        };
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", buildStep, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("cached");
        assertThat(cache.fetches).hasValue(1);
        assertThat(cache.stores).hasValue(0);
        assertThat(cache.fetchIdentity).isEqualTo("step");
        assertThat(cache.fetchStep).isEqualTo(BuildStepHashFunction.ofSerializationDigest("MD5").hash(buildStep));
        assertThat(cache.fetchInputs).containsOnlyKeys("source");
        assertThat(cache.fetchInputs.get("source")).containsOnlyKeys(Path.of("file"));
        assertThat(cache.fetchInputs.get("source").get(Path.of("file"))).isEqualTo(hash.hash(source.resolve("file")));
    }

    @Test
    public void stores_result_after_executing_on_cache_miss() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        RecordingCache cache = new RecordingCache(false);
        BuildExecutor buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                hash,
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(),
                cache,
                false);
        BuildStep buildStep = (_, context, arguments) -> {
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        };
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", buildStep, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foobar");
        assertThat(cache.fetches).hasValue(1);
        assertThat(cache.stores).hasValue(1);
        assertThat(cache.storeIdentity).isEqualTo("step");
        assertThat(cache.storeStep).isEqualTo(BuildStepHashFunction.ofSerializationDigest("MD5").hash(buildStep));
        assertThat(cache.storeInputs).containsOnlyKeys("source");
        assertThat(cache.storeOutput.resolve("file")).content().isEqualTo("foobar");
    }

    @Test
    public void read_only_delegates_fetch_and_suppresses_store() throws IOException {
        RecordingCache delegate = new RecordingCache(true);
        BuildExecutorCache readOnly = delegate.readOnly();
        SequencedMap<String, Map<Path, byte[]>> inputs = new LinkedHashMap<>();
        Optional<BuildStepResult> result = readOnly.fetch(Runnable::run, "step", new byte[]{1}, inputs, root);
        assertThat(result).isPresent();
        assertThat(delegate.fetches).hasValue(1);
        assertThat(delegate.fetchIdentity).isEqualTo("step");
        readOnly.store(Runnable::run, "step", new byte[]{1}, inputs, root);
        assertThat(delegate.stores).hasValue(0);
    }

    @Test
    public void callback_prints_cache_loads_and_stores_with_timing() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BuildExecutorCallback callback = BuildExecutorCallback.printing(new PrintStream(bytes), false, true, root);
        callback.loaded("compile/javac", 5_000_000L);
        callback.stored("compile/javac", 7_000_000L);
        String printed = bytes.toString();
        assertThat(printed).contains("[LOADED]").contains("[STORED]").contains("compile/javac").contains("seconds");
    }

    @Test
    public void callback_omits_cache_lines_when_not_requested() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BuildExecutorCallback callback = BuildExecutorCallback.printing(new PrintStream(bytes), false, false, root);
        callback.loaded("compile/javac", 5_000_000L);
        callback.stored("compile/javac", 7_000_000L);
        assertThat(bytes.toString()).isEmpty();
    }

    private static final class RecordingCache implements BuildExecutorCache {

        private final boolean hit;
        private final AtomicInteger fetches = new AtomicInteger(), stores = new AtomicInteger();
        private volatile String fetchIdentity, storeIdentity;
        private volatile byte[] fetchStep, storeStep;
        private volatile SequencedMap<String, Map<Path, byte[]>> fetchInputs, storeInputs;
        private volatile Path storeOutput;

        private RecordingCache(boolean hit) {
            this.hit = hit;
        }

        @Override
        public boolean stores() {
            return true;
        }

        @Override
        public Optional<BuildStepResult> fetch(Executor executor,
                                               String identity,
                                               byte[] step,
                                               SequencedMap<String, Map<Path, byte[]>> inputs,
                                               Path target) throws IOException {
            fetches.incrementAndGet();
            fetchIdentity = identity;
            fetchStep = step;
            fetchInputs = inputs;
            if (!hit) {
                return Optional.empty();
            }
            Files.writeString(target.resolve("file"), "cached");
            return Optional.of(new BuildStepResult(true));
        }

        @Override
        public void store(Executor executor,
                          String identity,
                          byte[] step,
                          SequencedMap<String, Map<Path, byte[]>> inputs,
                          Path output) {
            stores.incrementAndGet();
            storeIdentity = identity;
            storeStep = step;
            storeInputs = inputs;
            storeOutput = output;
        }
    }
}
