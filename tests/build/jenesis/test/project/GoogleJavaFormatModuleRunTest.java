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
import build.jenesis.project.GoogleJavaFormatModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GoogleJavaFormatModuleRunTest {

    private static final String VERSION = "1.35.0";

    @TempDir
    private Path root, project;

    @Test
    public void formats_in_place_then_skips_an_unchanged_file_on_a_second_run() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("google-java-format/maven/com.google.googlejavaformat/google-java-format", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sample = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample")).resolve("Sample.java");
        Files.writeString(sample, "package sample;class Sample{int f(){return 42;}}\n");

        newExecutor(false).execute();

        Path resolved = root.resolve("google-java-format").resolve("dependencies").resolve("output").resolve("resolved");
        try (Stream<Path> jars = Files.list(resolved)) {
            assertThat(jars.map(jar -> jar.getFileName().toString()))
                    .as("the pinned google-java-format version resolves")
                    .anyMatch(name -> name.contains("google-java-format") && name.contains(VERSION));
        }
        String formatted = Files.readString(sample);
        assertThat(formatted)
                .as("the source is reformatted in place")
                .contains("class Sample {")
                .contains("int f() {");
        Path command = root.resolve("google-java-format").resolve("format").resolve("supplement").resolve("command");
        assertThat(command).as("the first run forks the formatter").exists();
        Path hashes = root.resolve("google-java-format").resolve("format").resolve("output").resolve("formatted.properties");
        assertThat(hashes).isNotEmptyFile();

        newExecutor(false).execute();

        assertThat(Files.readString(sample)).as("the already-formatted file is left byte-identical").isEqualTo(formatted);
        assertThat(command).as("the formatter is not forked again when nothing changed").doesNotExist();
    }

    @Test
    public void verify_mode_fails_the_build_and_leaves_the_file_untouched() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("google-java-format/maven/com.google.googlejavaformat/google-java-format", VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sample = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample")).resolve("Sample.java");
        String unformatted = "package sample;class Sample{int f(){return 42;}}\n";
        Files.writeString(sample, unformatted);

        assertThatThrownBy(newExecutor(true)::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("Unexpected exit code");
        assertThat(Files.readString(sample)).as("verify mode never rewrites the source").isEqualTo(unformatted);
        assertThat(root.resolve("google-java-format").resolve("format").resolve("output").resolve("formatted.properties"))
                .as("verify mode does not persist the incremental hash state")
                .doesNotExist();
    }

    private BuildExecutor newExecutor(boolean verify) throws IOException {
        BuildExecutor executor = BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addSource("project", project);
        executor.addModule(
                "google-java-format",
                new GoogleJavaFormatModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver()))
                        .verify(verify),
                "project");
        return executor;
    }

    private static Repository mavenCentral() {
        return new MavenDefaultRepository();
    }
}
