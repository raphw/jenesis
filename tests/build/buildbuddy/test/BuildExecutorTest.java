package build.buildbuddy.test;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorCallback;
import build.buildbuddy.BuildExecutorException;
import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.HashFunction;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BuildExecutorTest {

    @TempDir
    private Path root, source, source2;
    private HashFunction hash;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        hash = new HashDigestFunction("MD5");
        buildExecutor = BuildExecutor.of(root, hash, BuildExecutorCallback.nop());
    }

    @Test
    public void can_execute_build() throws IOException {
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
    public void does_not_except_non_alphanumeric() {
        assertThatThrownBy(() -> buildExecutor.addStep("foo/bar", (_, _, _) -> {
            throw new AssertionError();
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
                "foo/bar does not match pattern: [a-zA-Z0-9-]+");
    }

    @Test
    public void rejects_use_of_context_if_not_exists() throws IOException {
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
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step")
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot reuse initial run for step");
        assertThat(root.resolve("step")).doesNotExist();
    }

    @Test
    public void handles_error_in_step() throws IOException {
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
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step")
                .cause()
                .isInstanceOf(RuntimeException.class)
                .hasMessage("baz");
        assertThat(root.resolve("step")).doesNotExist();
    }

    @Test
    public void handles_error_in_step_async() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            return CompletableFuture.failedStage(new RuntimeException("baz"));
        }, "source");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step")
                .cause()
                .isInstanceOf(RuntimeException.class)
                .hasMessage("baz");
        assertThat(root.resolve("step")).doesNotExist();
    }

    @Test
    public void can_execute_build_with_skipped_step() throws IOException {
        Path step = Files.createDirectory(root.resolve("step")),
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
        Path step = Files.createDirectory(root.resolve("step")),
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
    public void can_execute_build_with_changed_source_custom_condition() throws IOException {
        Path step = Files.createDirectory(root.resolve("step")),
                checksum = Files.createDirectory(step.resolve("checksum")),
                output = Files.createDirectory(step.resolve("output"));
        Files.writeString(source.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("checksums.source"), HashFunction.read(source, hash));
        Files.writeString(output.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("checksums"), HashFunction.read(output, hash));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", new BuildStep() {
            @Override
            public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
                assertThat(arguments).containsOnlyKeys("source");
                assertThat(arguments.get("source").folder()).isEqualTo(source);
                assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.RETAINED));
                return true;
            }

            @Override
            public CompletionStage<BuildStepResult> apply(Executor executor, BuildStepContext context, SequencedMap<String, BuildStepArgument> arguments) throws IOException {
                assertThat(context.previous()).exists().isEqualTo(output);
                assertThat(context.next()).isNotEqualTo(output).isDirectory();
                assertThat(arguments).containsOnlyKeys("source");
                assertThat(arguments.get("source").folder()).isEqualTo(source);
                assertThat(arguments.get("source").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.RETAINED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file")).content().isEqualTo("foobar");
    }

    @Test
    public void can_execute_build_with_changed_source_and_use_of_context() throws IOException {
        Path step = Files.createDirectory(root.resolve("step")),
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
        Path step = Files.createDirectory(root.resolve("step")),
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
        Files.writeString(source.resolve("file"), "foo");
        Files.writeString(source2.resolve("file"), "bar");
        buildExecutor.addSource("source1", source);
        buildExecutor.addSource("source2", source2);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source1", "source2");
            assertThat(arguments.get("source1").folder()).isEqualTo(source);
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
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).isEmpty();
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
    public void can_execute_nested_resolved() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).isEmpty();
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
        }, identity -> switch (identity) {
            case "source" -> Optional.of("renamed/source");
            case "step1" -> Optional.empty();
            case "step2" -> Optional.of("");
            default -> throw new AssertionError();
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("step/renamed/source", "step");
        assertThat(root.resolve("step").resolve("step2").resolve("output").resolve("file"))
                .content()
                .isEqualTo("foobarqux");
    }

    @Test
    public void fails_on_duplicate_nested_resolve() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).isEmpty();
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
        }, _ -> Optional.of("duplicate"));
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate resolution duplicate");
    }

    @Test
    public void can_execute_nested_parent_reference() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).containsOnlyKeys("../source");
            assertThat(inherited.get("../source")).isEqualTo(source);
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
    public void can_execute_child_reference() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("source", (buildExecutor, inherited) -> {
            assertThat(inherited).isEmpty();
            buildExecutor.addSource("source1", source);
        });
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("source/source1");
            assertThat(arguments.get("source/source1").folder()).isEqualTo(source);
            assertThat(arguments.get("source/source1").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source/source1").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source/source1", "step");
        assertThat(root.resolve("step").resolve("output").resolve("file"))
                .content()
                .isEqualTo("foobar");
    }

    @Test
    public void propagates_nested_error() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).containsOnlyKeys("../source");
            assertThat(inherited.get("../source")).isEqualTo(source);
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
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step/step1")
                .cause()
                .isInstanceOf(RuntimeException.class)
                .hasMessage("baz");
        assertThat(root.resolve("step").resolve("step1").resolve("output")).doesNotExist();
    }

    @Test
    public void can_detect_nested_missing_reference() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).isEmpty();
            buildExecutor.addStep("step1", (_, _, _) -> {
                throw new AssertionError();
            }, "../source");
        });
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Did not inherit: ../source");
        assertThat(root.resolve("step").resolve("step1").resolve("output")).doesNotExist();
    }

    @Test
    public void can_detect_faulty_root_reference() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("source", (buildExecutor, inherited) -> {
            assertThat(inherited).isEmpty();
            buildExecutor.addSource("source1", source);
        });
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).containsOnlyKeys("../source/source1");
            assertThat(inherited.get("../source/source1")).isEqualTo(source);
            buildExecutor.addStep("step1", (_, _, _) -> {
                throw new AssertionError();
            }, "../source");
        }, "source");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Did not inherit: ../source");
        assertThat(root.resolve("step").resolve("step1").resolve("output")).doesNotExist();
    }

    @Test
    public void can_execute_nested_parent_child_reference() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("source", (buildExecutor, inherited) -> {
            assertThat(inherited).isEmpty();
            buildExecutor.addSource("source1", source);
        });
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).containsOnlyKeys("../source/source1");
            assertThat(inherited.get("../source/source1")).isEqualTo(source);
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
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addModule("step", (buildExecutor, inherited) -> {
            assertThat(inherited).containsOnlyKeys("../source");
            assertThat(inherited.get("../source")).isEqualTo(source);
            buildExecutor.addModule("step1", (nestedBuildExecutor, nestedInherited) -> {
                assertThat(nestedInherited).containsOnlyKeys("../../source");
                assertThat(nestedInherited.get("../../source")).isEqualTo(source);
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
    public void can_replace_module() throws IOException {
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
    public void can_prepend_module() throws IOException {
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
    public void can_append_module() throws IOException {
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

    @Test
    public void can_reference_module_step_lazily() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("step1", (module, inherited) -> {
            assertThat(inherited).isEmpty();
            module.addSource("source", source);
            module.addStep("step2", (_, context, arguments) -> {
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
        });
        buildExecutor.addStep("step3", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step1/step2");
            assertThat(arguments.get("step1/step2").folder()).isEqualTo(root.resolve("step1/step2").resolve("output"));
            assertThat(arguments.get("step1/step2").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step1/step2").folder().resolve("file")) + "qux");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step1/step2");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("step1/source", "step1/step2", "step3");
        assertThat(root.resolve("step3").resolve("output").resolve("file")).content().isEqualTo("foobarqux");
    }

    @Test
    public void can_reference_module_step_lazily_multiple() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("step1", (module, inherited) -> {
            assertThat(inherited).isEmpty();
            module.addSource("source", source);
            module.addStep("step2", (_, context, arguments) -> {
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
            module.addStep("step3", (_, context, arguments) -> {
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
        });
        buildExecutor.addStep("step4", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("step1/step2", "step1/step3");
            assertThat(arguments.get("step1/step2").folder()).isEqualTo(root.resolve("step1/step2").resolve("output"));
            assertThat(arguments.get("step1/step2").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            assertThat(arguments.get("step1/step3").folder()).isEqualTo(root.resolve("step1/step3").resolve("output"));
            assertThat(arguments.get("step1/step3").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step1/step2").folder().resolve("file"))
                            + Files.readString(arguments.get("step1/step3").folder().resolve("file")));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step1/step2", "step1/step3");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("step1/source", "step1/step2", "step1/step3", "step4");
        assertThat(root.resolve("step4").resolve("output").resolve("file")).content().isEqualTo("foobarfooqux");
    }

    @Test
    public void can_reference_module_step_lazily_nested() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addModule("step1", (outer, inherited) -> {
            assertThat(inherited).containsOnlyKeys("../source");
            outer.addModule("step2", (inner, innerInherited) -> {
                assertThat(innerInherited).containsOnlyKeys("../../source");
                inner.addStep("step3", (_, context, arguments) -> {
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
        buildExecutor.addStep("step4", (_, context, arguments) -> {
            assertThat(arguments).containsOnlyKeys("step1/step2/step3");
            assertThat(arguments.get("step1/step2/step3").folder()).isEqualTo(root.resolve("step1/step2/step3").resolve("output"));
            assertThat(arguments.get("step1/step2/step3").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("step1/step2/step3").folder().resolve("file")) + "qux");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "step1/step2/step3");
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1/step2/step3", "step4");
        assertThat(root.resolve("step4").resolve("output").resolve("file")).content().isEqualTo("foobarqux");
    }

    @Test
    public void rejects_nonexistent_root_dependency() {
        assertThatThrownBy(() -> buildExecutor.addStep("step1",
                (_, _, _) -> {
                    throw new AssertionError();
                },
                "nonexistent/step"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Did not find dependency: nonexistent/step");
    }

    @Test
    public void rejects_nonexistent_sub_dependency() {
        buildExecutor.addModule("step1", (module, inherited) -> {
            assertThat(inherited).isEmpty();
            module.addStep("step2", (_, _, _) -> CompletableFuture.completedStage(new BuildStepResult(true)));
        });
        buildExecutor.addStep("step2", (_, _, _) -> {
            throw new AssertionError();
        }, "step1/nonexistent");
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run).toCompletableFuture().join())
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step2")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Did not find dependency: step1/nonexistent");
    }

    @Test
    public void rejects_redundant_root_dependency() {
        buildExecutor.addModule("step1", (module, inherited) -> {
            assertThat(inherited).isEmpty();
            module.addStep("step2", (_, _, _) -> CompletableFuture.completedStage(new BuildStepResult(true)));
        });
        assertThatThrownBy(() -> buildExecutor.addStep("step2", (_, _, _) -> {
            throw new AssertionError();
        }, "step1/step2", "step1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redundant root dependency: step1");
    }

    @Test
    public void rejects_redundant_root_dependency_nested() {
        buildExecutor.addModule("step1", (module, inherited) -> {
            assertThat(inherited).isEmpty();
            module.addModule("step2", (nested, nestedInherited) -> {
                assertThat(nestedInherited).isEmpty();
                nested.addStep("step3", (_, _, _) -> CompletableFuture.completedStage(new BuildStepResult(true)));
            });
        });
        assertThatThrownBy(() -> buildExecutor.addStep("step4", (_, _, _) -> {
            throw new AssertionError();
        }, "step1/step2/step3", "step1/step2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Redundant root dependency: step1/step2");
    }

    @Test
    public void can_use_dependency_synonyms() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("other");
            assertThat(arguments.get("other").folder()).isEqualTo(source);
            assertThat(arguments.get("other").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("other").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, new LinkedHashMap<>(Map.of("source", "other")));
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1");
        assertThat(root.resolve("step1").resolve("output").resolve("file")).content().isEqualTo("foobar");
    }

    @Test
    public void can_use_dependency_synonyms_nested() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addModule("step1",  (module, inherited) -> {
            assertThat(inherited).containsOnlyKeys("../intermediate");
            module.addStep("step2", (_, context, arguments) -> {
                assertThat(context.previous()).isNull();
                assertThat(context.next()).isDirectory();
                assertThat(arguments).containsOnlyKeys("../other");
                assertThat(arguments.get("../other").folder()).isEqualTo(source);
                assertThat(arguments.get("../other").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("../other").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, new LinkedHashMap<>(Map.of("../intermediate", "../other")));
        }, new LinkedHashMap<>(Map.of("source", "intermediate")));
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1/step2");
        assertThat(root.resolve("step1/step2").resolve("output").resolve("file")).content().isEqualTo("foobar");
    }

    @Test
    public void can_use_dependency_synonyms_root() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addModule("step1",  (module, inherited) -> {
            assertThat(inherited).containsOnlyKeys("../source");
            module.addStep("step2", (_, context, arguments) -> {
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
        }, "source");
        buildExecutor.addStep("step3", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isDirectory();
            assertThat(arguments).containsOnlyKeys("other1/step2");
            assertThat(arguments.get("other1/step2").folder()).isEqualTo(root.resolve("step1/step2").resolve("output"));
            assertThat(arguments.get("other1/step2").files()).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("other1/step2").folder().resolve("file")) + "qux");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, new LinkedHashMap<>(Map.of("step1", "other1")));
        Map<String, ?> build = buildExecutor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1/step2", "step3");
        assertThat(root.resolve("step3").resolve("output").resolve("file")).content().isEqualTo("foobarqux");
    }

    @Test
    public void rejects_duplicate_synonym() {
        buildExecutor.addStep("step1", (_, _, _) -> {
            throw new AssertionError();
        });
        buildExecutor.addStep("step2", (_, _, _) -> {
            throw new AssertionError();
        });
        assertThatThrownBy(() -> buildExecutor.addStep("step3", (_, _, _) -> {
            throw new AssertionError();
        }, new LinkedHashMap<>(Map.of("step1", "duplicate", "step2", "duplicate"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicated synonym: duplicate");
    }
}
