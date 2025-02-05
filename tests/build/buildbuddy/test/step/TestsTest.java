package build.buildbuddy.test.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.step.Javac;
import build.buildbuddy.step.Tests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sample.TestSample;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class TestsTest {

    @TempDir
    private Path root;
    private List<String> appended;
    private Path previous, next, supplement, dependencies, classes;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectories(root.resolve("dependencies"));
        classes = Files.createDirectories(root.resolve("classes"));
        Path artifacts = Files.createDirectory(dependencies.resolve(BuildStep.ARTIFACTS));
        List<String> elements = new ArrayList<>();
        elements.addAll(Arrays.asList(System.getProperty("java.class.path", "").split(File.pathSeparator)));
        elements.addAll(Arrays.asList(System.getProperty("jdk.module.path", "").split(File.pathSeparator)));
        appended = new ArrayList<>();
        for (String element : elements) {
            if (element.endsWith("_rt.jar") || element.endsWith("-rt.jar")) {
                continue;
            }
            Path path = Path.of(element);
            if (Files.isRegularFile(path)) {
                String name = path.getFileName().toFile() + "-" + UUID.randomUUID() + ".jar";
                appended.add(name);
                Files.copy(path, artifacts.resolve(name));
            }
        }
        try (InputStream input = TestSample.class.getResourceAsStream(TestSample.class.getSimpleName() + ".class");
             OutputStream output = Files.newOutputStream(Files
                     .createDirectories(classes.resolve(Javac.CLASSES + "sample"))
                     .resolve("TestSample.class"))) {
            requireNonNull(input).transferTo(output);
        }
    }

    @Test
    public void can_execute_junit() throws IOException {
        BuildStepResult result = new Tests(null, candidate -> candidate.endsWith("TestSample")).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "dependencies", new BuildStepArgument(
                                dependencies,
                                appended.stream().collect(Collectors.toMap(
                                        name -> Path.of(BuildStep.ARTIFACTS + name),
                                        _ -> ChecksumStatus.ADDED))),
                        "classes", new BuildStepArgument(
                                classes,
                                Map.of(Path.of(Javac.CLASSES + "sample/TestSample.class"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }

    @Test
    public void can_execute_junit_non_modular() throws IOException {
        BuildStepResult result = new Tests(null, candidate -> candidate.endsWith("TestSample")).modular(false).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "dependencies", new BuildStepArgument(
                                dependencies,
                                appended.stream().collect(Collectors.toMap(
                                        name -> Path.of(BuildStep.ARTIFACTS + name),
                                        _ -> ChecksumStatus.ADDED))),
                        "classes", new BuildStepArgument(
                                classes,
                                Map.of(Path.of(Javac.CLASSES + "sample/TestSample.class"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(supplement.resolve("output")).content().contains("Hello world!");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }
}
