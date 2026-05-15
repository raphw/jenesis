package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildExecutorException;
import build.jenesis.BuildStep;
import build.jenesis.HashDigestFunction;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.project.TestModule;
import build.jenesis.step.JUnit4;
import build.jenesis.step.JUnit5;
import build.jenesis.step.Javac;
import sample.TestSample;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestModuleTest {

    @TempDir
    private Path root, dependencies, classes, emptyDependencies;
    private List<String> appended;

    @BeforeEach
    public void setUp() throws Exception {
        Path artifacts = Files.createDirectory(dependencies.resolve(BuildStep.ARTIFACTS));
        Files.createDirectory(emptyDependencies.resolve(BuildStep.ARTIFACTS));
        List<String> elements = new ArrayList<>();
        elements.addAll(Arrays.asList(System.getProperty("java.class.path", "").split(File.pathSeparator)));
        elements.addAll(Arrays.asList(System.getProperty("jdk.module.path", "").split(File.pathSeparator)));
        appended = new ArrayList<>();
        for (String element : elements) {
            if (element.endsWith("_rt.jar") || element.endsWith("-rt.jar")) {
                continue;
            }
            Path path = Path.of(element);
            if (Files.isRegularFile(path)) {
                String name = path.getFileName().toFile() + "-" + UUID.randomUUID() + ".jar";
                appended.add(name);
                Files.copy(path, artifacts.resolve(name));
            }
        }
        try (InputStream input = TestSample.class.getResourceAsStream(TestSample.class.getSimpleName() + ".class");
             OutputStream output = Files.newOutputStream(Files
                     .createDirectories(classes.resolve(Javac.CLASSES + "sample"))
                     .resolve("TestSample.class"))) {
            requireNonNull(input).transferTo(output);
        }
    }

    @Test
    public void can_execute_junit() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(null, candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }

    @Test
    public void can_execute_junit_non_modular() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(null, candidate -> candidate.endsWith("TestSample")).jarsOnly(false).modular(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }

    @Test
    public void can_execute_with_explicit_engine() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5("5.11.3", "1.11.4"), candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }

    @Test
    public void can_execute_with_default_predicate() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5("5.11.3", "1.11.4")).jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
    }

    @Test
    public void throws_when_no_engine_found() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(null, candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
                "dependencies", "classes");

        assertThatThrownBy(executor::execute)
                .isInstanceOf(BuildExecutorException.class)
                .hasMessage("Failed to execute test/" + "executed")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No test engine found");
    }

    @Test
    public void requires_step_emits_runner_coordinate_when_missing() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5("5.11.3", "1.11.4"), candidate -> candidate.endsWith("TestSample"))
                        .withResolvers(Map.<String, Repository>of(), Map.of("maven", noResolver()))
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        Properties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties.stringPropertyNames())
                .containsExactly("maven/org.junit.platform/junit-platform-console/1.11.4");
    }

    @Test
    public void requires_step_picks_module_coordinate_when_module_resolver_available() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5("5.11.3", "1.11.4"), candidate -> candidate.endsWith("TestSample"))
                        .withResolvers(Map.<String, Repository>of(), Map.of("module", noResolver()))
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        Properties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties.stringPropertyNames())
                .containsExactly("module/org.junit.platform.console");
    }

    @Test
    public void requires_step_emits_nothing_when_no_resolver_matches() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5("5.11.3", "1.11.4"), candidate -> candidate.endsWith("TestSample"))
                        .withResolvers(Map.<String, Repository>of(), Map.<String, Resolver>of())
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        Properties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties).isEmpty();
    }

    @Test
    public void requires_step_emits_nothing_when_runner_present() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5("5.11.3", "1.11.4"), candidate -> candidate.endsWith("TestSample"))
                        .withResolvers(Map.<String, Repository>of(), Map.<String, Resolver>of())
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        Properties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties).isEmpty();
    }

    @Test
    public void requires_step_emits_nothing_for_engine_without_external_runner() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit4(), candidate -> candidate.endsWith("TestSample"))
                        .withResolvers(Map.<String, Repository>of(), Map.<String, Resolver>of())
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        Properties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties).isEmpty();
    }

    @Test
    public void requires_step_emits_nothing_when_no_engine_detected() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(null, candidate -> candidate.endsWith("TestSample"))
                        .withResolvers(Map.<String, Repository>of(), Map.<String, Resolver>of())
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        Properties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties).isEmpty();
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root, new HashDigestFunction("MD5"), BuildExecutorCallback.nop());
    }

    private static Resolver noResolver() {
        return (_, _, _, _, _, _) -> new LinkedHashMap<>();
    }

    private static Properties readRequires(Path stepFolder) throws IOException {
        Path file = stepFolder.resolve("output").resolve(BuildStep.REQUIRES);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return properties;
    }
}
