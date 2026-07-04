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
    private Path root, project;

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

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }
}
