package codes.rafael.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildExecutorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path root, log;
    private BuildExecutor buildExecutor;

    @Before
    public void setUp() throws Exception {
        root = temporaryFolder.newFolder().toPath();
        buildExecutor = new BuildExecutor(root, new ChecksumDigestDiff("MD5"));
    }

    @Test
    public void can_execute_build() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder().toPath();
        try (Writer writer = Files.newBufferedWriter(source.resolve("sample"))) {
            writer.append("foo");
        }
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (executor, previous, target, dependencies) -> {
            assertThat(previous).doesNotExist();
            assertThat(target).isDirectory();
            assertThat(dependencies).containsOnlyKeys("source");
            assertThat(dependencies.get("source").root()).isEqualTo(source);
            assertThat(dependencies.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(dependencies.get("source").root().resolve("sample"), target.resolve("result"));
            return CompletableFuture.completedStage("Success");
        }, "source");
        Map<String, BuildResult> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step");
        Path result = root.resolve("step").resolve("result");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foo");
    }
}