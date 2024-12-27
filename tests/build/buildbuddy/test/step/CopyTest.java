package build.buildbuddy.test.step;

import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.step.Copy;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

public class CopyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
    }

    @Test
    public void can_copy_file() throws IOException, ExecutionException, InterruptedException {
        Path source = Files.writeString(Files.createTempFile("sample", ".txt"), "foo");
        BuildStepResult result = new Copy(Map.of(Path.of("target", "copy.txt"), source.toUri()), null).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of()).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("target/copy.txt")).content().contains("foo");
    }

    @Test
    public void can_copy_file_from_cache() throws IOException, ExecutionException, InterruptedException {
        Path first = Files.writeString(Files.createTempFile("sample", ".txt"), "foo");
        Files.copy(first, Files.createDirectories(previous.resolve("target")).resolve("first.txt"));
        Files.writeString(first, "qux");
        Path second = Files.writeString(Files.createTempFile("sample", ".txt"), "bar");
        BuildStepResult result = new Copy(Map.of(
                Path.of("target", "first.txt"), first.toUri(),
                Path.of("target", "second.txt"), second.toUri()), Duration.ofMinutes(1)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of()).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("target/first.txt")).content().contains("foo");
        assertThat(next.resolve("target/second.txt")).content().contains("bar");
    }

    @Test
    public void can_discover_expired_file_from_cache() throws IOException, ExecutionException, InterruptedException {
        Path first = Files.writeString(Files.createTempFile("sample", ".txt"), "foo");
        Files.copy(first, Files.createDirectories(previous.resolve("target")).resolve("first.txt"));
        Files.writeString(first, "qux");
        Path second = Files.writeString(Files.createTempFile("sample", ".txt"), "bar");
        Thread.sleep(Duration.ofSeconds(1));
        BuildStepResult result = new Copy(Map.of(
                Path.of("target", "first.txt"), first.toUri(),
                Path.of("target", "second.txt"), second.toUri()), Duration.ofMillis(1)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of()).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("target/first.txt")).content().contains("qux");
        assertThat(next.resolve("target/second.txt")).content().contains("bar");
    }

    @Test
    public void can_copy_file_from_cache_none_changed() throws IOException, ExecutionException, InterruptedException {
        Path first = Files.writeString(Files.createTempFile("sample", ".txt"), "foo");
        Files.copy(first, Files.createDirectories(previous.resolve("target")).resolve("first.txt"));
        Files.writeString(first, "qux");
        Path second = Files.writeString(Files.createTempFile("sample", ".txt"), "bar");
        Files.copy(second, Files.createDirectories(previous.resolve("target")).resolve("second.txt"));
        Files.writeString(first, "baz");
        BuildStepResult result = new Copy(Map.of(
                Path.of("target", "first.txt"), first.toUri(),
                Path.of("target", "second.txt"), second.toUri()), Duration.ofMinutes(1)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of()).toCompletableFuture().get();
        assertThat(result.next()).isFalse();
        assertThat(previous.resolve("target/first.txt")).content().contains("foo");
        assertThat(previous.resolve("target/second.txt")).content().contains("bar");
    }
}
