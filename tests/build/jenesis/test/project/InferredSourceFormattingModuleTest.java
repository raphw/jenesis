package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.InferredSourceFormattingModule;

import static org.assertj.core.api.Assertions.assertThat;

public class InferredSourceFormattingModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void wires_ktlint_format_when_editorconfig_is_present() throws IOException {
        Files.writeString(project.resolve(".editorconfig"), "root = true\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of()), "project");
        executor.execute("format/ktlint/tool/required");

        assertThat(coordinates("ktlint", "tool"))
                .containsExactly("ktlint-format/runtime/maven/com.pinterest.ktlint/ktlint-cli/RELEASE");
    }

    @Test
    public void wires_scalafmt_format_when_scalafmt_conf_is_present() throws IOException {
        Files.writeString(project.resolve(".scalafmt.conf"), "version = \"3.8.3\"\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of()), "project");
        executor.execute("format/scalafmt/tool/required");

        assertThat(coordinates("scalafmt", "tool"))
                .containsExactly("scalafmt-format/runtime/maven/org.scalameta/scalafmt-cli_2.13/RELEASE");
    }

    @Test
    public void wires_google_java_format_from_the_javaformat_properties_file() throws IOException {
        Files.writeString(project.resolve("javaformat.properties"), "formatter=google\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of()), "project");
        executor.execute("format/java/required");

        assertThat(coordinates("java"))
                .containsExactly("google-java-format/runtime/maven/com.google.googlejavaformat/google-java-format/RELEASE");
    }

    @Test
    public void wires_palantir_java_format_from_the_javaformat_properties_file() throws IOException {
        Files.writeString(project.resolve("javaformat.properties"), "formatter=palantir\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of()), "project");
        executor.execute("format/java/required");

        assertThat(coordinates("java"))
                .containsExactly("palantir-java-format/runtime/maven/com.palantir.javaformat/palantir-java-format/RELEASE");
    }

    @Test
    public void the_java_override_switches_off_the_formatter_from_the_file() throws IOException {
        Files.writeString(project.resolve("javaformat.properties"), "formatter=google\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format",
                new InferredSourceFormattingModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of()).java(false),
                "project");
        executor.execute();

        assertThat(root.resolve("format").resolve("java"))
                .as("jenesis.format.java=false switches the formatter off even with a javaformat.properties file")
                .doesNotExist();
    }

    @Test
    public void does_not_wire_a_java_formatter_without_a_javaformat_properties_file() throws IOException {
        Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(project.resolve(BuildStep.SOURCES + "sample").resolve("Sample.java"), "package sample; class Sample {}");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of()), "project");
        executor.execute();

        assertThat(root.resolve("format").resolve("java"))
                .as("no Java formatter is wired without a javaformat.properties file, even when Java sources exist")
                .doesNotExist();
    }

    @Test
    public void does_not_wire_ktlint_format_when_editorconfig_is_absent() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("format", new InferredSourceFormattingModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of()), "project");
        executor.execute();

        assertThat(root.resolve("format").resolve("ktlint")).doesNotExist();
    }

    private Iterable<String> coordinates(String... segments) throws IOException {
        Path output = root.resolve("format");
        for (String segment : segments) {
            output = output.resolve(segment);
        }
        output = output.resolve("required").resolve("output");
        return SequencedProperties.ofFiles(output.resolve(BuildStep.REQUIRES)).stringPropertyNames();
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }
}
