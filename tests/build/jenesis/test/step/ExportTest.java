package build.jenesis.test.step;

import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Export;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class ExportTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, source, target;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        source = Files.createDirectory(root.resolve("source"));
        target = Files.createDirectory(root.resolve("target"));
    }

    @Test
    public void copies_files_through_placement() throws IOException {
        Files.writeString(source.resolve("kept.jar"), "kept");
        Files.writeString(source.resolve("ignored.txt"), "ignored");

        Export export = new Export(target, (Function<Path, Optional<Path>> & Serializable) (file -> {
            String name = file.getFileName().toString();
            return name.endsWith(".jar")
                    ? Optional.of(Path.of("staged", name))
                    : Optional.empty();
        }));
        BuildStepResult result = export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("kept.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(target.resolve("staged/kept.jar")).hasContent("kept");
        assertThat(target.resolve("staged/ignored.txt")).doesNotExist();
    }

    @Test
    public void replaces_existing_files() throws IOException {
        Files.writeString(source.resolve("artifact.jar"), "new");
        Files.writeString(target.resolve("artifact.jar"), "stale");

        Export export = new Export(target,
                (Function<Path, Optional<Path>> & Serializable) (file -> Optional.of(Path.of(file.getFileName().toString()))));
        export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("artifact.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(target.resolve("artifact.jar")).hasContent("new");
    }

    @Test
    public void should_run_returns_true() {
        Export export = new Export(target,
                (Function<Path, Optional<Path>> & Serializable) (_ -> Optional.empty()));
        assertThat(export.shouldRun(new LinkedHashMap<>())).isTrue();
    }

    @Test
    public void invokes_finalizer_with_target_after_copy() throws IOException {
        Files.writeString(source.resolve("artifact.jar"), "data");

        Path marker = root.resolve("finalizer-called.txt");
        Export export = new Export(target,
                (Function<Path, Optional<Path>> & Serializable) (file -> Optional.of(Path.of(file.getFileName().toString()))),
                (Consumer<Path> & Serializable) (received -> {
                    try {
                        Files.writeString(marker, received.toString());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
        export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("artifact.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(target.resolve("artifact.jar")).hasContent("data");
        assertThat(marker).hasContent(target.toString());
    }

    @Test
    public void skips_finalizer_when_target_does_not_exist() throws IOException {
        Path absentTarget = root.resolve("missing");
        AtomicBoolean called = new AtomicBoolean();
        Export export = new Export(absentTarget,
                (Function<Path, Optional<Path>> & Serializable) (_ -> Optional.empty()),
                (Consumer<Path> & Serializable) (_ -> called.set(true)));
        export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of()))))
                .toCompletableFuture()
                .join();
        assertThat(called).isFalse();
    }
}
