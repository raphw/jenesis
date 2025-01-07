package build.buildbuddy.test;

import build.buildbuddy.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
        buildExecutor = BuildExecutor.of(root, hash);
    }

    @Test
    public void can_execute_build() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foobar");
    }

    @Test
    public void rejects_use_of_context_if_not_exists() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(false));
        }, "source");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .hasMessageContaining("Cannot reuse non-existing location for step")
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(root.resolve("step")).doesNotExist();
    }

    @Test
    public void handles_error_in_step() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            throw new RuntimeException("baz");
        }, "source");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .hasMessageContaining("baz")
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(root.resolve("step")).doesNotExist();
    }

    @Test
    public void can_execute_build_with_skipped_step() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath(),
                step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, hash));
        Files.writeString(output.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, hash));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, _, _) -> {
            throw new AssertionError("Did not expect that step is executed");
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foo");
    }

    @Test
    public void can_execute_build_with_changed_source() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath(),
                step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, _ -> new byte[0]));
        Files.writeString(output.resolve("file"), "bar");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, hash));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).exists().isEqualTo(output);
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ALTERED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foobar");
    }

    @Test
    public void can_execute_build_with_changed_source_and_use_of_context() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath(),
                step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, _ -> new byte[0]));
        Files.writeString(output.resolve("file"), "qux");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, hash));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).exists().isEqualTo(output);
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ALTERED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(false));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("qux");
    }

    @Test
    public void can_execute_build_with_inconsistent_output() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath(),
                step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, hash));
        Files.writeString(output.resolve("file"), "qux");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, _ -> new byte[0]));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foobar");
    }

    @Test
    public void can_execute_build_multiple_steps() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step2", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step1");
            assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step1").resolve("output"));
            assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step1").folder().resolve("file")) + "qux");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step1");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1", "step2");
        assertThat(root.resolve("step2").resolve("output").resolve("file")).content().isEqualTo("foobarqux");
    }

    @Test
    public void can_execute_multiple_sources() throws IOException {
        Path source1 = temporaryFolder.newFolder("source1").toPath(), source2 = temporaryFolder.newFolder("source2").toPath();
        Files.writeString(source1.resolve("file"), "foo");
        Files.writeString(source2.resolve("file"), "bar");
        buildExecutor.addSource("source1", source1);
        buildExecutor.addSource("source2", source2);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source1", "source2");
            assertThat(arguments.get("source1").folder()).isEqualTo(source1);
            assertThat(arguments.get("source2").folder()).isEqualTo(source2);
            assertThat(arguments.get("source1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            assertThat(arguments.get("source2").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source1").folder().resolve("file"))
                            + Files.readString(arguments.get("source2").folder().resolve("file")));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source1", "source2");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source1", "source2", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foobar");
    }

    @Test
    public void can_execute_diverging_steps() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step2", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "qux");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step3", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step1", "step2");
            assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step1").resolve("output"));
            assertThat(arguments.get("step2").folder()).isEqualTo(root.resolve("step2").resolve("output"));
            assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            assertThat(arguments.get("step2").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step1").folder().resolve("file"))
                            + Files.readString(arguments.get("step2").folder().resolve("file")));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step1", "step2");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1", "step2", "step3");
        assertThat(root.resolve("step3").resolve("output").resolve("file")).content().isEqualTo("foobarfooqux");
    }

    @Test
    public void can_execute_nested() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.add("step", (buildExecutor, paths) -> {
            assertThat(paths).isEmpty();
            buildExecutor.addSource("source", source);
            buildExecutor.addStep("step1", (_, context, arguments) -> {
                assertThat(context.previous()).isNull();
                assertThat(context.next()).isDirectory();
                assertThat(arguments).containsOnlyKeys("source");
                assertThat(arguments.get("source").folder()).isEqualTo(source);
                assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
            buildExecutor.addStep("step2", (_, context, arguments) -> {
                assertThat(context.previous()).isNull();
                assertThat(context.next()).isDirectory();
                assertThat(arguments).containsOnlyKeys("step1");
                assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step").resolve("step1").resolve("output"));
                assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("step1").folder().resolve("file")) + "qux");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "step1");
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("step/source", "step/step1", "step/step2");
        assertThat(root.resolve("step").resolve("step2").resolve("output").resolve("file"))
                .content()
                .isEqualTo("foobarqux");
    }

    @Test
    public void can_execute_nested_parent_reference() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.add("step", (buildExecutor, paths) -> {
            assertThat(paths).containsOnlyKeys("source");
            assertThat(paths.get("source")).isEqualTo(source);
            buildExecutor.addStep("step1", (_, context, arguments) -> {
                assertThat(context.previous()).isNull();
                assertThat(context.next()).isDirectory();
                assertThat(arguments).containsOnlyKeys("../source");
                assertThat(arguments.get("../source").folder()).isEqualTo(source);
                assertThat(arguments.get("../source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("../source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "../source");
            buildExecutor.addStep("step2", (_, context, arguments) -> {
                assertThat(context.previous()).isNull();
                assertThat(context.next()).isDirectory();
                assertThat(arguments).containsOnlyKeys("step1");
                assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step").resolve("step1").resolve("output"));
                assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("step1").folder().resolve("file")) + "qux");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "step1");
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step/step1", "step/step2");
        assertThat(root.resolve("step").resolve("step2").resolve("output").resolve("file"))
                .content()
                .isEqualTo("foobarqux");
    }

    @Test
    public void propagates_nested_error() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.add("step", (buildExecutor, paths) -> {
            assertThat(paths).containsOnlyKeys("source");
            assertThat(paths.get("source")).isEqualTo(source);
            buildExecutor.addStep("step1", (_, context, arguments) -> {
                assertThat(context.previous()).isNull();
                assertThat(context.next()).isDirectory();
                assertThat(arguments).containsOnlyKeys("../source");
                assertThat(arguments.get("../source").folder()).isEqualTo(source);
                assertThat(arguments.get("../source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                throw new RuntimeException("baz");
            }, "../source");
        }, "source");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .hasMessageContaining("baz")
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(root.resolve("step").resolve("step1").resolve("output")).doesNotExist();
    }

    @Test
    public void can_detect_nested_missing_reference() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.add("step", (buildExecutor, paths) -> {
            assertThat(paths).containsOnlyKeys("source");
            assertThat(paths.get("source")).isEqualTo(source);
            buildExecutor.addStep("step1", (_, _, _) -> {
                throw new AssertionError();
            }, "../missing");
        }, "source");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .hasMessageContaining("Did not inherit: ../missing")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("step").resolve("step1").resolve("output")).doesNotExist();
    }

    @Test
    public void can_execute_nested_parent_child_reference() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.add("source", (buildExecutor, paths) -> {
            assertThat(paths).isEmpty();
            buildExecutor.addSource("source1", source);
        });
        buildExecutor.add("step", (buildExecutor, paths) -> {
            assertThat(paths).containsOnlyKeys("source/source1");
            assertThat(paths.get("source/source1")).isEqualTo(source);
            buildExecutor.addStep("step1", (_, context, arguments) -> {
                assertThat(context.previous()).isNull();
                assertThat(context.next()).isDirectory();
                assertThat(arguments).containsOnlyKeys("../source/source1");
                assertThat(arguments.get("../source/source1").folder()).isEqualTo(source);
                assertThat(arguments.get("../source/source1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("../source/source1").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "../source/source1");
            buildExecutor.addStep("step2", (_, context, arguments) -> {
                assertThat(context.previous()).isNull();
                assertThat(context.next()).isDirectory();
                assertThat(arguments).containsOnlyKeys("step1");
                assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step").resolve("step1").resolve("output"));
                assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("step1").folder().resolve("file")) + "qux");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "step1");
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source/source1", "step/step1", "step/step2");
        assertThat(root.resolve("step").resolve("step2").resolve("output").resolve("file"))
                .content()
                .isEqualTo("foobarqux");
    }

    @Test
    public void can_execute_multi_nest() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.add("step", (buildExecutor, paths) -> {
            assertThat(paths).containsOnlyKeys("source");
            assertThat(paths.get("source")).isEqualTo(source);
            buildExecutor.add("step1", (nestedBuildExecutor, nestedPaths) -> {
                assertThat(nestedPaths).containsOnlyKeys("../source");
                assertThat(nestedPaths.get("../source")).isEqualTo(source);
                nestedBuildExecutor.addStep("step2", (_, context, arguments) -> {
                    assertThat(context.previous()).isNull();
                    assertThat(context.next()).isDirectory();
                    assertThat(arguments).containsOnlyKeys("../../source");
                    assertThat(arguments.get("../../source").folder()).isEqualTo(source);
                    assertThat(arguments.get("../../source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                    Files.writeString(
                            context.next().resolve("file"),
                            Files.readString(arguments.get("../../source").folder().resolve("file")) + "bar");
                    return CompletableFuture.completedStage(new BuildStepResult(true));
                }, "../../source");
            }, "../source");
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step/step1/step2");
        assertThat(root.resolve("step").resolve("step1").resolve("step2").resolve("output").resolve("file"))
                .content()
                .isEqualTo("foobar");
    }

    @Test
    public void can_replace_step() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, _, _) -> {
            throw new AssertionError();
        }, "source");
        buildExecutor.replaceStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        Path result = root.resolve("step").resolve("output").resolve("file");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foobar");
    }

    @Test
    public void can_prepend_step() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step0", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step2", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step1");
            assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step1").resolve("output"));
            assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step1").folder().resolve("file")) + "qux");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step0");
        buildExecutor.prependStep("step2", "step1", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step0");
            assertThat(arguments.get("step0").folder()).isEqualTo(root.resolve("step0").resolve("output"));
            assertThat(arguments.get("step0").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step0").folder().resolve("file")) + "baz");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step0", "step2", "step1");
        Path result = root.resolve("step2").resolve("output").resolve("file");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foobarbazqux");
    }

    @Test
    public void can_append_step() throws IOException {
        Path source = temporaryFolder.newFolder("source").toPath();
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step2", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step1");
            assertThat(arguments.get("step1").folder()).isEqualTo(root.resolve("step1").resolve("output"));
            assertThat(arguments.get("step1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step1").folder().resolve("file")) + "qux");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step1");
        buildExecutor.appendStep("step1", "step0", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step0");
            assertThat(arguments.get("step0").folder()).isEqualTo(root.resolve("step0").resolve("output"));
            assertThat(arguments.get("step0").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step0").folder().resolve("file")) + "baz");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1", "step2", "step0");
        Path result = root.resolve("step2").resolve("output").resolve("file");
        assertThat(result).isRegularFile();
        assertThat(result).content().isEqualTo("foobarbazqux");
    }
}
