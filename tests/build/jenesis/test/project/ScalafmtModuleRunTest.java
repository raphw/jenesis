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
import build.jenesis.project.ScalafmtModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ScalafmtModuleRunTest {

    private static final String VERSION = "3.8.3";

    private static final String CONFIG = """
            version = "3.8.3"
            runner.dialect = scala213
            """;

    @TempDir
    private Path root, project;

    @BeforeEach
    public void writeProject() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("scalafmt/maven/org.scalameta/scalafmt-cli_2.13", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Files.writeString(project.resolve(".scalafmt.conf"), CONFIG);
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.scala"), "package sample\nclass Sample {   def  f( ) :Int=42 }\n");
    }

    @Test
    public void report_only_runs_the_pinned_scalafmt_and_flags_the_misformatted_file() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scalafmt",
                new ScalafmtModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resolved = root.resolve("scalafmt").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned scalafmt version resolves")
                    .anyMatch(name -> name.contains("scalafmt-cli") && name.contains(VERSION));
        }
        Path supplement = root.resolve("scalafmt").resolve("check").resolve("supplement");
        String captured = Files.readString(supplement.resolve("output")) + Files.readString(supplement.resolve("error"));
        assertThat(captured)
                .as("scalafmt --test reports the misformatted file")
                .contains("Sample.scala");
    }

    @Test
    public void strict_mode_fails_the_build_on_a_misformatted_file() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scalafmt",
                new ScalafmtModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver()))
                        .strict(true),
                "project");

        assertThatThrownBy(executor::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("Unexpected exit code");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }

    private static Repository mavenCentral() {
        Path local = Path.of(System.getProperty("user.home"), ".m2", "repository");
        return new MavenDefaultRepository(
                URI.create("https://repo1.maven.org/maven2/"),
                Files.isDirectory(local) ? local : null,
                Map.of(),
                _ -> {});
    }
}
