package build.jenesis.test;

import build.jenesis.BuildExecutorCallback;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildExecutorCallbackTest {

    @Test
    public void can_print_executed() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(outputStream)) {
            BuildExecutorCallback.printing(printStream)
                    .step("foo", new LinkedHashSet<>(Set.of("bar")))
                    .accept(true, null);
        }
        assertThat(outputStream.toString(StandardCharsets.UTF_8))
                .matches("\\[EXECUTED] foo in [0-9]+.[0-9]{2} seconds\n");
    }

    @Test
    public void can_print_skipped() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(outputStream)) {
            BuildExecutorCallback.printing(printStream)
                    .step("foo", new LinkedHashSet<>(Set.of("bar")))
                    .accept(false, null);
        }
        assertThat(outputStream.toString(StandardCharsets.UTF_8)).matches("\\[SKIPPED] foo\n");
    }

    @Test
    public void can_print_failed() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(outputStream)) {
            BuildExecutorCallback.printing(printStream)
                    .step("foo", new LinkedHashSet<>(Set.of("bar")))
                    .accept(null, new RuntimeException("message"));
        }
        assertThat(outputStream.toString(StandardCharsets.UTF_8)).matches("\\[FAILED] foo: message\n");
    }
}