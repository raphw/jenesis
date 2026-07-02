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
    public void merges_boms_across_sibling_modules_first_wins() throws IOException {
        SequencedProperties first = new SequencedProperties();
        first.setProperty("bom/main/module/acme.platform", "1.0");
        first.setProperty("entry/main/module/bar", "1.2.3");
        first.store(module.resolve(BuildStep.BOMS));
        SequencedProperties firstRequires = new SequencedProperties();
        firstRequires.store(module.resolve(BuildStep.REQUIRES));
        SequencedProperties second = new SequencedProperties();
        second.setProperty("bom/main/module/acme.platform", "2.0");
        second.setProperty("bom/main/module/other.platform", "3.0");
        second.store(dependency.resolve(BuildStep.BOMS));
        SequencedProperties secondRequires = new SequencedProperties();
        secondRequires.store(dependency.resolve(BuildStep.REQUIRES));
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("foo", new BuildStepArgument(
                module,
                Map.of(Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED))));
        arguments.put("bar", new BuildStepArgument(
                dependency,
                Map.of(Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED))));
        BuildStepResult result = new MultiProjectDependencies(_ -> true).apply(
                        Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties properties = SequencedProperties.ofFiles(next.resolve(BuildStep.BOMS));
        assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrder(
                "bom/main/module/acme.platform",
                "entry/main/module/bar",
                "bom/main/module/other.platform");
        assertThat(properties.getProperty("bom/main/module/acme.platform")).isEqualTo("1.0");
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
