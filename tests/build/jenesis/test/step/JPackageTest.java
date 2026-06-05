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
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.JLink;
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
        BuildStepResult result = new JPackage(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, "app-image").apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/app.jar"), ChecksumStatus.ADDED,
                                Path.of("process/jpackage.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JPackage.PACKAGES)).isNotEmptyDirectory();
        assertThat(imageDirectory()).isDirectory();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void skips_when_no_jars_are_present(boolean process) throws IOException {
        SequencedProperties configuration = new SequencedProperties();
        configuration.setProperty("--main-jar", "app.jar");
        configuration.store(Files.createDirectory(bundle.resolve("process")).resolve("jpackage.properties"));
        BuildStepResult result = new JPackage(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, "app-image").apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("process/jpackage.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JPackage.PACKAGES)).doesNotExist();
    }

    @Test
    public void can_execute_jpackage_in_modular_mode() throws IOException {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module sample { }\n");
        Files.writeString(Files.createDirectory(sources.resolve("sample")).resolve("Sample.java"),
                "package sample; public class Sample { public static void main(String[] args) { } }\n");
        Path classes = Files.createDirectory(root.resolve("classes"));
        int compiled = ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-d", classes.toString(),
                sources.resolve("module-info.java").toString(),
                sources.resolve("sample/Sample.java").toString());
        assertThat(compiled).isZero();
        Path artifacts = Files.createDirectory(bundle.resolve(BuildStep.ARTIFACTS));
        int archived = ToolProvider.findFirst("jar").orElseThrow().run(System.out, System.err,
                "--create", "--file", artifacts.resolve("sample.jar").toString(),
                "-C", classes.toString(), ".");
        assertThat(archived).isZero();
        SequencedProperties configuration = new SequencedProperties();
        configuration.setProperty("--name", "Sample");
        configuration.setProperty("--module", "sample/sample.Sample");
        configuration.store(Files.createDirectory(bundle.resolve("process")).resolve("jpackage.properties"));
        BuildStepResult result = new JPackage(ProcessHandler.Factory.TOOL, "app-image").apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/sample.jar"), ChecksumStatus.ADDED,
                                Path.of("process/jpackage.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(imageDirectory()).isDirectory();
    }

    @Test
    public void wraps_jlink_runtime_via_runtime_image() throws IOException {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module sample { }\n");
        Files.writeString(Files.createDirectory(sources.resolve("sample")).resolve("Sample.java"),
                "package sample; public class Sample { public static void main(String[] args) { } }\n");
        Path classes = Files.createDirectory(root.resolve("classes"));
        assertThat(ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-d", classes.toString(),
                sources.resolve("module-info.java").toString(),
                sources.resolve("sample/Sample.java").toString())).isZero();
        Path modules = Files.createDirectory(root.resolve("modules"));
        assertThat(ToolProvider.findFirst("jar").orElseThrow().run(System.out, System.err,
                "--create", "--file", modules.resolve("sample.jar").toString(),
                "-C", classes.toString(), ".")).isZero();
        Path runtime = bundle.resolve(JLink.RUNTIME);
        assertThat(ToolProvider.findFirst("jlink").orElseThrow().run(System.out, System.err,
                "--module-path", modules.toString(),
                "--add-modules", "sample",
                "--output", runtime.toString())).isZero();
        Files.writeString(Files.createDirectories(runtime.resolve("conf")).resolve("app.properties"),
                "greeting=bundled\n");
        SequencedProperties configuration = new SequencedProperties();
        configuration.setProperty("--name", "Sample");
        configuration.setProperty("--module", "sample/sample.Sample");
        configuration.store(Files.createDirectory(bundle.resolve("process")).resolve("jpackage.properties"));
        BuildStepResult result = new JPackage(ProcessHandler.Factory.TOOL, "app-image").apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("runtime", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("process/jpackage.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path image = imageDirectory();
        assertThat(image).isDirectory();
        Path bundled;
        try (Stream<Path> walk = Files.walk(image)) {
            bundled = walk.filter(path -> path.endsWith(Path.of("conf", "app.properties")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Bundled runtime config not found in " + image));
        }
        assertThat(bundled).content().contains("greeting=bundled");
    }

    @Test
    public void fails_on_duplicate_jar_file_names() throws IOException {
        Files.writeString(Files.createDirectory(bundle.resolve(BuildStep.ARTIFACTS)).resolve("app.jar"), "one");
        Files.writeString(Files.createDirectory(bundle.resolve(BuildStep.DEPENDENCIES)).resolve("app.jar"), "two");
        SequencedProperties index = new SequencedProperties();
        index.setProperty("runtime/maven/app", "dependencies/app.jar");
        index.store(bundle.resolve(BuildStep.DEPENDENCY_INDEX));
        SequencedProperties configuration = new SequencedProperties();
        configuration.setProperty("--main-jar", "app.jar");
        configuration.store(Files.createDirectory(bundle.resolve("process")).resolve("jpackage.properties"));
        assertThatThrownBy(() -> new JPackage(ProcessHandler.Factory.TOOL, "app-image").apply(
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
        BuildStepResult result = new JPackage(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, "app-image").apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/app.jar"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JPackage.PACKAGES)).doesNotExist();
    }

    private Path imageDirectory() {
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        return next.resolve(JPackage.PACKAGES + (mac ? "Sample.app" : "Sample"));
    }
}
