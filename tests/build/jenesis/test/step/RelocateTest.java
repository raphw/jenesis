package build.jenesis.test.step;

import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Relocate;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class RelocateTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, original;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        original = Files.createDirectory(root.resolve("original"));
    }

    @Test
    public void links_files_for_which_placement_returns_a_target() throws IOException {
        Files.writeString(original.resolve("first.jar"), "first");
        Files.writeString(original.resolve("second.jar"), "second");
        Files.writeString(original.resolve("readme.txt"), "ignored");

        BuildStepResult result = new Relocate(file -> {
            String name = file.getFileName().toString();
            if (name.endsWith(".jar")) {
                return Optional.of(Path.of("staged", name));
            }
            return Optional.empty();
        }).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("original", new BuildStepArgument(
                                original,
                                Map.of(
                                        Path.of("first.jar"), ChecksumStatus.ADDED,
                                        Path.of("second.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("staged").resolve("first.jar")).hasContent("first");
        assertThat(next.resolve("staged").resolve("second.jar")).hasContent("second");
        assertThat(next.resolve("staged").resolve("readme.txt")).doesNotExist();
    }

    @Test
    public void placement_walks_nested_folders() throws IOException {
        Path nested = Files.createDirectories(original.resolve("a").resolve("b"));
        Files.writeString(nested.resolve("deep.jar"), "deep");

        BuildStepResult result = new Relocate(
                file -> Optional.of(Path.of(file.getFileName().toString()))).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("original", new BuildStepArgument(
                                original,
                                Map.of(Path.of("a/b/deep.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("deep.jar")).hasContent("deep");
    }

    @Test
    public void placement_merges_multiple_predecessors() throws IOException {
        Path otherFolder = Files.createDirectory(root.resolve("other-original"));
        Files.writeString(original.resolve("a.jar"), "a");
        Files.writeString(otherFolder.resolve("b.jar"), "b");

        BuildStepResult result = new Relocate(
                file -> Optional.of(Path.of(file.getFileName().toString()))).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "first", new BuildStepArgument(
                                        original,
                                        Map.of(Path.of("a.jar"), ChecksumStatus.ADDED)),
                                "second", new BuildStepArgument(
                                        otherFolder,
                                        Map.of(Path.of("b.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("a.jar")).hasContent("a");
        assertThat(next.resolve("b.jar")).hasContent("b");
    }

    @Test
    public void placement_skips_when_function_returns_empty() throws IOException {
        Files.writeString(original.resolve("ignored.jar"), "ignored");

        BuildStepResult result = new Relocate(file -> Optional.empty()).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("original", new BuildStepArgument(
                                original,
                                Map.of(Path.of("ignored.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        try (Stream<Path> contents = Files.list(next)) {
            assertThat(contents).isEmpty();
        }
    }

    @Test
    public void placement_with_prefixes_walks_only_listed_subtrees() throws IOException {
        Path included = Files.createDirectories(original.resolve("included"));
        Path excluded = Files.createDirectories(original.resolve("excluded"));
        Files.writeString(included.resolve("kept.jar"), "kept");
        Files.writeString(excluded.resolve("dropped.jar"), "dropped");

        BuildStepResult result = new Relocate(
                file -> Optional.of(Path.of(file.getFileName().toString())),
                Set.of(Path.of("included"))).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("original", new BuildStepArgument(
                                original,
                                Map.of(
                                        Path.of("included/kept.jar"), ChecksumStatus.ADDED,
                                        Path.of("excluded/dropped.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("kept.jar")).hasContent("kept");
        assertThat(next.resolve("dropped.jar")).doesNotExist();
    }

    @Test
    public void placement_with_prefixes_short_circuits_when_changes_outside() {
        Relocate relocate = new Relocate(file -> Optional.of(Path.of(file.getFileName().toString())),
                Set.of(Path.of("included")));
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>(Map.of(
                "original", new BuildStepArgument(
                        original,
                        Map.of(Path.of("excluded/dropped.jar"), ChecksumStatus.ALTERED))));
        assertThat(relocate.shouldRun(arguments)).isFalse();
    }

    @Test
    public void placement_with_prefixes_runs_when_changes_inside() {
        Relocate relocate = new Relocate(file -> Optional.of(Path.of(file.getFileName().toString())),
                Set.of(Path.of("included")));
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>(Map.of(
                "original", new BuildStepArgument(
                        original,
                        Map.of(Path.of("included/kept.jar"), ChecksumStatus.ALTERED))));
        assertThat(relocate.shouldRun(arguments)).isTrue();
    }
}
