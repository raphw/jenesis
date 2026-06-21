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
import build.jenesis.project.PmdModule;

import static org.assertj.core.api.Assertions.assertThat;

public class PmdModuleRunTest {

    private static final String VERSION = "7.7.0";

    private static final String CONFIG = """
            <?xml version="1.0"?>
            <ruleset name="test"
                     xmlns="http://pmd.sourceforge.net/ruleset/2.0.0">
                <rule ref="category/java/bestpractices.xml/SystemPrintln"/>
            </ruleset>
            """;

    @TempDir
    private Path root, project;

    @Test
    public void downloads_the_pinned_pmd_and_writes_a_report() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("pmd/maven/net.sourceforge.pmd/pmd-dist", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Files.writeString(project.resolve("pmd.xml"), CONFIG);
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.java"), """
                package sample;
                public class Sample {
                    public void run() {
                        System.out.println("hello");
                    }
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "pmd",
                new PmdModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resolved = root.resolve("pmd").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned PMD version resolves")
                    .anyMatch(name -> name.contains("pmd-core") && name.contains(VERSION));
        }
        Path report = root.resolve("pmd").resolve("check").resolve("output").resolve("reports").resolve("pmd").resolve("pmd-report.xml");
        assertThat(report).isNotEmptyFile();
        assertThat(report).content().contains("SystemPrintln");
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
