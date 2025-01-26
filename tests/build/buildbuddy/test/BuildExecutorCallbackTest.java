package build.buildbuddy.test;

import build.buildbuddy.BuildExecutorCallback;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

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
        assertThat(outputStream.toString(StandardCharsets.UTF_8)).matches("\\[EXECUTED] foo in [0-9]+.[0-9]{2}\n");
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