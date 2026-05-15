package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.project.MultiProjectDependencies;
import build.jenesis.project.MultiProjectModule;

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
        BuildStepResult result = new MultiProjectDependencies("SHA256", "foo"::equals, MultiProjectModule.COMPILE).apply(
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
        assertThat(properties.getProperty("baz")).isEqualTo("SHA256/" + HexFormat.of().formatHex(MessageDigest
                .getInstance("SHA256")
                .digest("qux".getBytes())));
    }

    @Test
    public void reuses_prior_digest_when_identity_and_referenced_file_are_retained() throws IOException {
        Properties dependencies = new Properties();
        dependencies.setProperty("baz", "");
        try (Writer writer = Files.newBufferedWriter(module.resolve(BuildStep.REQUIRES))) {
            dependencies.store(writer, null);
        }
        Path file = dependency.resolve("artifact");
        Files.writeString(file, "ignored-because-reused");
        Properties coordinates = new Properties();
        coordinates.setProperty("baz", "artifact");
        try (Writer writer = Files.newBufferedWriter(dependency.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        Path priorFolder = Files.createDirectory(root.resolve("prior"));
        Properties priorRequires = new Properties();
        priorRequires.setProperty("baz", "SHA256/cafebabe");
        try (Writer writer = Files.newBufferedWriter(priorFolder.resolve(BuildStep.REQUIRES))) {
            priorRequires.store(writer, null);
        }
        BuildStepResult result = new MultiProjectDependencies("SHA256", "foo"::equals, MultiProjectModule.COMPILE).apply(
                        Runnable::run,
                        new BuildStepContext(priorFolder, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.RETAINED)),
                                "bar", new BuildStepArgument(
                                        dependency,
                                        Map.of(
                                                Path.of(BuildStep.IDENTITY), ChecksumStatus.RETAINED,
                                                Path.of("artifact"), ChecksumStatus.RETAINED)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.REQUIRES))) {
            properties.load(reader);
        }
        assertThat(properties.getProperty("baz")).isEqualTo("SHA256/cafebabe");
    }

    @Test
    public void recomputes_when_referenced_file_changed() throws IOException, NoSuchAlgorithmException {
        Properties dependencies = new Properties();
        dependencies.setProperty("baz", "");
        try (Writer writer = Files.newBufferedWriter(module.resolve(BuildStep.REQUIRES))) {
            dependencies.store(writer, null);
        }
        Path file = dependency.resolve("artifact");
        Files.writeString(file, "qux");
        Properties coordinates = new Properties();
        coordinates.setProperty("baz", "artifact");
        try (Writer writer = Files.newBufferedWriter(dependency.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        Path priorFolder = Files.createDirectory(root.resolve("prior"));
        Properties priorRequires = new Properties();
        priorRequires.setProperty("baz", "SHA256/staledigest");
        try (Writer writer = Files.newBufferedWriter(priorFolder.resolve(BuildStep.REQUIRES))) {
            priorRequires.store(writer, null);
        }
        BuildStepResult result = new MultiProjectDependencies("SHA256", "foo"::equals, MultiProjectModule.COMPILE).apply(
                        Runnable::run,
                        new BuildStepContext(priorFolder, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "foo", new BuildStepArgument(
                                        module,
                                        Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.RETAINED)),
                                "bar", new BuildStepArgument(
                                        dependency,
                                        Map.of(
                                                Path.of(BuildStep.IDENTITY), ChecksumStatus.RETAINED,
                                                Path.of("artifact"), ChecksumStatus.ALTERED)))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.REQUIRES))) {
            properties.load(reader);
        }
        assertThat(properties.getProperty("baz")).isEqualTo("SHA256/" + HexFormat.of().formatHex(MessageDigest
                .getInstance("SHA256")
                .digest("qux".getBytes())));
    }
}
