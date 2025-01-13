package build.buildbuddy.test.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildStep;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.project.JavaModule;
import build.buildbuddy.step.Bind;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sample.Sample;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class JavaModuleTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path input;
    private BuildExecutor buildExecutor;

    @Before
    public void setUp() throws Exception {
        input = temporaryFolder.newFolder("input").toPath();
        buildExecutor = BuildExecutor.of(
                temporaryFolder.newFolder("root").toPath(),
                new HashDigestFunction("MD5"));
    }

    @Test
    public void can_build_java() throws IOException {
        Path sources = Files.createDirectories(input.resolve(BuildStep.SOURCES + "other"));
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("Sample.java"))) {
            writer.append("package other;");
            writer.newLine();
            writer.append("public class Sample {");
            writer.newLine();
            writer.append("  sample.Sample s = new sample.Sample();");
            writer.newLine();
            writer.append("}");
            writer.newLine();
        }
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(Files
                .createDirectories(input.resolve(BuildStep.ARTIFACTS))
                .resolve("dependency.jar")));
             InputStream inputStream = requireNonNull(Sample.class.getResourceAsStream("Sample.class"))) {
            outputStream.putNextEntry(new JarEntry("sample/Sample.class"));
            inputStream.transferTo(outputStream);
        }
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new JavaModule(), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/classes", "output/artifacts");
        assertThat(steps.get("output/classes").resolve(BuildStep.CLASSES).resolve("other/Sample.class")).exists();
        try (JarInputStream inputStream = new JarInputStream(Files.newInputStream(steps.get("output/artifacts")
                .resolve(BuildStep.ARTIFACTS)
                .resolve("classes.jar")))) {
            assertThat(inputStream.getNextJarEntry())
                    .extracting(ZipEntry::getName)
                    .isEqualTo("other/");
            assertThat(inputStream.getNextJarEntry())
                    .extracting(ZipEntry::getName)
                    .isEqualTo("other/Sample.class");
            assertThat(inputStream.getNextJarEntry()).isNull();
        }
    }
}