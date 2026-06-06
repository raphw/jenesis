package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Group;

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
        SequencedProperties leftCoordinates = new SequencedProperties(), rightCoordinates = new SequencedProperties();
        leftCoordinates.setProperty("foo", "");
        rightCoordinates.setProperty("bar", "");
        leftCoordinates.store(left.resolve(BuildStep.IDENTITY));
        rightCoordinates.store(right.resolve(BuildStep.IDENTITY));
        SequencedProperties leftDependencies = new SequencedProperties(), rightDependencies = new SequencedProperties();
        leftDependencies.setProperty("qux", "");
        rightDependencies.setProperty("foo", "");
        rightDependencies.setProperty("baz", "");
        leftDependencies.store(left.resolve(BuildStep.REQUIRES));
        rightDependencies.store(right.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Group(Optional::of).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "left", new BuildStepArgument(
                                left,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)),
                        "right", new BuildStepArgument(
                                right,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        SequencedProperties leftGroup = SequencedProperties.ofFiles(next.resolve(Group.GROUPS + "left.properties"));
        SequencedProperties rightGroup = SequencedProperties.ofFiles(next.resolve(Group.GROUPS + "right.properties"));
        assertThat(leftGroup.stringPropertyNames()).isEmpty();
        assertThat(rightGroup.stringPropertyNames()).containsExactly("left");
        assertThat(result.next()).isTrue();
    }

    @Test
    public void does_not_link_unrelated_groups() throws IOException {
        SequencedProperties leftCoordinates = new SequencedProperties(), rightCoordinates = new SequencedProperties();
        leftCoordinates.setProperty("foo", "");
        rightCoordinates.setProperty("bar", "");
        leftCoordinates.store(left.resolve(BuildStep.IDENTITY));
        rightCoordinates.store(right.resolve(BuildStep.IDENTITY));
        SequencedProperties leftDependencies = new SequencedProperties(), rightDependencies = new SequencedProperties();
        leftDependencies.setProperty("qux", "");
        rightDependencies.setProperty("baz", "");
        leftDependencies.store(left.resolve(BuildStep.REQUIRES));
        rightDependencies.store(right.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Group(Optional::of).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "left", new BuildStepArgument(
                                left,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)),
                        "right", new BuildStepArgument(
                                right,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        SequencedProperties leftGroup = SequencedProperties.ofFiles(next.resolve(Group.GROUPS + "left.properties"));
        SequencedProperties rightGroup = SequencedProperties.ofFiles(next.resolve(Group.GROUPS + "right.properties"));
        assertThat(leftGroup.stringPropertyNames()).isEmpty();
        assertThat(rightGroup.stringPropertyNames()).isEmpty();
        assertThat(result.next()).isTrue();
    }

    @Test
    public void does_not_link_self_referencing_group() throws IOException {
        SequencedProperties leftCoordinates = new SequencedProperties();
        leftCoordinates.setProperty("foo", "");
        leftCoordinates.store(left.resolve(BuildStep.IDENTITY));
        SequencedProperties leftDependencies = new SequencedProperties();
        leftDependencies.setProperty("foo", "");
        leftDependencies.store(left.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Group(Optional::of).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "left", new BuildStepArgument(
                                left,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        SequencedProperties leftGroup = SequencedProperties.ofFiles(next.resolve(Group.GROUPS + "left.properties"));
        assertThat(leftGroup.stringPropertyNames()).isEmpty();
        assertThat(result.next()).isTrue();
    }
}
