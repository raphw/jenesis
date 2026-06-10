package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Repository;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.DetektModule;

import static org.assertj.core.api.Assertions.assertThat;

public class DetektModuleRunTest {

    private static final String VERSION = "1.23.7";

    private static final String CONFIG = """
            style:
              active: true
              MaxLineLength:
                active: true
                maxLineLength: 10
            """;

    @TempDir
    private Path root, project;

    @Test
    public void downloads_the_pinned_detekt_and_writes_a_report() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("detekt/maven/io.gitlab.arturbosch.detekt/detekt-cli", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Files.writeString(project.resolve("detekt.yml"), CONFIG);
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample
                fun greetTheEntireWorld(): Int = 42
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "detekt",
                new DetektModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resolved = root.resolve("detekt").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned detekt version resolves")
                    .anyMatch(name -> name.contains("detekt-cli") && name.contains(VERSION));
        }
        Path report = root.resolve("detekt").resolve("check").resolve("output").resolve("detekt-report.xml");
        assertThat(report).isNotEmptyFile();
        assertThat(report).content().contains("MaxLineLength");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }

    private static Repository mavenCentral() {
        return new MavenDefaultRepository();
    }
}
