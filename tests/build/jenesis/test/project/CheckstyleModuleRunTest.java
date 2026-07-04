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
import build.jenesis.project.CheckstyleModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CheckstyleModuleRunTest {

    private static final String VERSION = "10.21.0";

    private static final String CONFIG = """
            <?xml version="1.0"?>
            <!DOCTYPE module PUBLIC
                "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                "https://checkstyle.org/dtds/configuration_1_3.dtd">
            <module name="Checker">
                <property name="severity" value="error"/>
                <module name="TreeWalker">
                    <module name="TypeName"/>
                </module>
            </module>
            """;

    @TempDir
    private Path root, project;

    @BeforeEach
    public void writeProject() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("checkstyle/maven/com.puppycrawl.tools/checkstyle", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Files.writeString(project.resolve("checkstyle.xml"), CONFIG);
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("badName.java"), """
                package sample;
                public class badName {
                }
                """);
    }

    @Test
    public void writes_a_report_without_failing_the_build_in_report_only_mode() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "checkstyle",
                new CheckstyleModule(Map.of("maven", MavenDefaultRepository.of()), Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resolved = root.resolve("checkstyle").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned Checkstyle version is the one that resolves, not a floated RELEASE")
                    .anyMatch(name -> name.contains("checkstyle") && name.contains(VERSION));
        }
        Path report = root.resolve("checkstyle").resolve("check").resolve("output").resolve("reports").resolve("checkstyle").resolve("checkstyle-report.xml");
        assertThat(report)
                .as("report-only run still produces the Checkstyle XML report")
                .isNotEmptyFile();
        assertThat(report).content().contains("badName");
    }

    @Test
    public void strict_mode_fails_the_build_on_a_violation() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "checkstyle",
                new CheckstyleModule(Map.of("maven", MavenDefaultRepository.of()), Map.of("maven", new MavenPomResolver()))
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }
}
