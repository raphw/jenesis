package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.project.DokkaDocumentationModule;
import build.jenesis.project.GroovyDocumentationModule;
import build.jenesis.project.InferredDocumentationChainModule;
import build.jenesis.project.ScalaDocumentationModule;

import static org.assertj.core.api.Assertions.assertThat;

public class InferredDocumentationModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void scan_detects_each_language_from_source_extensions() throws IOException {
        Path sample = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sample.resolve("Greeter.java"), "package sample; class Greeter {}");
        Files.writeString(sample.resolve("Sample.kt"), "package sample\nclass Sample");
        Files.writeString(sample.resolve("Pure.scala"), "package sample\nclass Pure");
        Files.writeString(sample.resolve("Script.groovy"), "package sample\nclass Script {}");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("doc",
                new InferredDocumentationChainModule(Map.of(), Map.of("maven", Resolver.identity())),
                "project");
        executor.execute("doc/scan");

        SequencedProperties scan = SequencedProperties.ofFiles(root
                .resolve("doc").resolve("scan").resolve("output").resolve("scan.properties"));
        assertThat(scan.getProperty("javadoc")).isEqualTo("true");
        assertThat(scan.getProperty("dokka")).isEqualTo("true");
        assertThat(scan.getProperty("scaladoc")).isEqualTo("true");
        assertThat(scan.getProperty("groovydoc")).isEqualTo("true");
    }

    @Test
    public void scan_reports_absent_languages_as_false() throws IOException {
        Path sample = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sample.resolve("Greeter.java"), "package sample; class Greeter {}");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("doc",
                new InferredDocumentationChainModule(Map.of(), Map.of("maven", Resolver.identity())),
                "project");
        executor.execute("doc/scan");

        SequencedProperties scan = SequencedProperties.ofFiles(root
                .resolve("doc").resolve("scan").resolve("output").resolve("scan.properties"));
        assertThat(scan.getProperty("javadoc")).isEqualTo("true");
        assertThat(scan.getProperty("dokka")).isEqualTo("false");
        assertThat(scan.getProperty("scaladoc")).isEqualTo("false");
        assertThat(scan.getProperty("groovydoc")).isEqualTo("false");
    }

    @Test
    public void dokka_requires_the_cli_and_plugin_coordinates_under_a_qualified_trail() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("dokka",
                new DokkaDocumentationModule(Map.of(), Map.of("maven", Resolver.identity())),
                "project");
        executor.execute("dokka/required");

        SequencedProperties requires = SequencedProperties.ofFiles(root
                .resolve("dokka").resolve("required").resolve("output").resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames()).containsExactlyInAnyOrder(
                "dokka/maven/org.jetbrains.dokka/dokka-cli/RELEASE",
                "dokka/maven/org.jetbrains.dokka/dokka-base/RELEASE",
                "dokka/maven/org.jetbrains.dokka/analysis-kotlin-descriptors/RELEASE");
    }

    @Test
    public void dokka_requires_nothing_without_a_maven_resolver() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("dokka",
                new DokkaDocumentationModule(Map.of(), Map.of("module", Resolver.identity())),
                "project");
        executor.execute("dokka/required");

        SequencedProperties requires = SequencedProperties.ofFiles(root
                .resolve("dokka").resolve("required").resolve("output").resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames()).isEmpty();
    }

    @Test
    public void scaladoc_requires_pins_the_jackson_annotations_its_runtime_expects() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("scaladoc",
                new ScalaDocumentationModule(Map.of(), Map.of("maven", Resolver.identity())),
                "project");
        executor.execute("scaladoc/required");

        SequencedProperties requires = SequencedProperties.ofFiles(root
                .resolve("scaladoc").resolve("required").resolve("output").resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames()).contains(
                "scaladoc/maven/org.scala-lang/scaladoc_3/RELEASE",
                "scaladoc/maven/com.fasterxml.jackson.core/jackson-annotations/2.21");
    }

    @Test
    public void groovydoc_requires_the_groovydoc_coordinate_under_a_qualified_trail() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("groovydoc",
                new GroovyDocumentationModule(Map.of(), Map.of("maven", Resolver.identity())),
                "project");
        executor.execute("groovydoc/required");

        SequencedProperties requires = SequencedProperties.ofFiles(root
                .resolve("groovydoc").resolve("required").resolve("output").resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("groovydoc/maven/org.apache.groovy/groovy-groovydoc/RELEASE");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }
}
