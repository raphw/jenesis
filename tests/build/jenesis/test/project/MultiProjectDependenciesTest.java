package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
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
    public void sibling_artifact_dependency_writes_artifact_checksum() throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("baz", "");
        dependencies.store(module.resolve(BuildStep.REQUIRES));
        Path file = target.resolve("file");
        Files.writeString(file, "qux");
        SequencedProperties coordinates = new SequencedProperties();
        coordinates.setProperty("baz", file.toString());
        coordinates.store(dependency.resolve(BuildStep.IDENTITY));
        BuildStepResult result = new MultiProjectDependencies("foo"::equals, DependencyScope.COMPILE, new HashDigestFunction("MD5")).apply(
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
        SequencedProperties properties = SequencedProperties.ofFiles(next.resolve(BuildStep.REQUIRES));
        assertThat(properties.stringPropertyNames()).containsExactly("baz");
        assertThat(properties.getProperty("baz")).isEqualTo(new HashDigestFunction("MD5").encodedHash(file));
    }

    @Test
    public void preserves_pinned_checksum_for_external_dep() throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("baz", "SHA256/cafebabe");
        dependencies.store(module.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new MultiProjectDependencies("foo"::equals, DependencyScope.COMPILE, new HashDigestFunction("MD5")).apply(
                        Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties properties = SequencedProperties.ofFiles(next.resolve(BuildStep.REQUIRES));
        assertThat(properties.getProperty("baz")).isEqualTo("SHA256/cafebabe");
    }
}
