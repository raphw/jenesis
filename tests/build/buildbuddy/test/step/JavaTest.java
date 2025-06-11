package build.buildbuddy.test.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.step.Java;
import build.buildbuddy.step.Javac;
import sample.Sample;

import module java.base;
import module org.junit.jupiter.api;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class JavaTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, classes;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        classes = Files.createDirectory(root.resolve("classes"));
    }

    @Test
    public void can_execute_java() throws IOException {
        Path folder = Files.createDirectories(classes.resolve(Javac.CLASSES + "sample"));
        try (InputStream input = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(input), folder.resolve("Sample.class"));
        }
        BuildStepResult result = Java.of("sample.Sample").apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("classes", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.class"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(supplement.resolve("output")).content().isEqualTo("Hello world!");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }
}
