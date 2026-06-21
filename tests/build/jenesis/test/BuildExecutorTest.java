package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildExecutorException;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.HashDigestFunction;
import build.jenesis.HashFunction;
import build.jenesis.SequencedProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BuildExecutorTest implements Serializable {

    private static Map<Path, ChecksumStatus> statuses(Map<Path, Checksum> files) {
        Map<Path, ChecksumStatus> statuses = new LinkedHashMap<>();
        files.forEach((path, checksum) -> statuses.put(path, checksum.status()));
        return statuses;
    }

    @TempDir
    private Path root, source, source2;
    private transient HashDigestFunction hash;
    private transient BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        hash = new HashDigestFunction("MD5");
        buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                hash,
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                "foo/bar does not match pattern: [a-zA-Z0-9._%-]+");
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
        HashFunction.write(checksum.resolve("argument.source.properties"), HashFunction.read(source, hash, Runnable::run));
        Files.writeString(output.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("output.properties"), HashFunction.read(output, hash, Runnable::run));
        BuildStep buildStep = (_, _, _) -> {
            throw new AssertionError("Did not expect that step is executed");
        };
        SequencedProperties stepProperties = new SequencedProperties();
        stepProperties.setProperty("serialization",
                HexFormat.of().formatHex(BuildStepHashFunction.ofSerializationDigest("MD5").hash(buildStep)));
        stepProperties.store(checksum.resolve("step.properties"));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", buildStep, "source");
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
        HashFunction.write(checksum.resolve("argument.source.properties"), HashFunction.read(source, _ -> new byte[0], Runnable::run));
        Files.writeString(output.resolve("file"), "bar");
        HashFunction.write(checksum.resolve("output.properties"), HashFunction.read(output, hash, Runnable::run));
        BuildStep buildStep = (_, context, arguments) -> {
            assertThat(context.previous()).exists().isEqualTo(output);
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ALTERED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        };
        SequencedProperties stepProperties = new SequencedProperties();
        stepProperties.setProperty("serialization",
                HexFormat.of().formatHex(BuildStepHashFunction.ofSerializationDigest("MD5").hash(buildStep)));
        stepProperties.store(checksum.resolve("step.properties"));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", buildStep, "source");
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
        HashFunction.write(checksum.resolve("argument.source.properties"), HashFunction.read(source, hash, Runnable::run));
        Files.writeString(output.resolve("file"), "foo");
        HashFunction.write(checksum.resolve("output.properties"), HashFunction.read(output, hash, Runnable::run));
        BuildStep buildStep = new BuildStep() {
            @Override
            public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
                assertThat(arguments).containsOnlyKeys("source");
                assertThat(arguments.get("source").folder()).isEqualTo(source);
                assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.RETAINED));
                return true;
            }

            @Override
            public CompletionStage<BuildStepResult> apply(Executor executor, BuildStepContext context, SequencedMap<String, BuildStepArgument> arguments) throws IOException {
                assertThat(context.previous()).exists().isEqualTo(output);
                assertThat(context.next()).isNotEqualTo(output).isDirectory();
                assertThat(arguments).containsOnlyKeys("source");
                assertThat(arguments.get("source").folder()).isEqualTo(source);
                assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.RETAINED));
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }
        };
        SequencedProperties stepProperties = new SequencedProperties();
        stepProperties.setProperty("serialization",
                HexFormat.of().formatHex(BuildStepHashFunction.ofSerializationDigest("MD5").hash(buildStep)));
        stepProperties.store(checksum.resolve("step.properties"));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", buildStep, "source");
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
        HashFunction.write(checksum.resolve("argument.source.properties"), HashFunction.read(source, _ -> new byte[0], Runnable::run));
        Files.writeString(output.resolve("file"), "qux");
        HashFunction.write(checksum.resolve("output.properties"), HashFunction.read(output, hash, Runnable::run));
        BuildStep buildStep = (_, context, arguments) -> {
            assertThat(context.previous()).exists().isEqualTo(output);
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ALTERED));
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(false));
        };
        SequencedProperties stepProperties = new SequencedProperties();
        stepProperties.setProperty("serialization",
                HexFormat.of().formatHex(BuildStepHashFunction.ofSerializationDigest("MD5").hash(buildStep)));
        stepProperties.store(checksum.resolve("step.properties"));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", buildStep, "source");
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
        HashFunction.write(checksum.resolve("argument.source.properties"), HashFunction.read(source, hash, Runnable::run));
        Files.writeString(output.resolve("file"), "qux");
        HashFunction.write(checksum.resolve("output.properties"), HashFunction.read(output, _ -> new byte[0], Runnable::run));
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step", (_, context, arguments) -> {
            assertThat(context.previous()).isNull();
            assertThat(context.next()).isNotEqualTo(output).isDirectory();
            assertThat(arguments).containsOnlyKeys("source");
            assertThat(arguments.get("source").folder()).isEqualTo(source);
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            assertThat(statuses(arguments.get("source2").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            assertThat(statuses(arguments.get("step2").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("../source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source/source1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("../source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("../source/source1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                    assertThat(statuses(arguments.get("../../source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step0").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step1").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step0").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step1/step2").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step1/step2").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
            assertThat(arguments.get("step1/step3").folder()).isEqualTo(root.resolve("step1/step3").resolve("output"));
            assertThat(statuses(arguments.get("step1/step3").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                    assertThat(statuses(arguments.get("../../source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("step1/step2/step3").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("other").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("../other").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
                assertThat(statuses(arguments.get("../source").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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
            assertThat(statuses(arguments.get("other1/step2").files())).isEqualTo(Map.of(Path.of("file"), ChecksumStatus.ADDED));
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

    @Test
    public void can_execute_single_selector_with_transitive_dependency() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (_, context, arguments) -> {
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step2", (_, _, _) -> {
            throw new AssertionError("step2 should not run");
        }, "step1");
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "step1").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1");
        assertThat(root.resolve("step1").resolve("output").resolve("file")).content().isEqualTo("foobar");
        assertThat(root.resolve("step2")).doesNotExist();
    }

    @Test
    public void can_execute_multiple_selectors() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addStep("step1", (_, context, arguments) -> {
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step2", (_, context, arguments) -> {
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source").folder().resolve("file")) + "qux");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source");
        buildExecutor.addStep("step3", (_, _, _) -> {
            throw new AssertionError("step3 should not run");
        }, "step1");
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "step1", "step2").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "step1", "step2");
        assertThat(root.resolve("step3")).doesNotExist();
    }

    @Test
    public void can_execute_selector_skipping_unrelated_branch() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        Files.writeString(source2.resolve("file"), "bar");
        buildExecutor.addSource("source1", source);
        buildExecutor.addSource("source2", source2);
        buildExecutor.addStep("branchA", (_, context, arguments) -> {
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("source1").folder().resolve("file")) + "A");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "source1");
        buildExecutor.addStep("branchB", (_, _, _) -> {
            throw new AssertionError("branchB should not run");
        }, "source2");
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "branchA").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source1", "branchA");
        assertThat(root.resolve("branchB")).doesNotExist();
        assertThat(root.resolve("source2")).doesNotExist();
    }

    @Test
    public void rejects_unknown_selector() {
        buildExecutor.addStep("step1", (_, _, _) -> {
            throw new AssertionError();
        });
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run, "nonexistent").toCompletableFuture().join())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown selector: nonexistent");
    }

    @Test
    public void can_execute_nested_selector_with_sub_step() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("module", (module, _) -> {
            module.addSource("source", source);
            module.addStep("step1", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
            module.addStep("step2", (_, _, _) -> {
                throw new AssertionError("step2 should not run");
            }, "step1");
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "module/step1").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("module/source", "module/step1");
        assertThat(root.resolve("module").resolve("step2")).doesNotExist();
    }

    @Test
    public void can_execute_bare_module_selector_runs_entire_subtree() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("module", (module, _) -> {
            module.addSource("source", source);
            module.addStep("step1", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
            module.addStep("step2", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("step1").folder().resolve("file")) + "qux");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "step1");
        });
        buildExecutor.addStep("other", (_, _, _) -> {
            throw new AssertionError("other should not run");
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "module").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("module/source", "module/step1", "module/step2");
        assertThat(root.resolve("other")).doesNotExist();
    }

    @Test
    public void can_execute_deeply_nested_selector() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("source", source);
        buildExecutor.addModule("outer", (outer, _) -> {
            outer.addModule("inner", (inner, _) -> {
                inner.addStep("leaf", (_, context, arguments) -> {
                    Files.writeString(
                            context.next().resolve("file"),
                            Files.readString(arguments.get("../../source").folder().resolve("file")) + "bar");
                    return CompletableFuture.completedStage(new BuildStepResult(true));
                }, "../../source");
                inner.addStep("sibling", (_, _, _) -> {
                    throw new AssertionError("sibling should not run");
                });
            }, "../source");
        }, "source");
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "outer/inner/leaf").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("source", "outer/inner/leaf");
        assertThat(root.resolve("outer").resolve("inner").resolve("sibling")).doesNotExist();
    }

    @Test
    public void rejects_unknown_nested_selector() {
        buildExecutor.addModule("module", (module, _) -> module.addStep("step1", (_, _, _) -> {
            throw new AssertionError();
        }));
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run, "module/nonexistent")
                .toCompletableFuture().join())
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute module")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown selector: nonexistent");
    }

    @Test
    public void can_execute_wildcard_selector_across_modules() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("modA", (module, _) -> {
            module.addSource("source", source);
            module.addStep("step", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "A");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
            module.addStep("other", (_, _, _) -> {
                throw new AssertionError("modA/other should not run");
            });
        });
        buildExecutor.addModule("modB", (module, _) -> {
            module.addSource("source", source);
            module.addStep("step", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "B");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
        });
        buildExecutor.addModule("modC", (module, _) -> module.addStep("different", (_, _, _) -> {
            throw new AssertionError("modC/different should not run");
        }));
        Map<String, ?> build = buildExecutor.execute(Runnable::run, ":/step").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("modA/source", "modA/step", "modB/source", "modB/step");
    }

    @Test
    public void can_execute_any_depth_wildcard_selector() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("outer", (outer, _) -> outer.addModule("inner", (inner, _) -> {
            inner.addSource("source", source);
            inner.addStep("leaf", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
            inner.addStep("other", (_, _, _) -> {
                throw new AssertionError("other should not run");
            });
        }));
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "::/leaf").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("outer/inner/source", "outer/inner/leaf");
    }

    @Test
    public void any_depth_wildcard_matches_at_multiple_depths() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addSource("rootSource", source);
        buildExecutor.addStep("leaf", (_, context, arguments) -> {
            Files.writeString(
                    context.next().resolve("file"),
                    Files.readString(arguments.get("rootSource").folder().resolve("file")) + "0");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }, "rootSource");
        buildExecutor.addModule("modA", (module, _) -> {
            module.addSource("sourceA", source);
            module.addStep("leaf", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("sourceA").folder().resolve("file")) + "A");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "sourceA");
        });
        buildExecutor.addModule("modB", (module, _) -> module.addModule("nested", (nested, _) -> {
            nested.addSource("sourceB", source);
            nested.addStep("leaf", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("sourceB").folder().resolve("file")) + "B");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "sourceB");
        }));
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "::/leaf").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys(
                "rootSource",
                "leaf",
                "modA/sourceA",
                "modA/leaf",
                "modB/nested/sourceB",
                "modB/nested/leaf");
    }

    @Test
    public void can_execute_intermediate_wildcard_selector() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("module", (outer, _) -> {
            outer.addModule("childA", (child, _) -> {
                child.addSource("source", source);
                child.addStep("leaf", (_, context, arguments) -> {
                    Files.writeString(
                            context.next().resolve("file"),
                            Files.readString(arguments.get("source").folder().resolve("file")) + "A");
                    return CompletableFuture.completedStage(new BuildStepResult(true));
                }, "source");
            });
            outer.addModule("childB", (child, _) -> {
                child.addSource("source", source);
                child.addStep("leaf", (_, context, arguments) -> {
                    Files.writeString(
                            context.next().resolve("file"),
                            Files.readString(arguments.get("source").folder().resolve("file")) + "B");
                    return CompletableFuture.completedStage(new BuildStepResult(true));
                }, "source");
            });
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "module/:/leaf")
                .toCompletableFuture().join();
        assertThat(build).containsOnlyKeys(
                "module/childA/source",
                "module/childA/leaf",
                "module/childB/source",
                "module/childB/leaf");
    }

    @Test
    public void wildcard_skips_modules_without_match() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("modA", (module, _) -> {
            module.addSource("source", source);
            module.addStep("leaf", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "A");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
        });
        buildExecutor.addModule("modB", (module, _) -> module.addStep("different", (_, _, _) -> {
            throw new AssertionError("modB/different should not run");
        }));
        Map<String, ?> build = buildExecutor.execute(Runnable::run, ":/leaf").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("modA/source", "modA/leaf");
    }

    @Test
    public void wildcard_with_no_match_runs_nothing() throws IOException {
        buildExecutor.addModule("module", (inner, _) -> inner.addStep("step", (_, _, _) -> {
            throw new AssertionError("step should not run");
        }));
        Map<String, ?> build = buildExecutor.execute(Runnable::run, ":/nonexistent")
                .toCompletableFuture().join();
        assertThat(build).isEmpty();
    }

    @Test
    public void wildcard_skips_top_level_leaf_step() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addStep("leaf", (_, _, _) -> {
            throw new AssertionError("top-level leaf should not run when wildcard descends");
        });
        buildExecutor.addModule("module", (inner, _) -> {
            inner.addSource("source", source);
            inner.addStep("step", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run, ":/step").toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("module/source", "module/step");
    }

    @Test
    public void rejects_triple_colon_as_invalid_path_segment() {
        buildExecutor.addStep("step", (_, _, _) -> {
            throw new AssertionError();
        });
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run, ":::/step")
                .toCompletableFuture().join())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown selector: :::/step");
    }

    @Test
    public void rejects_strict_sub_selector_on_leaf_step() {
        buildExecutor.addStep("step1", (_, _, _) -> {
            throw new AssertionError();
        });
        assertThatThrownBy(() -> buildExecutor.execute(Runnable::run, "step1/extra")
                .toCompletableFuture().join())
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute step1")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown selector: extra");
    }

    @Test
    public void bare_selector_overrides_sub_path() throws IOException {
        Files.writeString(source.resolve("file"), "foo");
        buildExecutor.addModule("module", (module, _) -> {
            module.addSource("source", source);
            module.addStep("step1", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("source").folder().resolve("file")) + "bar");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "source");
            module.addStep("step2", (_, context, arguments) -> {
                Files.writeString(
                        context.next().resolve("file"),
                        Files.readString(arguments.get("step1").folder().resolve("file")) + "qux");
                return CompletableFuture.completedStage(new BuildStepResult(true));
            }, "step1");
        });
        Map<String, ?> build = buildExecutor.execute(Runnable::run, "module/step1", "module")
                .toCompletableFuture().join();
        assertThat(build).containsOnlyKeys("module/source", "module/step1", "module/step2");
    }
}
