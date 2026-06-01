package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.project.JUnit4;
import build.jenesis.project.JUnit5;
import build.jenesis.project.TestEngine;
import build.jenesis.project.TestNG;

import static org.assertj.core.api.Assertions.assertThat;

public class TestEngineTest {

    @TempDir
    private Path root;

    @Test
    public void detects_junit5_from_artifact_manifest() throws IOException {
        writeJar(root.resolve("artifacts"), "engine.jar", "junit-platform-engine");
        assertThat(TestEngine.of(List.of(root))).get().isInstanceOf(JUnit5.class);
    }

    @Test
    public void detects_junit4_from_artifact_manifest() throws IOException {
        writeJar(root.resolve("artifacts"), "junit.jar", "JUnit");
        assertThat(TestEngine.of(List.of(root))).get().isInstanceOf(JUnit4.class);
    }

    @Test
    public void detects_testng_from_artifact_manifest() throws IOException {
        writeJar(root.resolve("artifacts"), "testng.jar", "TestNG");
        assertThat(TestEngine.of(List.of(root))).get().isInstanceOf(TestNG.class);
    }

    @Test
    public void detects_engine_from_dependencies_folder() throws IOException {
        writeJar(root.resolve("dependencies"), "engine.jar", "junit-platform-engine");
        assertThat(TestEngine.of(List.of(root))).get().isInstanceOf(JUnit5.class);
    }

    @Test
    public void detects_no_engine_without_matching_marker() throws IOException {
        writeJar(root.resolve("artifacts"), "plain.jar", "Something-Else");
        assertThat(TestEngine.of(List.of(root))).isEmpty();
    }

    @Test
    public void detects_no_engine_without_jars() throws IOException {
        assertThat(TestEngine.of(List.of(root))).isEmpty();
    }

    @Test
    public void detects_runner_from_runner_marker() throws IOException {
        writeJar(root.resolve("artifacts"), "console.jar", "junit-platform-console");
        assertThat(TestEngine.hasRunner(new JUnit5(), List.of(root))).isTrue();
    }

    @Test
    public void detects_no_runner_for_engine_only_marker() throws IOException {
        writeJar(root.resolve("artifacts"), "engine.jar", "junit-platform-engine");
        assertThat(TestEngine.hasRunner(new JUnit5(), List.of(root))).isFalse();
    }

    private static void writeJar(Path folder, String name, String implementationTitle) throws IOException {
        Files.createDirectories(folder);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Title", implementationTitle);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(folder.resolve(name)), manifest)) {
            output.putNextEntry(new JarEntry("sample/resource.txt"));
            output.closeEntry();
        }
    }
}
