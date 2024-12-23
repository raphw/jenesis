package build.buildbuddy.steps;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

public class ResolveTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, original;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        original = Files.createDirectory(root.resolve("original"));
    }

    @Test
    public void can_link_values() throws IOException, ExecutionException, InterruptedException {
        Files.writeString(original.resolve("file"), "foo");
        Files.writeString(Files.createDirectories(original.resolve("folder/sub")).resolve("file"), "bar");
        BuildStepResult result = new Resolve(Map.of(
                Path.of("file"), Path.of("other/copied"),
                Path.of("folder"), Path.of("other"))).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("original", new BuildStepArgument(
                        original,
                        Map.of(Path.of("file"), ChecksumStatus.ADDED,
                               Path.of("folder/sub/file"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("other/copied")).content().isEqualTo("foo");
        assertThat(next.resolve("other/sub/file")).content().isEqualTo("bar");
    }

}