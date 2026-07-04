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
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.KtlintModule;

import static org.assertj.core.api.Assertions.assertThat;

public class KtlintModuleRunTest {

    private static final String VERSION = "1.5.0";

    @TempDir
    private Path root, project, configuration;

    @Test
    public void downloads_the_pinned_ktlint_and_writes_a_report() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("ktlint/maven/com.pinterest.ktlint/ktlint-cli", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Files.writeString(project.resolve(".editorconfig"), """
                root = true
                [*.kt]
                indent_size = 4
                """);
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample
                fun main() {
                println("hello")
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "ktlint",
                new KtlintModule(Map.of("maven", MavenDefaultRepository.of()), Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resolved = root.resolve("ktlint").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned ktlint version resolves")
                    .anyMatch(name -> name.contains("ktlint-cli") && name.contains(VERSION));
        }
        Path report = root.resolve("ktlint").resolve("check").resolve("output").resolve("reports").resolve("ktlint").resolve("ktlint-report.xml");
        assertThat(report).isNotEmptyFile();
        assertThat(report).content().contains("Sample.kt");
    }

    @Test
    public void editorconfig_from_a_configuration_folder_is_passed_to_ktlint() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("ktlint/maven/com.pinterest.ktlint/ktlint-cli", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample

                fun main() {
                  println("hello")
                }
                """);
        Files.writeString(configuration.resolve(".editorconfig"), """
                root = true
                [*.kt]
                indent_size = 2
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addSource("configuration", configuration);
        executor.addModule(
                "ktlint",
                new KtlintModule(Map.of("maven", MavenDefaultRepository.of()), Map.of("maven", new MavenPomResolver()))
                        .strict(true),
                "project",
                "configuration");
        executor.execute();

        Path report = root.resolve("ktlint").resolve("check").resolve("output").resolve("reports").resolve("ktlint").resolve("ktlint-report.xml");
        assertThat(report).isNotEmptyFile();
        assertThat(report).content().doesNotContain("<error");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }
}
