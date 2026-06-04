package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.project.ProjectWatch;

import static org.assertj.core.api.Assertions.assertThat;

public class ProjectWatchTest {

    @TempDir
    private Path root;

    @Test
    public void runs_an_initial_build_and_rebuilds_on_change() throws Exception {
        Files.writeString(root.resolve("Sample.java"), "initial");
        CountDownLatch initial = new CountDownLatch(1);
        CountDownLatch afterChange = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        Runnable build = () -> {
            if (builds.incrementAndGet() == 1) {
                initial.countDown();
            } else {
                afterChange.countDown();
            }
        };
        Thread thread = watching(new ProjectWatch(root, Set.of(), 50L), build);
        try {
            assertThat(initial.await(30, TimeUnit.SECONDS)).isTrue();
            Files.writeString(root.resolve("Sample.java"), "changed");
            assertThat(afterChange.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            stop(thread);
        }
    }

    @Test
    public void ignores_changes_under_an_excluded_directory() throws Exception {
        Path excluded = Files.createDirectory(root.resolve("target"));
        Files.writeString(root.resolve("Sample.java"), "initial");
        CountDownLatch initial = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        Runnable build = () -> {
            builds.incrementAndGet();
            initial.countDown();
        };
        Thread thread = watching(new ProjectWatch(root, Set.of(excluded.toAbsolutePath().normalize()), 50L), build);
        try {
            assertThat(initial.await(30, TimeUnit.SECONDS)).isTrue();
            Files.writeString(excluded.resolve("ignored.txt"), "noise");
            Thread.sleep(500);
            assertThat(builds.get()).isEqualTo(1);
        } finally {
            stop(thread);
        }
    }

    private static Thread watching(ProjectWatch watch, Runnable build) {
        Thread thread = new Thread(() -> {
            try {
                watch.watch(build);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stop(Thread thread) throws InterruptedException {
        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(5));
    }
}
