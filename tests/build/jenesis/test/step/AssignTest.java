package build.jenesis.test.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Assign;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class AssignTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, argument;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        argument = Files.createDirectory(root.resolve("argument"));
    }

    @Test
    public void can_assign_all_dependencies() throws IOException {
        Properties properties = new SequencedProperties();
        properties.setProperty("foo", "");
        Path passthroughArtifact = argument.resolve("passthrough.jar");
        Files.writeString(passthroughArtifact, "other");
        properties.setProperty("bar", argument.relativize(passthroughArtifact).toString());
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            properties.store(writer, null);
        }
        Files.writeString(Files.createDirectory(argument.resolve(BuildStep.ARTIFACTS)).resolve("artifact"), "baz");
        BuildStepResult result = new Assign().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.ARTIFACTS + "foo"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        Properties coordinates = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.IDENTITY))) {
            coordinates.load(reader);
        }
        assertThat(coordinates).containsExactly(
                Map.entry("foo", next.relativize(argument.resolve(BuildStep.ARTIFACTS).resolve("artifact")).toString()),
                Map.entry("bar", next.relativize(passthroughArtifact).toString()));
        assertThat(coordinates.getProperty("foo")).doesNotStartWith("/");
        assertThat(coordinates.getProperty("bar")).doesNotStartWith("/");
    }

    @Test
    public void can_assign_dependencies() throws IOException {
        Properties properties = new SequencedProperties();
        properties.setProperty("foo", "");
        Path passthroughArtifact = argument.resolve("passthrough.jar");
        Files.writeString(passthroughArtifact, "other");
        properties.setProperty("bar", argument.relativize(passthroughArtifact).toString());
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            properties.store(writer, null);
        }
        Files.writeString(Files.createDirectory(argument.resolve(BuildStep.ARTIFACTS)).resolve("artifact"), "baz");
        BuildStepResult result = new Assign((coordinates, files) -> {
            assertThat(coordinates).containsExactly("foo");
            assertThat(files).containsExactly(argument.resolve(BuildStep.ARTIFACTS).resolve("artifact"));
            return Map.of(
                    "foo", argument.resolve(BuildStep.ARTIFACTS).resolve("artifact"),
                    "qux", argument.resolve(BuildStep.ARTIFACTS).resolve("artifact"));
        }).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.ARTIFACTS + "foo"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        Properties coordinates = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.IDENTITY))) {
            coordinates.load(reader);
        }
        Path artifact = argument.resolve(BuildStep.ARTIFACTS).resolve("artifact");
        assertThat(coordinates).containsExactly(
                Map.entry("foo", next.relativize(artifact).toString()),
                Map.entry("bar", next.relativize(passthroughArtifact).toString()),
                Map.entry("qux", next.relativize(artifact).toString()));
        coordinates.stringPropertyNames().forEach(name ->
                assertThat(coordinates.getProperty(name)).doesNotStartWith("/"));
    }
}
