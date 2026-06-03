package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.project.JUnit4;
import build.jenesis.project.JUnitPlatform;
import build.jenesis.project.TestEngine;
import build.jenesis.project.TestNG;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestEngineTest {

    @TempDir
    private Path root;

    @Test
    public void detects_junit_platform_from_engine_module() throws IOException {
        writeJar(root.resolve("artifacts"), "engine.jar", "org.junit.platform.engine");
        assertThat(TestEngine.of(List.of(root))).get().isInstanceOf(JUnitPlatform.class);
    }

    @Test
    public void detects_junit4_from_module() throws IOException {
        writeJar(root.resolve("artifacts"), "junit.jar", "junit");
        assertThat(TestEngine.of(List.of(root))).get().isInstanceOf(JUnit4.class);
    }

    @Test
    public void detects_testng_from_module() throws IOException {
        writeJar(root.resolve("artifacts"), "testng.jar", "org.testng");
        assertThat(TestEngine.of(List.of(root))).get().isInstanceOf(TestNG.class);
    }

    @Test
    public void detects_engine_from_dependencies_folder() throws IOException {
        writeJar(root.resolve("dependencies"), "engine.jar", "org.junit.platform.engine");
        assertThat(TestEngine.of(List.of(root))).get().isInstanceOf(JUnitPlatform.class);
    }

    @Test
    public void detects_no_engine_for_unrelated_module() throws IOException {
        writeJar(root.resolve("artifacts"), "plain.jar", "com.example.something");
        assertThat(TestEngine.of(List.of(root))).isEmpty();
    }

    @Test
    public void detects_no_engine_from_file_name_alone() throws IOException {
        writeJar(root.resolve("artifacts"), "org.junit.platform.engine.jar", null);
        assertThat(TestEngine.of(List.of(root))).isEmpty();
    }

    @Test
    public void detects_no_engine_without_jars() throws IOException {
        assertThat(TestEngine.of(List.of(root))).isEmpty();
    }

    @Test
    public void detects_runner_from_console_module() throws IOException {
        writeJar(root.resolve("artifacts"), "console.jar", "org.junit.platform.console");
        assertThat(new JUnitPlatform().hasRunner(TestEngine.scan(List.of(root)))).isTrue();
    }

    @Test
    public void detects_no_runner_for_engine_only() throws IOException {
        writeJar(root.resolve("artifacts"), "engine.jar", "org.junit.platform.engine");
        assertThat(new JUnitPlatform().hasRunner(TestEngine.scan(List.of(root)))).isFalse();
    }

    @Test
    public void derives_console_default_version_from_engine_module() {
        ModuleDescriptor engine = ModuleDescriptor.newModule("org.junit.platform.engine")
                .version("1.11.3")
                .build();
        assertThat(new JUnitPlatform().coordinates(engine))
                .containsEntry("maven/org.junit.platform/junit-platform-console", "1.11.3")
                .containsEntry("module/org.junit.platform.console", "1.11.3");
    }

    @Test
    public void console_floats_without_a_derived_engine_version() {
        SequencedMap<String, String> coordinates = new JUnitPlatform().coordinates(null);
        assertThat(coordinates).containsEntry("maven/org.junit.platform/junit-platform-console", "RELEASE");
        assertThat(coordinates).containsKey("module/org.junit.platform.console");
        assertThat(coordinates.get("module/org.junit.platform.console")).isNull();
    }

    @Test
    public void junit4_emits_class_names_positionally() {
        assertThat(new JUnit4().commands(List.of("sample.AlphaTest", "sample.BetaTest"), new LinkedHashMap<>()))
                .containsExactly("sample.AlphaTest", "sample.BetaTest");
    }

    @Test
    public void junit4_rejects_method_selectors() {
        SequencedMap<String, List<String>> methods = new LinkedHashMap<>();
        methods.put("sample.AlphaTest", List.of("first"));
        assertThatThrownBy(() -> new JUnit4().commands(List.of(), methods))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void junit_platform_emits_select_class_and_method_arguments() {
        SequencedMap<String, List<String>> methods = new LinkedHashMap<>();
        methods.put("sample.AlphaTest", List.of("first", "second"));
        assertThat(new JUnitPlatform().commands(List.of("sample.BetaTest"), methods))
                .containsExactly("--select-class=sample.BetaTest",
                        "--select-method=sample.AlphaTest#first",
                        "--select-method=sample.AlphaTest#second");
    }

    @Test
    public void testng_joins_classes_and_methods() {
        SequencedMap<String, List<String>> methods = new LinkedHashMap<>();
        methods.put("sample.AlphaTest", List.of("first"));
        assertThat(new TestNG().commands(List.of("sample.AlphaTest", "sample.BetaTest"), methods))
                .containsExactly("-testclass", "sample.AlphaTest,sample.BetaTest",
                        "-methods", "sample.AlphaTest.first");
    }

    private static void writeJar(Path folder, String name, String moduleName) throws IOException {
        Files.createDirectories(folder);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (moduleName != null) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(folder.resolve(name)), manifest)) {
            output.putNextEntry(new JarEntry("sample/resource.txt"));
            output.closeEntry();
        }
    }
}
