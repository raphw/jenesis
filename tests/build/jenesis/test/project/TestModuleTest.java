package build.jenesis.test.project;

import module java.base;
import module java.compiler;
import module org.junit.jupiter.api;
import build.jenesis.PathPlacement;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.TestModule;
import build.jenesis.project.JUnit4;
import build.jenesis.project.JUnitPlatform;
import build.jenesis.project.JaCoCo;
import build.jenesis.step.Javac;
import build.jenesis.project.TestEngine;
import build.jenesis.project.TestNG;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.util.jar.Attributes;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestModuleTest {

    @TempDir
    private Path root, dependencies, classes, emptyDependencies, junit4Dependencies, testngDependencies;

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
        Path sampleClasses = classes.resolve(Javac.CLASSES + "sample");
        compileSource(sampleClasses, "TestSample", """
                package sample;
                public class TestSample {
                    @org.junit.jupiter.api.Test
                    public void test() { System.out.println("Hello world!"); }
                }
                """, bootModuleJars());
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/maven/org.junit.platform/junit-platform-console",
                "1.11.4 SHA-256/a9c3309cdfded3542200de85da6cb274864439d6b02ba80bb45ecc8e0bdf1be7");
        versions.setProperty("main/maven/org.junit.platform/junit-platform-reporting",
                "1.11.4 SHA-256/df6896109bfaef4de8d2fa9e3371a6176936d1a45a6c0e7fd8f7e6dd6f4c5597");
        versions.setProperty("main/maven/org.junit.platform/junit-platform-launcher",
                "1.11.4 SHA-256/d7430bd029e7fcced53ee445e4d2d1a8a1e043ea4c4df43b6335a857f79761ae");
        versions.setProperty("main/maven/org.junit.platform/junit-platform-engine",
                "1.11.3 SHA-256/0043f72f611664735da8dc9a308bf12ecd2236b05339351c4741edb4d8fab0da");
        versions.setProperty("main/maven/org.junit.platform/junit-platform-commons",
                "1.11.3 SHA-256/be262964b0b6b48de977c61d4f931df8cf61e80e750cc3f3a0a39cdd21c1008c");
        versions.setProperty("main/maven/org.opentest4j/opentest4j",
                "1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b");
        versions.setProperty("main/maven/org.apiguardian/apiguardian-api",
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
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
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
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false).modulePath(PathPlacement.CLASS_PATH),
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
    public void can_execute_junit4() throws Exception {
        Path junitJar = downloadJar(junit4Dependencies.resolve("junit-4.13.2.jar"),
                "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar",
                "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3");
        Path hamcrestJar = downloadJar(junit4Dependencies.resolve("hamcrest-core-1.3.jar"),
                "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
                "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9");
        populateFilteredArtifacts(junit4Dependencies, Set.of("junit-4.13.2.jar", "hamcrest-core-1.3.jar"));
        Path sampleClasses = classes.resolve(Javac.CLASSES + "sample");
        compileSource(sampleClasses, "JUnit4TestSample", """
                package sample;
                public class JUnit4TestSample {
                    @org.junit.Test
                    public void test() { System.out.println("Hello world!"); }
                }
                """, List.of(junitJar));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/maven/junit/junit",
                "4.13.2 SHA-256/8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3");
        versions.setProperty("main/maven/org.hamcrest/hamcrest-core",
                "1.3 SHA-256/66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9");
        versions.store(junit4Dependencies.resolve(BuildStep.VERSIONS));
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/runtime/maven/junit/junit", "");
        requires.store(junit4Dependencies.resolve(BuildStep.REQUIRES));

        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", junit4Dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .engine(new JUnit4())
                        .isTest(candidate -> candidate.endsWith("JUnit4TestSample")).jarsOnly(false).modulePath(PathPlacement.CLASS_PATH),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("output")).content().contains("OK (1 test)");
    }

    @Test
    public void can_execute_testng() throws Exception {
        Path testngJar = downloadJar(testngDependencies.resolve("testng-7.10.2.jar"),
                "https://repo1.maven.org/maven2/org/testng/testng/7.10.2/testng-7.10.2.jar",
                "225fd56447f2e5e439db3b483a79cd9f294fad9f357f8352b12ee6a3411ebb15");
        Path jcommanderJar = downloadJar(testngDependencies.resolve("jcommander-1.82.jar"),
                "https://repo1.maven.org/maven2/com/beust/jcommander/1.82/jcommander-1.82.jar",
                "deeac157c8de6822878d85d0c7bc8467a19cc8484d37788f7804f039dde280b1");
        Path slf4jJar = downloadJar(testngDependencies.resolve("slf4j-api-1.7.36.jar"),
                "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar",
                "d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0");
        Path jqueryJar = downloadJar(testngDependencies.resolve("jquery-3.7.1.jar"),
                "https://repo1.maven.org/maven2/org/webjars/jquery/3.7.1/jquery-3.7.1.jar",
                "262016dd3a559df87aefbe392804e9bf620787c9204c0ab8522d4c231ea65097");
        populateFilteredArtifacts(testngDependencies, Set.of(
                "testng-7.10.2.jar", "jcommander-1.82.jar", "slf4j-api-1.7.36.jar", "jquery-3.7.1.jar"));
        Path sampleClasses = classes.resolve(Javac.CLASSES + "sample");
        compileSource(sampleClasses, "TestNGTestSample", """
                package sample;
                public class TestNGTestSample {
                    @org.testng.annotations.Test
                    public void test() { System.out.println("Hello world!"); }
                }
                """, List.of(testngJar));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/maven/org.testng/testng",
                "7.10.2 SHA-256/225fd56447f2e5e439db3b483a79cd9f294fad9f357f8352b12ee6a3411ebb15");
        versions.setProperty("main/maven/com.beust/jcommander",
                "1.82 SHA-256/deeac157c8de6822878d85d0c7bc8467a19cc8484d37788f7804f039dde280b1");
        versions.setProperty("main/maven/org.slf4j/slf4j-api",
                "1.7.36 SHA-256/d3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0");
        versions.setProperty("main/maven/org.webjars/jquery",
                "3.7.1 SHA-256/262016dd3a559df87aefbe392804e9bf620787c9204c0ab8522d4c231ea65097");
        versions.store(testngDependencies.resolve(BuildStep.VERSIONS));
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/runtime/maven/org.testng/testng", "");
        requires.store(testngDependencies.resolve(BuildStep.REQUIRES));

        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", testngDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .engine(new TestNG())
                        .isTest(candidate -> candidate.endsWith("TestNGTestSample")).jarsOnly(false).modulePath(PathPlacement.CLASS_PATH),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
    }

    @Test
    public void can_execute_with_explicit_engine() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .engine(new JUnitPlatform())
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
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
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .engine(new JUnitPlatform()).jarsOnly(false),
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
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .engine(new JUnitPlatform())
                        .isTest((Predicate<String> & Serializable) _ -> false)
                        .filter("sample\\.TestSample").jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("command")).content().contains("--select-class=sample.TestSample");
    }

    @Test
    public void abstract_classes_are_excluded_from_test_selection() throws IOException {
        Path sampleClasses = classes.resolve(Javac.CLASSES + "sample");
        compileSource(sampleClasses, "AbstractTestSample", """
                package sample;
                public abstract class AbstractTestSample {
                    @org.junit.jupiter.api.Test
                    public void test() { System.out.println("must not run"); }
                }
                """, bootModuleJars());

        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .engine(new JUnitPlatform())
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("command")).content()
                .contains("--select-class=sample.TestSample")
                .doesNotContain("AbstractTestSample");
    }

    @Test
    public void filter_with_method_selector_targets_specific_method() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .engine(new JUnitPlatform())
                        .isTest((Predicate<String> & Serializable) _ -> false)
                        .filter("sample\\.TestSample#test").jarsOnly(false),
                "dependencies", "classes");
        executor.execute();

        Path supplement = root.resolve("test").resolve("executed").resolve("supplement");
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("command")).content().contains("--select-method=sample.TestSample#test");
    }

    @Test
    public void fails_when_selection_matches_no_tests() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of("maven", new MavenDefaultRepository(
                                URI.create("https://repo1.maven.org/maven2/"),
                                null,
                                Map.of(),
                                _ -> {})),
                        Map.of("maven", new MavenPomResolver()))
                        .engine(new JUnitPlatform())
                        .isTest((Predicate<String> & Serializable) _ -> false)
                        .filter("sample\\.DoesNotExist").jarsOnly(false),
                "dependencies", "classes");

        assertThatThrownBy(executor::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("No tests matched the requested selection");
    }

    @Test
    public void throws_when_no_engine_found() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of(), Map.of())
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false).requireEngine(true),
                "dependencies", "classes");

        assertThatThrownBy(executor::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("No test engine could be resolved from inherited dependencies");
    }

    @Test
    public void skips_when_jenesis_test_skip_is_set() throws IOException {
        System.setProperty("jenesis.test.skip", "");
        try {
            BuildExecutor executor = newExecutor();
            executor.addSource("dependencies", emptyDependencies);
            executor.addSource("classes", classes);
            executor.addModule(
                    "test",
                    new TestModule(Map.of(), Map.of())
                            .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false).requireEngine(true),
                    "dependencies", "classes");

            SequencedMap<String, Path> outputs = executor.execute();

            assertThat(outputs).doesNotContainKeys("test/resolved", "test/dependencies", "test/executed");
        } finally {
            System.clearProperty("jenesis.test.skip");
        }
    }

    @Test
    public void requires_step_emits_runner_coordinate_when_missing() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of(), Map.of("maven", (_, _, _, _, _, _) -> new Resolver.Resolution(new LinkedHashMap<>(), List.of(), new LinkedHashMap<>())))
                        .engine(new JUnitPlatform())
                        .isTest(candidate -> candidate.endsWith("TestSample"))
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        Path stepFolder = root.resolve("test").resolve("resolved");
        assertThat(readRequires(stepFolder).stringPropertyNames())
                .containsExactly("main/runtime/maven/org.junit.platform/junit-platform-console");
        assertThat(readVersions(stepFolder).getProperty("main/maven/org.junit.platform/junit-platform-console"))
                .isEqualTo("RELEASE");
    }

    @Test
    public void requires_step_emits_observability_agent_coordinate() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of(), Map.of("maven", (_, _, _, _, _, _) -> new Resolver.Resolution(new LinkedHashMap<>(), List.of(), new LinkedHashMap<>())))
                        .engine(new JUnitPlatform())
                        .observe(new JaCoCo())
                        .isTest(candidate -> candidate.endsWith("TestSample"))
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        assertThat(readRequires(root.resolve("test").resolve("resolved")).stringPropertyNames())
                .containsExactlyInAnyOrder(
                        "main/runtime/maven/org.junit.platform/junit-platform-console",
                        "main/agent/maven/org.jacoco/org.jacoco.agent/jar/runtime/RELEASE");
    }

    @Test
    public void requires_step_picks_module_coordinate_when_module_resolver_available() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of(), Map.of("module", (_, _, _, _, _, _) -> new Resolver.Resolution(new LinkedHashMap<>(), List.of(), new LinkedHashMap<>())))
                        .engine(new JUnitPlatform())
                        .isTest(candidate -> candidate.endsWith("TestSample"))
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        SequencedProperties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties.stringPropertyNames())
                .containsExactly("main/runtime/module/org.junit.platform.console");
    }

    @Test
    public void requires_step_emits_nothing_when_no_resolver_matches() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of(), Map.of())
                        .engine(new JUnitPlatform())
                        .isTest(candidate -> candidate.endsWith("TestSample"))
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
                new TestModule(Map.of(), Map.of())
                        .engine(new JUnitPlatform())
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
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
                new TestModule(Map.of(), Map.of())
                        .engine(new JUnit4())
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
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
                new TestModule(Map.of(), Map.of())
                        .engine(new TestNG())
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false),
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
                new TestModule(Map.of(), Map.of())
                        .isTest(candidate -> candidate.endsWith("TestSample")).jarsOnly(false).requireEngine(false),
                "dependencies", "classes");
        SequencedMap<String, Path> outputs = executor.execute();

        assertThat(outputs).doesNotContainKeys("test/resolved", "test/required", "test/artifacts", "test/executed");
    }

    @Test
    public void requires_step_writes_derived_runner_version_as_default() throws IOException {
        writeModuleJar(emptyDependencies.resolve(BuildStep.ARTIFACTS),
                "engine.jar", "org.junit.platform.engine", "1.11.3");
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of(), Map.of("module", (_, _, _, _, _, _) -> new Resolver.Resolution(new LinkedHashMap<>(), List.of(), new LinkedHashMap<>())))
                        .engine(new JUnitPlatform())
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        Path stepFolder = root.resolve("test").resolve("resolved");
        assertThat(readRequires(stepFolder).stringPropertyNames())
                .containsExactly("main/runtime/module/org.junit.platform.console");
        assertThat(readVersions(stepFolder).getProperty("main/module/org.junit.platform.console"))
                .isEqualTo("1.11.3");
    }

    @Test
    public void requires_step_lets_pinned_runner_version_override_derived_default() throws IOException {
        writeModuleJar(emptyDependencies.resolve(BuildStep.ARTIFACTS),
                "engine.jar", "org.junit.platform.engine", "1.11.3");
        SequencedProperties pinned = new SequencedProperties();
        pinned.setProperty("main/module/org.junit.platform.console", "1.12.0 SHA-256/abc");
        pinned.store(emptyDependencies.resolve(BuildStep.VERSIONS));
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of(), Map.of("module", (_, _, _, _, _, _) -> new Resolver.Resolution(new LinkedHashMap<>(), List.of(), new LinkedHashMap<>())))
                        .engine(new JUnitPlatform())
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        assertThat(readVersions(root.resolve("test").resolve("resolved"))
                .getProperty("main/module/org.junit.platform.console"))
                .isEqualTo("1.12.0 SHA-256/abc");
    }

    @Test
    public void requires_step_emits_all_coordinates_sharing_selected_prefix() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", emptyDependencies);
        executor.addSource("classes", classes);
        executor.addModule(
                "test",
                new TestModule(Map.of(), Map.of("maven", (_, _, _, _, _, _) -> new Resolver.Resolution(new LinkedHashMap<>(), List.of(), new LinkedHashMap<>())))
                        .engine(new MultiRunnerEngine())
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.execute("test/" + "resolved");

        SequencedProperties properties = readRequires(root.resolve("test").resolve("resolved"));
        assertThat(properties.stringPropertyNames())
                .containsExactly("main/runtime/maven/com.example/runner-core", "main/runtime/maven/com.example/runner-cli");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }

    private static SequencedProperties readRequires(Path stepFolder) throws IOException {
        return SequencedProperties.ofFiles(stepFolder.resolve("output").resolve(BuildStep.REQUIRES));
    }

    private static SequencedProperties readVersions(Path stepFolder) throws IOException {
        return SequencedProperties.ofFiles(stepFolder.resolve("output").resolve(BuildStep.VERSIONS));
    }

    private static Path downloadJar(Path target, String url, String expected) throws Exception {
        byte[] body;
        try (InputStream in = URI.create(url).toURL().openStream()) {
            body = in.readAllBytes();
        }
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "SHA-256 mismatch for " + url + ": expected " + expected + " got " + actual);
        }
        Files.write(target, body);
        return target;
    }

    private static void compileSource(Path classesDir, String simpleName, String source, List<Path> classpath)
            throws IOException {
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesDir.getParent()));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            JavaFileObject unit = new SimpleJavaFileObject(
                    URI.create("string:///sample/" + simpleName + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            if (!compiler.getTask(null, fileManager, null, null, null, List.of(unit)).call()) {
                throw new IllegalStateException("Failed to compile " + simpleName);
            }
        }
    }

    private static void populateFilteredArtifacts(Path dependencies, Set<String> excludedJarPrefixes) throws IOException {
        Path artifacts = Files.createDirectory(dependencies.resolve(BuildStep.ARTIFACTS));
        for (Path path : bootModuleJars()) {
            String fileName = path.getFileName().toString();
            if (fileName.endsWith("_rt.jar") || fileName.endsWith("-rt.jar")) {
                continue;
            }
            if (excludedJarPrefixes.stream().anyMatch(fileName::startsWith)) {
                continue;
            }
            Files.copy(path, artifacts.resolve(fileName + "-" + UUID.randomUUID() + ".jar"));
        }
    }

    private static void writeModuleJar(Path folder, String name, String moduleName, String version) throws IOException {
        Files.createDirectories(folder);
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(moduleName),
                builder -> builder
                        .moduleVersion(version)
                        .requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(folder.resolve(name)), manifest)) {
            output.putNextEntry(new JarEntry("module-info.class"));
            output.write(moduleInfo);
            output.closeEntry();
        }
    }

    private record MultiRunnerEngine() implements TestEngine {

        @Override
        public String runnerModule() {
            return "example.runner";
        }

        @Override
        public String mainClass() {
            return "example.Main";
        }

        @Override
        public boolean isEngine(ModuleDescriptor module) {
            return false;
        }

        @Override
        public boolean isRunner(ModuleDescriptor module) {
            return false;
        }

        @Override
        public SequencedMap<String, String> coordinates(ModuleDescriptor engine) {
            SequencedMap<String, String> coordinates = new LinkedHashMap<>();
            coordinates.put("maven/com.example/runner-core", "1.0");
            coordinates.put("maven/com.example/runner-cli", "1.0");
            return coordinates;
        }

        @Override
        public List<String> commands(Path supplement,
                                     Path output,
                                     SequencedSet<String> classes,
                                     SequencedMap<String, SequencedSet<String>> methods,
                                     SequencedSet<String> groups,
                                     boolean parallel,
                                     boolean reporting) {
            return List.of();
        }
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
