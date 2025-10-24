package build.jenesis.test.project;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.project.MultiProjectDependencies;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class MultiProjectDependenciesTest {

    @TempDir
    private Path root, target;
    private Path previous, next, supplement, module, dependency;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        module = Files.createDirectory(root.resolve("module"));
        dependency = Files.createDirectory(root.resolve("dependency"));
    }

    @Test
    public void can_assign_coordinate_target_dependencies() throws IOException, NoSuchAlgorithmException {
        Properties dependencies = new Properties();
        dependencies.setProperty("baz", "");
        try (Writer writer = Files.newBufferedWriter(module.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.store(writer, null);
        }
        Path file = target.resolve("file");
        Files.writeString(file, "qux");
        Properties coordinates = new Properties();
        coordinates.setProperty("baz", file.toString());
        try (Writer writer = Files.newBufferedWriter(dependency.resolve(BuildStep.COORDINATES))) {
            coordinates.store(writer, null);
        }
        BuildStepResult result = new MultiProjectDependencies("SHA256", "foo"::equals).apply(
                        Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED)),
                                "bar", new BuildStepArgument(
                                        dependency,
                                        Map.of(Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.DEPENDENCIES))) {
            properties.load(reader);
        }
        assertThat(properties.stringPropertyNames()).containsExactly("baz");
        assertThat(properties.getProperty("baz")).isEqualTo("SHA256/" + HexFormat.of().formatHex(MessageDigest
                .getInstance("SHA256")
                .digest("qux".getBytes())));
    }
}
