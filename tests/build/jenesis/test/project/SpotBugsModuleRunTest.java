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
import build.jenesis.project.SpotBugsModule;

import static org.assertj.core.api.Assertions.assertThat;

public class SpotBugsModuleRunTest {

    private static final String VERSION = "4.9.6";

    @TempDir
    private Path root, project, sources;

    @Test
    public void downloads_the_pinned_spotbugs_and_writes_a_report() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("spotbugs/maven/com.github.spotbugs/spotbugs", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Files.writeString(project.resolve("spotbugs-exclude.xml"), "<FindBugsFilter/>");

        Path source = Files.createDirectories(sources.resolve("sample")).resolve("Sample.java");
        Files.writeString(source, """
                package sample;
                public class Sample {
                    public boolean check(String value) {
                        return value == "expected";
                    }
                }
                """);
        Path classes = Files.createDirectories(project.resolve(BuildStep.CLASSES));
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", classes.toString(), "--release", "17", source.toString());
        assertThat(rc).as("the sample compiles").isZero();

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "spotbugs",
                new SpotBugsModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resolved = root.resolve("spotbugs").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned SpotBugs version resolves")
                    .anyMatch(name -> name.contains("spotbugs") && name.contains(VERSION));
        }
        Path report = root.resolve("spotbugs").resolve("check").resolve("output").resolve("reports").resolve("spotbugs").resolve("spotbugs-report.xml");
        assertThat(report).isNotEmptyFile();
        assertThat(report).content().contains("ES_COMPARING");
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
