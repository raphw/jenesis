package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.step.ProcessHandler;

import static org.assertj.core.api.Assertions.assertThat;

public class ProcessHandlerTest {

    @TempDir
    private Path root;

    @Test
    public void interrupting_a_forked_process_destroys_it_and_restores_the_interrupt_flag() throws Exception {
        Path source = root.resolve("Sleeper.java");
        Files.writeString(source, """
                public class Sleeper {
                    public static void main(String[] args) throws InterruptedException {
                        Thread.sleep(600_000);
                    }
                }
                """);
        Path output = root.resolve("output"), error = root.resolve("error");
        ProcessHandler handler = ProcessHandler.OfProcess.ofJavaHome("bin/java")
                .apply(List.of(source.toString()));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                handler.execute(output, error);
            } catch (Throwable t) {
                thrown.set(t);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                finished.countDown();
            }
        });
        worker.start();
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1_000);
        worker.interrupt();
        assertThat(finished.await(30, TimeUnit.SECONDS))
                .as("interrupting the worker tears the forked process down promptly")
                .isTrue();
        assertThat(thrown.get()).isInstanceOf(RuntimeException.class);
        assertThat(thrown.get().getCause()).isInstanceOf(InterruptedException.class);
        assertThat(interruptRestored.get())
                .as("the interrupt flag is restored before rethrowing")
                .isTrue();
    }
}
