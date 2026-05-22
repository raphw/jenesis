package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildExecutorException;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.TestModule;
import build.jenesis.step.JUnit4;
import build.jenesis.step.JUnit5;
import build.jenesis.step.Javac;
import build.jenesis.step.TestNG;
import sample.TestSample;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestModuleTest {

    @TempDir
    private Path root, dependencies, classes, emptyDependencies;

    @BeforeEach
    public void setUp() throws Exception {
        Path artifacts = Files.createDirectory(dependencies.resolve(BuildStep.ARTIFACTS));
        Files.createDirectory(emptyDependencies.resolve(BuildStep.ARTIFACTS));
        List<String> appended = new ArrayList<>();
        for (Path path : bootModuleJars()) {
            String fileName = path.getFileName().toString();
            if (fileName.endsWith("_rt.jar") || fileName.endsWith("-rt.jar")) {
                continue;
            }
            String name = fileName + "-" + UUID.randomUUID() + ".jar";
            appended.add(name);
            Files.copy(path, artifacts.resolve(name));
        }
        try (InputStream input = TestSample.class.getResourceAsStream(TestSample.class.getSimpleName() + ".class");
             OutputStream output = Files.newOutputStream(Files
                     .createDirectories(classes.resolve(Javac.CLASSES + "sample"))
                     .resolve("TestSample.class"))) {
            requireNonNull(input).transferTo(output);
        }
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("maven/org.junit.platform/junit-platform-console",
                "1.11.4 SHA-256/a9c3309cdfded3542200de85da6cb274864439d6b02ba80bb45ecc8e0bdf1be7");
        versions.setProperty("maven/org.junit.platform/junit-platform-reporting",
                "1.11.4 SHA-256/df6896109bfaef4de8d2fa9e3371a6176936d1a45a6c0e7fd8f7e6dd6f4c5597");
        versions.setProperty("maven/org.junit.platform/junit-platform-launcher",
                "1.11.4 SHA-256/d7430bd029e7fcced53ee445e4d2d1a8a1e043ea4c4df43b6335a857f79761ae");
        versions.setProperty("maven/org.junit.platform/junit-platform-engine",
                "1.11.3 SHA-256/0043f72f611664735da8dc9a308bf12ecd2236b05339351c4741edb4d8fab0da");
        versions.setProperty("maven/org.junit.platform/junit-platform-commons",
                "1.11.3 SHA-256/be262964b0b6b48de977c61d4f931df8cf61e80e750cc3f3a0a39cdd21c1008c");
        versions.setProperty("maven/org.opentest4j/opentest4j",
                "1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b");
        versions.setProperty("maven/org.apiguardian/apiguardian-api",
                "1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38");
        versions.store(dependencies.resolve(BuildStep.VERSIONS));
    }

    @Test
    public void can_execute_junit() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(null,
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver())).jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(Files.readString(supplement.resolve("error"))
                .lines()
                .filter(line -> !line.startsWith("stty:"))
                .collect(Collectors.joining("\n"))).isEmpty();
    }

    @Test
    public void can_execute_junit_non_modular() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(null,
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver())).jarsOnly(false).modular(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(Files.readString(supplement.resolve("error"))
                .lines()
                .filter(line -> !line.startsWith("stty:"))
                .collect(Collectors.joining("\n"))).isEmpty();
    }

    @Test
    public void can_execute_with_explicit_engine() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5(),
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver())).jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(Files.readString(supplement.resolve("error"))
                .lines()
                .filter(line -> !line.startsWith("stty:"))
                .collect(Collectors.joining("\n"))).isEmpty();
    }

    @Test
    public void can_execute_with_default_predicate() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5(),
                        Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver())).jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
    }

    @Test
    public void filter_pattern_selects_test_class_bypassing_isTest_predicate() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5(),
                        (Predicate<String> & Serializable) _ -> false,
                        Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()),
                        "sample\\.TestSample").jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("command")).content().contains("-select-class=sample.TestSample");
    }

    @Test
    public void filter_with_method_selector_targets_specific_method() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5(),
                        (Predicate<String> & Serializable) _ -> false,
                        Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()),
                        "sample\\.TestSample#test").jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("command")).content().contains("-select-method=sample.TestSample#test");
    }

    @Test
    public void throws_when_no_engine_found() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(null,
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of(),
                        Map.of()).jarsOnly(false).requireEngine(true),
                "dependencies", "classes");

        assertThatThrownBy(executor::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("No test engine could be resolved from inherited dependencies");
    }

    @Test
    public void requires_step_emits_runner_coordinate_when_missing() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5(),
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of(),
                        Map.of("maven", (_, _, _, _, _, _) -> new LinkedHashMap<>()))
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        SequencedProperties properties = readRequires(root.resolve("test").resolve("resolved"));
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
                new TestModule(new JUnit5(),
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of(),
                        Map.of("module", (_, _, _, _, _, _) -> new LinkedHashMap<>()))
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        SequencedProperties properties = readRequires(root.resolve("test").resolve("resolved"));
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
                new TestModule(new JUnit5(),
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of(),
                        Map.of())
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        SequencedProperties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties).isEmpty();
    }

    @Test
    public void requires_step_emits_nothing_when_runner_present() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit5(),
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of(),
                        Map.of()).jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        SequencedProperties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties).isEmpty();
    }

    @Test
    public void requires_step_emits_nothing_for_engine_without_external_runner() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new JUnit4(),
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of(),
                        Map.of()).jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        SequencedProperties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties).isEmpty();
    }

    @Test
    public void requires_step_emits_nothing_for_testng_engine() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(new TestNG(),
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of(),
                        Map.of()).jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        SequencedProperties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties).isEmpty();
    }

    @Test
    public void registers_no_sub_steps_when_no_engine_detected() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(null,
                        candidate -> candidate.endsWith("TestSample"),
                        Map.of(),
                        Map.of()).jarsOnly(false).requireEngine(false),
                "dependencies", "classes");
        SequencedMap<String, Path> outputs = executor.execute();

        assertThat(outputs).doesNotContainKeys("test/resolved", "test/required", "test/artifacts", "test/executed");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop());
    }

    private static SequencedProperties readRequires(Path stepFolder) throws IOException {
        return SequencedProperties.ofFiles(stepFolder.resolve("output").resolve(BuildStep.REQUIRES));
    }

    private static List<Path> bootModuleJars() throws IOException {
        Set<Path> jars = new LinkedHashSet<>();
        for (ResolvedModule resolved : ModuleLayer.boot().configuration().modules()) {
            String name = resolved.name();
            if (name.startsWith("java.") || name.startsWith("jdk.")) {
                continue;
            }
            URI location = resolved.reference().location().orElse(null);
            if (location == null) {
                continue;
            }
            Path path = Path.of(location);
            if (Files.isRegularFile(path)) {
                jars.add(path);
            }
        }
        Enumeration<URL> manifests = ClassLoader.getSystemClassLoader().getResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements()) {
            String url = manifests.nextElement().toString();
            if (!url.startsWith("jar:file:")) {
                continue;
            }
            int bang = url.indexOf("!/");
            if (bang < 0) {
                continue;
            }
            Path path = Path.of(URI.create(url.substring("jar:".length(), bang)));
            if (Files.isRegularFile(path)) {
                jars.add(path);
            }
        }
        return new ArrayList<>(jars);
    }
}
