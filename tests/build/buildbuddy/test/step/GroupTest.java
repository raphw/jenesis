package build.buildbuddy.test.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.step.Group;
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
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class GroupTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, left, right;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        left = Files.createDirectory(root.resolve("left"));
        right = Files.createDirectory(root.resolve("right"));
    }

    @Test
    public void can_link_related_groups() throws IOException {
        Properties leftCoordinates = new Properties(), rightCoordinates = new Properties();
        leftCoordinates.setProperty("foo", "");
        rightCoordinates.setProperty("bar", "");
        try (Writer writer = Files.newBufferedWriter(left.resolve(BuildStep.COORDINATES))) {
            leftCoordinates.store(writer, null);
        }
        try (Writer writer = Files.newBufferedWriter(right.resolve(BuildStep.COORDINATES))) {
            rightCoordinates.store(writer, null);
        }
        Properties leftDependencies = new Properties(), rightDependencies = new Properties();
        leftDependencies.setProperty("qux", "");
        rightDependencies.setProperty("foo", "");
        rightDependencies.setProperty("baz", "");
        try (Writer writer = Files.newBufferedWriter(left.resolve(BuildStep.DEPENDENCIES))) {
            leftDependencies.store(writer, null);
        }
        try (Writer writer = Files.newBufferedWriter(right.resolve(BuildStep.DEPENDENCIES))) {
            rightDependencies.store(writer, null);
        }
        BuildStepResult result = new Group(Optional::of).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "left", new BuildStepArgument(
                                left,
                                Map.of(
                                        Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED)),
                        "right", new BuildStepArgument(
                                right,
                                Map.of(
                                        Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        Properties leftGroup = new Properties(), rightGroup = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(Group.GROUPS + "left.properties"))) {
            leftGroup.load(reader);
        }
        try (Reader reader = Files.newBufferedReader(next.resolve(Group.GROUPS + "right.properties"))) {
            rightGroup.load(reader);
        }
        assertThat(leftGroup.stringPropertyNames()).isEmpty();
        assertThat(rightGroup.stringPropertyNames()).containsExactly("left");
        assertThat(result.next()).isTrue();
    }

    @Test
    public void does_not_link_unrelated_groups() throws IOException {
        Properties leftCoordinates = new Properties(), rightCoordinates = new Properties();
        leftCoordinates.setProperty("foo", "");
        rightCoordinates.setProperty("bar", "");
        try (Writer writer = Files.newBufferedWriter(left.resolve(BuildStep.COORDINATES))) {
            leftCoordinates.store(writer, null);
        }
        try (Writer writer = Files.newBufferedWriter(right.resolve(BuildStep.COORDINATES))) {
            rightCoordinates.store(writer, null);
        }
        Properties leftDependencies = new Properties(), rightDependencies = new Properties();
        leftDependencies.setProperty("qux", "");
        rightDependencies.setProperty("baz", "");
        try (Writer writer = Files.newBufferedWriter(left.resolve(BuildStep.DEPENDENCIES))) {
            leftDependencies.store(writer, null);
        }
        try (Writer writer = Files.newBufferedWriter(right.resolve(BuildStep.DEPENDENCIES))) {
            rightDependencies.store(writer, null);
        }
        BuildStepResult result = new Group(Optional::of).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "left", new BuildStepArgument(
                                left,
                                Map.of(
                                        Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED)),
                        "right", new BuildStepArgument(
                                right,
                                Map.of(
                                        Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        Properties leftGroup = new Properties(), rightGroup = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(Group.GROUPS + "left.properties"))) {
            leftGroup.load(reader);
        }
        try (Reader reader = Files.newBufferedReader(next.resolve(Group.GROUPS + "right.properties"))) {
            rightGroup.load(reader);
        }
        assertThat(leftGroup.stringPropertyNames()).isEmpty();
        assertThat(rightGroup.stringPropertyNames()).isEmpty();
        assertThat(result.next()).isTrue();
    }
}
