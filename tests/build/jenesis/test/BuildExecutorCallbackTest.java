package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutorCallback;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildExecutorCallbackTest {

    @Test
    public void can_print_executed() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(outputStream)) {
            BuildExecutorCallback.printing(printStream, false, null)
                    .step("foo", new LinkedHashSet<>(Set.of("bar")))
                    .accept(true, null);
        }
        assertThat(outputStream.toString(StandardCharsets.UTF_8))
                .matches(Pattern.quote(BuildExecutorCallback.GREEN + "[EXECUTED] " + BuildExecutorCallback.RESET)
                        + " foo "
                        + Pattern.quote(BuildExecutorCallback.CYAN)
                        + "in [0-9]+.[0-9]{2} seconds"
                        + Pattern.quote(BuildExecutorCallback.RESET)
                        + "\n");
    }

    @Test
    public void can_print_skipped() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(outputStream)) {
            BuildExecutorCallback.printing(printStream, false, null)
                    .step("foo", new LinkedHashSet<>(Set.of("bar")))
                    .accept(false, null);
        }
        assertThat(outputStream.toString(StandardCharsets.UTF_8))
                .isEqualTo(BuildExecutorCallback.BLUE + "[SKIPPED]  " + BuildExecutorCallback.RESET + " foo\n");
    }

    @Test
    public void can_print_failed() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (PrintStream printStream = new PrintStream(outputStream)) {
            BuildExecutorCallback.printing(printStream, false, null)
                    .step("foo", new LinkedHashSet<>(Set.of("bar")))
                    .accept(null, new RuntimeException("message"));
        }
        assertThat(outputStream.toString(StandardCharsets.UTF_8))
                .isEqualTo(BuildExecutorCallback.RED + "[FAILED]   " + BuildExecutorCallback.RESET + " foo: message\n");
    }
}