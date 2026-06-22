package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorLayeredCache;
import build.jenesis.BuildStepResult;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildExecutorLayeredCacheTest {

    @TempDir
    private Path target;

    @Test
    public void local_hit_serves_from_front_and_touches_back() throws IOException {
        RecordingCache front = new RecordingCache(true, true, false);
        RecordingCache back = new RecordingCache(true, true, false);
        Optional<BuildStepResult> result = new BuildExecutorLayeredCache(front, back)
                .fetch(Runnable::run, "step", new byte[]{1}, inputs(), target);
        assertThat(result).isPresent();
        assertThat(front.fetches).hasValue(1);
        assertThat(back.fetches).hasValue(0);
        assertThat(back.touches).hasValue(1);
    }

    @Test
    public void local_miss_falls_through_to_back_and_populates_front() throws IOException {
        RecordingCache front = new RecordingCache(false, true, false);
        RecordingCache back = new RecordingCache(true, true, false);
        byte[] step = {1};
        SequencedMap<String, Map<Path, byte[]>> in = inputs();
        Optional<BuildStepResult> result = new BuildExecutorLayeredCache(front, back)
                .fetch(Runnable::run, "step", step, in, target);
        assertThat(result).isPresent();
        assertThat(front.fetches).hasValue(1);
        assertThat(back.fetches).hasValue(1);
        assertThat(front.stores).hasValue(1);
        assertThat(front.lastStoreStep).isEqualTo(step);
        assertThat(front.lastStoreInputs).containsOnlyKeys("source");
        assertThat(target.resolve("cached")).exists();
    }

    @Test
    public void both_miss_returns_empty_without_populating() throws IOException {
        RecordingCache front = new RecordingCache(false, true, false);
        RecordingCache back = new RecordingCache(false, true, false);
        assertThat(new BuildExecutorLayeredCache(front, back)
                .fetch(Runnable::run, "step", new byte[]{1}, inputs(), target)).isEmpty();
        assertThat(front.stores).hasValue(0);
    }

    @Test
    public void store_writes_through_to_both() throws IOException {
        RecordingCache front = new RecordingCache(false, true, false);
        RecordingCache back = new RecordingCache(false, true, false);
        byte[] step = {1};
        new BuildExecutorLayeredCache(front, back).store(Runnable::run, "step", step, inputs(), target);
        assertThat(front.stores).hasValue(1);
        assertThat(back.stores).hasValue(1);
        assertThat(back.lastStoreStep).isEqualTo(step);
    }

    @Test
    public void touch_fans_out_to_both() throws IOException {
        RecordingCache front = new RecordingCache(false, true, false);
        RecordingCache back = new RecordingCache(false, true, false);
        new BuildExecutorLayeredCache(front, back).touch(Runnable::run, "step", new byte[]{1}, inputs());
        assertThat(front.touches).hasValue(1);
        assertThat(back.touches).hasValue(1);
    }

    @Test
    public void stores_is_a_disjunction() {
        assertThat(new BuildExecutorLayeredCache(
                new RecordingCache(false, false, false),
                new RecordingCache(false, true, false)).stores()).isTrue();
        assertThat(new BuildExecutorLayeredCache(
                new RecordingCache(false, false, false),
                new RecordingCache(false, false, false)).stores()).isFalse();
    }

    @Test
    public void a_failing_front_store_does_not_break_a_remote_hit() throws IOException {
        RecordingCache front = new RecordingCache(false, true, true);
        RecordingCache back = new RecordingCache(true, true, false);
        Optional<BuildStepResult> result = new BuildExecutorLayeredCache(front, back)
                .fetch(Runnable::run, "step", new byte[]{1}, inputs(), target);
        assertThat(result).isPresent();
        assertThat(front.stores).hasValue(1);
    }

    private static SequencedMap<String, Map<Path, byte[]>> inputs() {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        files.put(Path.of("file"), new byte[]{9});
        SequencedMap<String, Map<Path, byte[]>> inputs = new LinkedHashMap<>();
        inputs.put("source", files);
        return inputs;
    }

    private static final class RecordingCache implements BuildExecutorCache {

        private final boolean hit;
        private final boolean storeEnabled;
        private final boolean fail;
        private final AtomicInteger fetches = new AtomicInteger(), stores = new AtomicInteger(), touches = new AtomicInteger();
        private volatile byte[] lastStoreStep;
        private volatile SequencedMap<String, Map<Path, byte[]>> lastStoreInputs;

        private RecordingCache(boolean hit, boolean storeEnabled, boolean fail) {
            this.hit = hit;
            this.storeEnabled = storeEnabled;
            this.fail = fail;
        }

        @Override
        public Optional<BuildStepResult> fetch(Executor executor,
                                               String identity,
                                               byte[] step,
                                               SequencedMap<String, Map<Path, byte[]>> inputs,
                                               Path target) throws IOException {
            fetches.incrementAndGet();
            if (!hit) {
                return Optional.empty();
            }
            Files.writeString(target.resolve("cached"), "cached");
            return Optional.of(new BuildStepResult(true));
        }

        @Override
        public void store(Executor executor,
                          String identity,
                          byte[] step,
                          SequencedMap<String, Map<Path, byte[]>> inputs,
                          Path output) throws IOException {
            stores.incrementAndGet();
            lastStoreStep = step;
            lastStoreInputs = inputs;
            if (fail) {
                throw new IOException("store failed");
            }
        }

        @Override
        public boolean stores() {
            return storeEnabled;
        }

        @Override
        public void touch(Executor executor,
                          String identity,
                          byte[] step,
                          SequencedMap<String, Map<Path, byte[]>> inputs) {
            touches.incrementAndGet();
        }
    }
}
