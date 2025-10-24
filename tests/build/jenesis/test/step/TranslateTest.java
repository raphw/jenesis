package build.jenesis.test.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Translate;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class TranslateTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, dependencies;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectory(root.resolve("dependencies"));
    }

    @Test
    public void can_translate_dependencies() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "foobar");
        properties.setProperty("bar/baz", "quxbaz");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Translate(Map.of(
                "foo",
                coordinate -> "translated/" + coordinate)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.DEPENDENCIES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("translated/qux", "bar/baz");
        assertThat(dependencies.getProperty("translated/qux")).isEqualTo("foobar");
        assertThat(dependencies.getProperty("bar/baz")).isEqualTo("quxbaz");
    }
}
