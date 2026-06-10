package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.InferredSourceFormattingModule;
import build.jenesis.project.InferredSourceFormattingModule.JavaFormatter;

import static org.assertj.core.api.Assertions.assertThat;

public class InferredSourceFormattingModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void wires_ktlint_format_when_editorconfig_is_present() throws IOException {
        Files.writeString(project.resolve(".editorconfig"), "root = true\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(project, Map.of(), Map.of()), "project");
        executor.execute("format/ktlint-format/required");

        assertThat(coordinates("ktlint-format"))
                .containsExactly("ktlint-format/runtime/maven/com.pinterest.ktlint/ktlint-cli/RELEASE");
    }

    @Test
    public void wires_scalafmt_format_when_scalafmt_conf_is_present() throws IOException {
        Files.writeString(project.resolve(".scalafmt.conf"), "version = \"3.8.3\"\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(project, Map.of(), Map.of()), "project");
        executor.execute("format/scalafmt-format/required");

        assertThat(coordinates("scalafmt-format"))
                .containsExactly("scalafmt-format/runtime/maven/org.scalameta/scalafmt-cli_2.13/RELEASE");
    }

    @Test
    public void wires_the_selected_google_java_formatter() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format",
                new InferredSourceFormattingModule(project, Map.of(), Map.of()).javaFormatter(JavaFormatter.GOOGLE),
                "project");
        executor.execute("format/google-java-format/required");

        assertThat(coordinates("google-java-format"))
                .containsExactly("google-java-format/runtime/maven/com.google.googlejavaformat/google-java-format/RELEASE");
    }

    @Test
    public void wires_the_selected_palantir_java_formatter() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format",
                new InferredSourceFormattingModule(project, Map.of(), Map.of()).javaFormatter(JavaFormatter.PALANTIR),
                "project");
        executor.execute("format/palantir-java-format/required");

        assertThat(coordinates("palantir-java-format"))
                .containsExactly("palantir-java-format/runtime/maven/com.palantir.javaformat/palantir-java-format/RELEASE");
    }

    @Test
    public void does_not_wire_a_java_formatter_by_default() throws IOException {
        Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(project.resolve(BuildStep.SOURCES + "sample").resolve("Sample.java"), "package sample; class Sample {}");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(project, Map.of(), Map.of()), "project");
        executor.execute();

        assertThat(root.resolve("format").resolve("google-java-format"))
                .as("no Java formatter is wired unless one is selected, even when Java sources exist")
                .doesNotExist();
        assertThat(root.resolve("format").resolve("palantir-java-format")).doesNotExist();
    }

    @Test
    public void does_not_wire_ktlint_format_when_editorconfig_is_absent() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(project, Map.of(), Map.of()), "project");
        executor.execute();

        assertThat(root.resolve("format").resolve("ktlint-format")).doesNotExist();
    }

    private Iterable<String> coordinates(String group) throws IOException {
        Path output = root.resolve("format").resolve(group).resolve("required").resolve("output");
        return SequencedProperties.ofFiles(output.resolve(BuildStep.REQUIRES)).stringPropertyNames();
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }
}
