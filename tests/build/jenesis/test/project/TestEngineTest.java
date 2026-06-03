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
        assertThat(new JUnit4().commands(root,
                new LinkedHashSet<>(List.of("sample.AlphaTest", "sample.BetaTest")),
                Collections.emptyNavigableMap(),
                Collections.emptyNavigableSet(),
                false))
                .containsExactly("sample.AlphaTest", "sample.BetaTest");
    }

    @Test
    public void junit4_rejects_method_selectors() {
        SequencedMap<String, SequencedSet<String>> methods = new LinkedHashMap<>();
        methods.put("sample.AlphaTest", new LinkedHashSet<>(List.of("first")));
        assertThatThrownBy(() -> new JUnit4().commands(root,
                Collections.emptyNavigableSet(),
                methods,
                Collections.emptyNavigableSet(),
                false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void junit_platform_emits_select_class_and_method_arguments() {
        SequencedMap<String, SequencedSet<String>> methods = new LinkedHashMap<>();
        methods.put("sample.AlphaTest", new LinkedHashSet<>(List.of("first", "second")));
        assertThat(new JUnitPlatform().commands(root,
                new LinkedHashSet<>(List.of("sample.BetaTest")),
                methods,
                Collections.emptyNavigableSet(),
                false))
                .containsExactly("execute", "--disable-banner", "--disable-ansi-colors",
                        "--select-class=sample.BetaTest",
                        "--select-method=sample.AlphaTest#first",
                        "--select-method=sample.AlphaTest#second");
    }

    @Test
    public void testng_joins_classes_and_methods() {
        SequencedMap<String, SequencedSet<String>> methods = new LinkedHashMap<>();
        methods.put("sample.AlphaTest", new LinkedHashSet<>(List.of("first")));
        assertThat(new TestNG().commands(root,
                new LinkedHashSet<>(List.of("sample.AlphaTest", "sample.BetaTest")),
                methods,
                Collections.emptyNavigableSet(),
                false))
                .containsSubsequence("-testclass", "sample.AlphaTest,sample.BetaTest",
                        "-methods", "sample.AlphaTest.first");
    }

    @Test
    public void junit_platform_commands_add_one_tag_per_group_and_parallel_config() {
        assertThat(new JUnitPlatform().commands(root,
                Collections.emptyNavigableSet(),
                Collections.emptyNavigableMap(),
                new LinkedHashSet<>(List.of("slow", "flaky")),
                true))
                .contains("--include-tag=slow",
                        "--include-tag=flaky",
                        "--config=junit.jupiter.execution.parallel.enabled=true",
                        "--config=junit.jupiter.execution.parallel.mode.default=concurrent");
        assertThat(new JUnitPlatform().commands(root,
                Collections.emptyNavigableSet(),
                Collections.emptyNavigableMap(),
                Collections.emptyNavigableSet(),
                false))
                .doesNotContain("--include-tag=slow",
                        "--config=junit.jupiter.execution.parallel.enabled=true");
    }

    @Test
    public void testng_joins_groups_with_commas_and_adds_parallel() {
        assertThat(new TestNG().commands(root,
                Collections.emptyNavigableSet(),
                Collections.emptyNavigableMap(),
                new LinkedHashSet<>(List.of("slow", "flaky")),
                true))
                .containsSubsequence("-groups", "slow,flaky")
                .containsSubsequence("-parallel", "methods");
    }

    @Test
    public void junit4_rejects_groups() {
        assertThatThrownBy(() -> new JUnit4().commands(root,
                Collections.emptyNavigableSet(),
                Collections.emptyNavigableMap(),
                new LinkedHashSet<>(List.of("slow")),
                false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void junit4_ignores_parallel() {
        assertThat(new JUnit4().commands(root,
                new LinkedHashSet<>(List.of("sample.AlphaTest")),
                Collections.emptyNavigableMap(),
                Collections.emptyNavigableSet(),
                true))
                .containsExactly("sample.AlphaTest");
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
