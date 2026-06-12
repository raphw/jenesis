package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.MultiProjectDependencies;

import static org.assertj.core.api.Assertions.assertThat;

public class MultiProjectDependenciesTest {

    @TempDir
    private Path root;
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
        SequencedProperties coordinates = new SequencedProperties();
        coordinates.setProperty("baz", "file");
        coordinates.store(dependency.resolve(BuildStep.IDENTITY));
        HashDigestFunction hash = new HashDigestFunction("MD5");
        byte[] artifact = {1, 2, 3};
        String checksum = hash.encoded(artifact);
        BuildStepResult result = new MultiProjectDependencies("foo"::equals).apply(
                        Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED))),
                                "bar", new BuildStepArgument(
                                        dependency,
                                        Checksum.added(Map.of(Path.of("file"), artifact), hash)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties properties = SequencedProperties.ofFiles(next.resolve(BuildStep.REQUIRES));
        assertThat(properties.stringPropertyNames()).containsExactly("baz");
        assertThat(properties.getProperty("baz")).isEqualTo(checksum);
    }

    @Test
    public void preserves_pinned_checksum_for_external_dep() throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("baz", "SHA256/cafebabe");
        dependencies.store(module.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new MultiProjectDependencies("foo"::equals).apply(
                        Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties properties = SequencedProperties.ofFiles(next.resolve(BuildStep.REQUIRES));
        assertThat(properties.getProperty("baz")).isEqualTo("SHA256/cafebabe");
    }
}
