package build.buildbuddy.test.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.SequencedProperties;
import build.buildbuddy.step.Assign;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

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
        properties.setProperty("bar", "qux");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.COORDINATES))) {
            properties.store(writer, null);
        }
        Files.writeString(Files.createDirectory(argument.resolve(BuildStep.ARTIFACTS)).resolve("artifact"), "baz");
        BuildStepResult result = new Assign().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.ARTIFACTS + "foo"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        Properties coordinates = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.COORDINATES))) {
            coordinates.load(reader);
        }
        assertThat(coordinates).containsExactly(
                Map.entry("foo", argument.resolve(BuildStep.ARTIFACTS).resolve("artifact").toString()),
                Map.entry("bar", argument.resolve(BuildStep.ARTIFACTS).resolve("artifact").toString()));
    }

    @Test
    public void can_assign_dependencies() throws IOException {
        Properties properties = new SequencedProperties();
        properties.setProperty("foo", "");
        properties.setProperty("bar", "qux");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.COORDINATES))) {
            properties.store(writer, null);
        }
        Files.writeString(Files.createDirectory(argument.resolve(BuildStep.ARTIFACTS)).resolve("artifact"), "baz");
        BuildStepResult result = new Assign((coordinates, files) -> {
            assertThat(coordinates).containsExactly("foo", "bar");
            assertThat(files).containsExactly(argument.resolve(BuildStep.ARTIFACTS).resolve("artifact"));
            return Map.of(
                    "foo", argument.resolve(BuildStep.ARTIFACTS).resolve("artifact"),
                    "qux", argument.resolve(BuildStep.ARTIFACTS).resolve("artifact"));
        }).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.ARTIFACTS + "foo"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        Properties coordinates = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.COORDINATES))) {
            coordinates.load(reader);
        }
        assertThat(coordinates).containsExactly(
                Map.entry("foo", argument.resolve(BuildStep.ARTIFACTS).resolve("artifact").toString()),
                Map.entry("bar", "qux"),
                Map.entry("qux", argument.resolve(BuildStep.ARTIFACTS).resolve("artifact").toString()));
    }
}
