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
import build.jenesis.project.ScalastyleModule;

import static org.assertj.core.api.Assertions.assertThat;

public class ScalastyleModuleRunTest {

    private static final String VERSION = "1.5.1";

    private static final String CONFIG = """
            <scalastyle>
                <name>test</name>
                <check level="error" class="org.scalastyle.file.FileLineLengthChecker" enabled="true">
                    <parameters>
                        <parameter name="maxLineLength">10</parameter>
                    </parameters>
                </check>
            </scalastyle>
            """;

    @TempDir
    private Path root, project;

    @Test
    public void downloads_the_pinned_scalastyle_and_writes_a_report() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("scalastyle/maven/com.beautiful-scala/scalastyle_2.13", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Files.writeString(project.resolve("scalastyle-config.xml"), CONFIG);
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.scala"), """
                package sample
                class Sample {
                  def greetTheEntireWorld(): Int = 42
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scalastyle",
                new ScalastyleModule(Map.of("maven", MavenDefaultRepository.of()), Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resolved = root.resolve("scalastyle").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned Scalastyle version resolves")
                    .anyMatch(name -> name.contains("scalastyle") && name.contains(VERSION));
        }
        Path report = root.resolve("scalastyle").resolve("check").resolve("output").resolve("reports").resolve("scalastyle").resolve("scalastyle-report.xml");
        assertThat(report).isNotEmptyFile();
        assertThat(report).content().contains("FileLineLengthChecker");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }
}
