package build.buildbuddy.test;

import build.buildbuddy.*;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BuildExecutorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path root;
    private HashFunction hash;
    private BuildExecutor buildExecutor;

    @Before
    public void setUp() throws Exception {
        root = temporaryFolder.newFolder("root").toPath();
        hash = new HashDigestFunction("MD5");
        buildExecutor = new BuildExecutor(root, hash);
    }

    @Test
    public void can_execute_build() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("sample"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step");
        Path result = root.resolve("step").resolve("output").resolve("result");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foo");
    }

    @Test
    public void rejects_use_of_context_if_not_exists() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("sample"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            return CompletableFuture.completedStage(new BuildStepResult(false));
        }, "source");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().get())
                .hasMessageContaining("Cannot reuse non-existing location for step")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(root.resolve("step")).doesNotExist();
    }

    @Test
    public void handles_error_in_step() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("sample"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            throw new RuntimeException("baz");
        }, "source");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().get())
                .hasMessageContaining("baz")
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(root.resolve("step")).doesNotExist();
    }

    @Test
    public void can_execute_build_with_skipped_step() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder("source").toPath(),
                step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("sample"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, hash));
        Files.writeString(output.resolve("result"), "foo");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, hash));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, _, _) -> {
            throw new AssertionError("Did not expect that step is executed");
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step");
        Path result = root.resolve("step").resolve("output").resolve("result");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foo");
    }

    @Test
    public void can_execute_build_with_changed_source() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder("source").toPath(),
                step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("sample"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, _ -> new byte[0]));
        Files.writeString(output.resolve("result"), "bar");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, hash));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).exists().isEqualTo(output);
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ALTERED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step");
        Path result = root.resolve("step").resolve("output").resolve("result");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foo");
    }

    @Test
    public void can_execute_build_with_changed_source_and_use_of_context() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder("source").toPath(),
                step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("sample"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, _ -> new byte[0]));
        Files.writeString(output.resolve("result"), "bar");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, hash));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).exists().isEqualTo(output);
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ALTERED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            return CompletableFuture.completedStage(new BuildStepResult(false));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step");
        Path result = root.resolve("step").resolve("output").resolve("result");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("bar");
    }

    @Test
    public void can_execute_build_with_inconsistent_output() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder("source").toPath(),
                step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("sample"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, hash));
        Files.writeString(output.resolve("result"), "bar");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, _ -> new byte[0]));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step");
        Path result = root.resolve("step").resolve("output").resolve("result");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foo");
    }

    @Test
    public void can_execute_build_multiple_steps() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("sample"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step2", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step1");
            assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step1").resolve("output"));
            assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("result"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("step1").folder().resolve("result"), context.next().resolve("final"));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step1");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step1", "step2");
        Path result = root.resolve("step2").resolve("output").resolve("final");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foo");
    }

    @Test
    public void can_execute_multiple_sources() throws IOException, ExecutionException, InterruptedException {
        Path source1 = temporaryFolder.newFolder("source1").toPath(), source2 = temporaryFolder.newFolder("source2").toPath();
        Files.writeString(source1.resolve("sample1"), "foo");
        Files.writeString(source2.resolve("sample2"), "bar");
        buildExecutor.addSource("source1", source1);
        buildExecutor.addSource("source2", source2);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source1", "source2");
            assertThat(arguments.get("source1").folder()).isEqualTo(source1);
            assertThat(arguments.get("source2").folder()).isEqualTo(source2);
            assertThat(arguments.get("source1").files()).isEqualTo(Map.of(Path.of("sample1"), ChecksumStatus.ADDED));
            assertThat(arguments.get("source2").files()).isEqualTo(Map.of(Path.of("sample2"), ChecksumStatus.ADDED));
            try (Writer writer = Files.newBufferedWriter(context.next().resolve("result"));
                 BufferedReader reader1 = Files.newBufferedReader(arguments.get("source1").folder().resolve("sample1"));
                 BufferedReader reader2 = Files.newBufferedReader(arguments.get("source2").folder().resolve("sample2"))) {
                writer.write(Stream.concat(reader1.lines(), reader2.lines()).collect(Collectors.joining()));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source1", "source2");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source1", "source2", "step");
        Path result = root.resolve("step").resolve("output").resolve("result");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foobar");
    }

    @Test
    public void can_execute_diverging_steps() throws IOException, ExecutionException, InterruptedException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("sample"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step2", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("sample"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("source").folder().resolve("sample"), context.next().resolve("result"));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step3", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step1", "step2");
            assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step1").resolve("output"));
            assertThat(arguments.get("step2").folder()).isEqualTo(root.resolve("step2").resolve("output"));
            assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("result"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("step1").folder().resolve("result"), context.next().resolve("result1"));
            assertThat(arguments.get("step2").files()).isEqualTo(Map.of(Path.of("result"), ChecksumStatus.ADDED));
            Files.copy(arguments.get("step2").folder().resolve("result"), context.next().resolve("result2"));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step1", "step2");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().get();
        assertThat(build).containsOnlyKeys("source", "step1", "step2", "step3");
        Path result1 = root.resolve("step3").resolve("output").resolve("result1");
        assertThat(result1).isRegularFile();
        assertThat(result1).content().isEqualTo("foo");
        Path result2 = root.resolve("step3").resolve("output").resolve("result2");
        assertThat(result2).isRegularFile();
        assertThat(result2).content().isEqualTo("foo");
    }
}
