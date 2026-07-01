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
                handler.execute(output, error, null);
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

    @Test
    public void teeing_a_forked_process_writes_the_files_and_streams_each_line() throws Exception {
        Path source = root.resolve("Chatty.java");
        Files.writeString(source, """
                public class Chatty {
                    public static void main(String[] args) {
                        System.out.println("out-one");
                        System.err.println("err-one");
                        System.out.println("out-two");
                    }
                }
                """);
        Path output = root.resolve("output"), error = root.resolve("error");
        List<String> outLines = new CopyOnWriteArrayList<>(), errLines = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProcessHandler handler = ProcessHandler.OfProcess.ofJavaHome("bin/java").apply(List.of(source.toString()));
            assertThat(handler.execute(output, error,
                    new ProcessHandler.Tee(executor, outLines::add, errLines::add))).isZero();
        } finally {
            executor.shutdown();
        }
        assertThat(Files.readString(output)).contains("out-one").contains("out-two");
        assertThat(Files.readString(error)).contains("err-one");
        assertThat(outLines).contains("out-one", "out-two");
        assertThat(errLines).contains("err-one");
    }

    @Test
    public void teeing_a_tool_writes_the_files_and_streams_each_line() throws Exception {
        ToolProvider tool = new ToolProvider() {
            @Override
            public String name() {
                return "chatty";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                out.println("out-one");
                err.println("err-one");
                out.println("out-two");
                return 0;
            }
        };
        Path output = root.resolve("output"), error = root.resolve("error");
        List<String> outLines = new CopyOnWriteArrayList<>(), errLines = new CopyOnWriteArrayList<>();
        ProcessHandler handler = ProcessHandler.OfTool.of(tool).apply(List.of());
        assertThat(handler.execute(output, error,
                new ProcessHandler.Tee(Runnable::run, outLines::add, errLines::add))).isZero();
        assertThat(Files.readString(output)).contains("out-one").contains("out-two");
        assertThat(Files.readString(error)).contains("err-one");
        assertThat(outLines).containsExactly("out-one", "out-two");
        assertThat(errLines).containsExactly("err-one");
    }
}
