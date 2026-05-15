package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.DependencyScope;

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
    public void sibling_artifact_dependency_writes_empty_value() throws IOException {
        Properties dependencies = new Properties();
        dependencies.setProperty("baz", "");
        try (Writer writer = Files.newBufferedWriter(module.resolve(BuildStep.REQUIRES))) {
            dependencies.store(writer, null);
        }
        Path file = target.resolve("file");
        Files.writeString(file, "qux");
        Properties coordinates = new Properties();
        coordinates.setProperty("baz", file.toString());
        try (Writer writer = Files.newBufferedWriter(dependency.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        BuildStepResult result = new MultiProjectDependencies("foo"::equals, DependencyScope.COMPILE).apply(
                        Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)),
                                "bar", new BuildStepArgument(
                                        dependency,
                                        Map.of(Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.REQUIRES))) {
            properties.load(reader);
        }
        assertThat(properties.stringPropertyNames()).containsExactly("baz");
        assertThat(properties.getProperty("baz")).isEmpty();
    }

    @Test
    public void preserves_pinned_checksum_for_external_dep() throws IOException {
        Properties dependencies = new Properties();
        dependencies.setProperty("baz", "SHA256/cafebabe");
        try (Writer writer = Files.newBufferedWriter(module.resolve(BuildStep.REQUIRES))) {
            dependencies.store(writer, null);
        }
        BuildStepResult result = new MultiProjectDependencies("foo"::equals, DependencyScope.COMPILE).apply(
                        Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.REQUIRES))) {
            properties.load(reader);
        }
        assertThat(properties.getProperty("baz")).isEqualTo("SHA256/cafebabe");
    }
}
