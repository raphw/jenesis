package build.buildbuddy.test.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorCallback;
import build.buildbuddy.BuildStep;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.project.JavaModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sample.Sample;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class JavaModuleTest {

    @TempDir
    private Path input, root;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
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

    @Test
    public void can_build_java_with_junit() throws Exception {
        Path sources = Files.createDirectories(input.resolve(BuildStep.SOURCES + "other"));
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("SampleTest.java"))) {
            writer.append("package other;");
            writer.newLine();
            writer.append("public class SampleTest {");
            writer.newLine();
            writer.append("  @org.junit.jupiter.api.Test");
            writer.newLine();
            writer.append("  public void test() {");
            writer.newLine();
            writer.append("    System.out.println(\"Hello world!\");");
            writer.newLine();
            writer.append("  }");
            writer.newLine();
            writer.append("}");
            writer.newLine();
        }
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.ARTIFACTS));
        List<String> elements = new ArrayList<>();
        elements.addAll(Arrays.asList(System.getProperty("java.class.path", "").split(File.pathSeparator)));
        elements.addAll(Arrays.asList(System.getProperty("jdk.module.path", "").split(File.pathSeparator)));
        for (String element : elements) {
            if (element.endsWith("_rt.jar") || element.endsWith("-rt.jar")) {
                continue;
            }
            Path path = Path.of(element);
            if (Files.isRegularFile(path)) {
                Files.copy(path, artifacts.resolve(URLEncoder.encode(
                        UUID.randomUUID().toString(),
                        StandardCharsets.UTF_8) + ".jar"));
            }
        }
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new JavaModule().testIfAvailable(), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/classes", "output/artifacts", "output/tests");
        assertThat(steps.get("output/classes").resolve(BuildStep.CLASSES).resolve("other/SampleTest.class")).exists();
        try (JarInputStream inputStream = new JarInputStream(Files.newInputStream(steps.get("output/artifacts")
                .resolve(BuildStep.ARTIFACTS)
                .resolve("classes.jar")))) {
            assertThat(inputStream.getNextJarEntry())
                    .extracting(ZipEntry::getName)
                    .isEqualTo("other/");
            assertThat(inputStream.getNextJarEntry())
                    .extracting(ZipEntry::getName)
                    .isEqualTo("other/SampleTest.class");
            assertThat(inputStream.getNextJarEntry()).isNull();
        }
    }
}
