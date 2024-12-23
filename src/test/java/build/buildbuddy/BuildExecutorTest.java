package build.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildExecutorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path root;
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

    @Test
    public void can_execute_build_multiple_steps() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder().toPath();
        try (Writer writer = Files.newBufferedWriter(source.resolve("sample"))) {
            writer.append("foo");
        }
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (executor, previous, target, dependencies) -> {
            assertThat(previous).doesNotExist();
            assertThat(target).isDirectory();
            assertThat(dependencies).containsOnlyKeys("source");
            assertThat(dependencies.get("source").root()).isEqualTo(source);
            assertThat(dependencies.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(dependencies.get("source").root().resolve("sample"), target.resolve("result"));
            return CompletableFuture.completedStage("Success");
        }, "source");
        buildExecutor.addStep("step2", (executor, previous, target, dependencies) -> {
            assertThat(previous).doesNotExist();
            assertThat(target).isDirectory();
            assertThat(dependencies).containsOnlyKeys("step1");
            assertThat(dependencies.get("step1").root()).isEqualTo(root.resolve("step1"));
            assertThat(dependencies.get("step1").files()).isEqualTo(Map.of(Path.of("result"), ChecksumStatus.ADDED));
            Files.copy(dependencies.get("step1").root().resolve("result"), target.resolve("final"));
            return CompletableFuture.completedStage("Success");
        }, "step1");
        Map<String, BuildResult> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step1", "step2");
        Path result = root.resolve("step2").resolve("final");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foo");
    }

    @Test
    public void can_execute_multiple_sources() throws IOException, ExecutionException, InterruptedException {
        Path source1 = temporaryFolder.newFolder().toPath(), source2 = temporaryFolder.newFolder().toPath();
        try (Writer writer = Files.newBufferedWriter(source1.resolve("sample1"))) {
            writer.append("foo");
        }
        try (Writer writer = Files.newBufferedWriter(source2.resolve("sample2"))) {
            writer.append("bar");
        }
        buildExecutor.addSource("source1", source1);
        buildExecutor.addSource("source2", source2);
        buildExecutor.addStep("step", (executor, previous, target, dependencies) -> {
            assertThat(previous).doesNotExist();
            assertThat(target).isDirectory();
            assertThat(dependencies).containsOnlyKeys("source1", "source2");
            assertThat(dependencies.get("source1").root()).isEqualTo(source1);
            assertThat(dependencies.get("source2").root()).isEqualTo(source2);
            assertThat(dependencies.get("source1").files()).isEqualTo(Map.of(Path.of("sample1"), ChecksumStatus.ADDED));
            assertThat(dependencies.get("source2").files()).isEqualTo(Map.of(Path.of("sample2"), ChecksumStatus.ADDED));
            try (
                    Writer writer = Files.newBufferedWriter(target.resolve("result"));
                    BufferedReader reader1 = Files.newBufferedReader(dependencies.get("source1").root().resolve("sample1"));
                    BufferedReader reader2 = Files.newBufferedReader(dependencies.get("source2").root().resolve("sample2"))
            ) {
                writer.write(Stream.concat(reader1.lines(), reader2.lines()).collect(Collectors.joining()));
            }
            return CompletableFuture.completedStage("Success");
        }, "source1", "source2");
        Map<String, BuildResult> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source1", "source2", "step");
        Path result = root.resolve("step").resolve("result");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foobar");
    }

    @Test
    public void can_execute_diverging_steps() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder().toPath();
        try (Writer writer = Files.newBufferedWriter(source.resolve("sample"))) {
            writer.append("foo");
        }
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (executor, previous, target, dependencies) -> {
            assertThat(previous).doesNotExist();
            assertThat(target).isDirectory();
            assertThat(dependencies).containsOnlyKeys("source");
            assertThat(dependencies.get("source").root()).isEqualTo(source);
            assertThat(dependencies.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(dependencies.get("source").root().resolve("sample"), target.resolve("result"));
            return CompletableFuture.completedStage("Success");
        }, "source");
        buildExecutor.addStep("step2", (executor, previous, target, dependencies) -> {
            assertThat(previous).doesNotExist();
            assertThat(target).isDirectory();
            assertThat(dependencies).containsOnlyKeys("source");
            assertThat(dependencies.get("source").root()).isEqualTo(source);
            assertThat(dependencies.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(dependencies.get("source").root().resolve("sample"), target.resolve("result"));
            return CompletableFuture.completedStage("Success");
        }, "source");
        buildExecutor.addStep("step3", (executor, previous, target, dependencies) -> {
            assertThat(previous).doesNotExist();
            assertThat(target).isDirectory();
            assertThat(dependencies).containsOnlyKeys("step1", "step2");
            assertThat(dependencies.get("step1").root()).isEqualTo(root.resolve("step1"));
            assertThat(dependencies.get("step2").root()).isEqualTo(root.resolve("step2"));
            assertThat(dependencies.get("step1").files()).isEqualTo(Map.of(Path.of("result"), ChecksumStatus.ADDED));
            Files.copy(dependencies.get("step1").root().resolve("result"), target.resolve("result1"));
            assertThat(dependencies.get("step2").files()).isEqualTo(Map.of(Path.of("result"), ChecksumStatus.ADDED));
            Files.copy(dependencies.get("step2").root().resolve("result"), target.resolve("result2"));
            return CompletableFuture.completedStage("Success");
        }, "step1", "step2");
        Map<String, BuildResult> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step1", "step2", "step3");
        Path result1 = root.resolve("step3").resolve("result1");
        assertThat(result1).isRegularFile();
        assertThat(result1).content().isEqualTo("foo");
        Path result2 = root.resolve("step3").resolve("result2");
        assertThat(result2).isRegularFile();
        assertThat(result2).content().isEqualTo("foo");
    }
}
