package build.jenesis.test.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Stage;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class StageTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement;

    @BeforeEach
    public void setUp() throws IOException {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
    }

    @Test
    public void can_stage_artifacts_grouped_by_coordinate() throws IOException {
        Path assignFolder = Files.createDirectory(root.resolve("assign"));
        Path artifacts = Files.createDirectory(root.resolve("artifacts"));
        Path mainJar = Files.writeString(artifacts.resolve("classes.jar"), "main");
        Path mavenJar = Files.writeString(artifacts.resolve("foo.jar"), "foo");
        Properties properties = new SequencedProperties();
        properties.setProperty("module/build.jenesis", mainJar.toString());
        properties.setProperty("maven/com.example/foo/jar/1.0.0", mavenJar.toString());
        try (Writer writer = Files.newBufferedWriter(assignFolder.resolve(BuildStep.COORDINATES))) {
            properties.store(writer, null);
        }

        BuildStepResult result = new Stage().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("build/module/foo/assign", new BuildStepArgument(
                                assignFolder,
                                Map.of(Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("build.jenesis").resolve("classes.jar")).hasContent("main");
        assertThat(next.resolve("com.example/foo/jar/1.0.0/foo.jar")).hasContent("foo");
    }

    @Test
    public void merges_multiple_assign_predecessors() throws IOException {
        Path artifacts = Files.createDirectory(root.resolve("artifacts"));
        Path firstJar = Files.writeString(artifacts.resolve("first.jar"), "first");
        Path secondJar = Files.writeString(artifacts.resolve("second.jar"), "second");

        Path firstAssign = Files.createDirectory(root.resolve("first-assign"));
        Properties firstProps = new SequencedProperties();
        firstProps.setProperty("module/first", firstJar.toString());
        try (Writer writer = Files.newBufferedWriter(firstAssign.resolve(BuildStep.COORDINATES))) {
            firstProps.store(writer, null);
        }
        Path secondAssign = Files.createDirectory(root.resolve("second-assign"));
        Properties secondProps = new SequencedProperties();
        secondProps.setProperty("module/second", secondJar.toString());
        try (Writer writer = Files.newBufferedWriter(secondAssign.resolve(BuildStep.COORDINATES))) {
            secondProps.store(writer, null);
        }

        BuildStepResult result = new Stage().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "build/module/first/assign", new BuildStepArgument(
                                        firstAssign,
                                        Map.of(Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED)),
                                "build/module/second/assign", new BuildStepArgument(
                                        secondAssign,
                                        Map.of(Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("first").resolve("first.jar")).hasContent("first");
        assertThat(next.resolve("second").resolve("second.jar")).hasContent("second");
    }

    @Test
    public void ignores_predecessors_not_ending_in_assign() throws IOException {
        Path other = Files.createDirectory(root.resolve("other"));
        Path artifacts = Files.createDirectory(root.resolve("artifacts"));
        Path jar = Files.writeString(artifacts.resolve("foo.jar"), "foo");
        Properties properties = new SequencedProperties();
        properties.setProperty("module/foo", jar.toString());
        try (Writer writer = Files.newBufferedWriter(other.resolve(BuildStep.COORDINATES))) {
            properties.store(writer, null);
        }

        BuildStepResult result = new Stage().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("build/module/foo/build", new BuildStepArgument(
                                other,
                                Map.of(Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        try (Stream<Path> contents = Files.list(next)) {
            assertThat(contents).isEmpty();
        }
    }

    @Test
    public void skips_unresolved_and_missing_coordinates() throws IOException {
        Path assignFolder = Files.createDirectory(root.resolve("assign"));
        Properties properties = new SequencedProperties();
        properties.setProperty("module/unresolved", "");
        properties.setProperty("module/missing", root.resolve("does-not-exist.jar").toString());
        try (Writer writer = Files.newBufferedWriter(assignFolder.resolve(BuildStep.COORDINATES))) {
            properties.store(writer, null);
        }

        BuildStepResult result = new Stage().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("build/module/foo/assign", new BuildStepArgument(
                                assignFolder,
                                Map.of(Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        try (Stream<Path> contents = Files.list(next)) {
            assertThat(contents).isEmpty();
        }
    }
}
