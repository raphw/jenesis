package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Repository;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.CodeNarcModule;

import static org.assertj.core.api.Assertions.assertThat;

public class CodeNarcModuleRunTest {

    private static final String CONFIG = """
            <ruleset xmlns="http://codenarc.org/ruleset/1.0">
                <rule class="org.codenarc.rule.basic.EmptyIfStatementRule"/>
            </ruleset>
            """;

    @TempDir
    private Path root, project;

    @Test
    public void downloads_the_pinned_codenarc_and_writes_a_report() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("codenarc/maven/org.codenarc/CodeNarc", "3.5.0");
        versions.setProperty("codenarc/maven/org.apache.groovy/groovy", "4.0.23");
        versions.setProperty("codenarc/maven/org.slf4j/slf4j-simple", "2.0.16");
        versions.store(project.resolve(BuildStep.VERSIONS));
        Files.writeString(project.resolve("codenarc.xml"), CONFIG);
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.groovy"), """
                package sample
                class Sample {
                    void run() {
                        if (true) { }
                    }
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "codenarc",
                new CodeNarcModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resolved = root.resolve("codenarc").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned CodeNarc version resolves")
                    .anyMatch(name -> name.contains("CodeNarc") && name.contains("3.5.0"));
        }
        Path report = root.resolve("codenarc").resolve("check").resolve("output").resolve("reports").resolve("codenarc").resolve("codenarc-report.xml");
        assertThat(report).isNotEmptyFile();
        assertThat(report).content().contains("EmptyIfStatement");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }

    private static Repository mavenCentral() {
        return new MavenDefaultRepository();
    }
}
