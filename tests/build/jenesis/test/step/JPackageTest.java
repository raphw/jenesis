package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.JPackage;
import sample.Sample;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JPackageTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, bundle;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        bundle = Files.createDirectory(root.resolve("bundle"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_jpackage(boolean process) throws IOException {
        Path artifacts = Files.createDirectory(bundle.resolve(BuildStep.ARTIFACTS));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, "sample.Sample");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifacts.resolve("app.jar")), manifest)) {
            jar.putNextEntry(new JarEntry("sample/Sample.class"));
            try (InputStream in = Sample.class.getResourceAsStream("Sample.class")) {
                requireNonNull(in).transferTo(jar);
            }
            jar.closeEntry();
        }
        SequencedProperties configuration = new SequencedProperties();
        configuration.setProperty("--name", "Sample");
        configuration.setProperty("--main-jar", "app.jar");
        configuration.setProperty("--main-class", "sample.Sample");
        configuration.store(Files.createDirectory(bundle.resolve("process")).resolve("jpackage.properties"));
        BuildStepResult result = (process ? JPackage.process("app-image") : JPackage.tool("app-image")).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/app.jar"), ChecksumStatus.ADDED,
                                Path.of("process/jpackage.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JPackage.PACKAGES)).isNotEmptyDirectory();
        assertThat(next.resolve(JPackage.PACKAGES + "Sample")).isDirectory();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void skips_when_no_jars_are_present(boolean process) throws IOException {
        SequencedProperties configuration = new SequencedProperties();
        configuration.setProperty("--main-jar", "app.jar");
        configuration.store(Files.createDirectory(bundle.resolve("process")).resolve("jpackage.properties"));
        BuildStepResult result = (process ? JPackage.process("app-image") : JPackage.tool("app-image")).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("process/jpackage.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JPackage.PACKAGES)).doesNotExist();
    }

    @Test
    public void fails_on_duplicate_jar_file_names() throws IOException {
        Files.writeString(Files.createDirectory(bundle.resolve(BuildStep.ARTIFACTS)).resolve("app.jar"), "one");
        Files.writeString(Files.createDirectory(bundle.resolve(BuildStep.DEPENDENCIES)).resolve("app.jar"), "two");
        SequencedProperties configuration = new SequencedProperties();
        configuration.setProperty("--main-jar", "app.jar");
        configuration.store(Files.createDirectory(bundle.resolve("process")).resolve("jpackage.properties"));
        assertThatThrownBy(() -> JPackage.tool("app-image").apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/app.jar"), ChecksumStatus.ADDED,
                                Path.of("dependencies/app.jar"), ChecksumStatus.ADDED,
                                Path.of("process/jpackage.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same file name 'app.jar'");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void skips_when_no_launcher_is_configured(boolean process) throws IOException {
        Path artifacts = Files.createDirectory(bundle.resolve(BuildStep.ARTIFACTS));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifacts.resolve("app.jar")))) {
            jar.putNextEntry(new JarEntry("sample/Sample.class"));
            try (InputStream in = Sample.class.getResourceAsStream("Sample.class")) {
                requireNonNull(in).transferTo(jar);
            }
            jar.closeEntry();
        }
        BuildStepResult result = (process ? JPackage.process("app-image") : JPackage.tool("app-image")).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/app.jar"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JPackage.PACKAGES)).doesNotExist();
    }
}
