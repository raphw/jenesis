package build.buildbuddy.test.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.step.Dependencies;
import build.buildbuddy.step.JUnit4;
import build.buildbuddy.step.Javac;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.JUnitCore;
import sample.SampleTest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class JUnit4Test {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, dependencies, classes;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectories(root.resolve("dependencies"));
        classes = Files.createDirectories(root.resolve("classes"));
    }

    @Test
    public void can_execute_junit4() throws IOException, ExecutionException, InterruptedException, URISyntaxException, ClassNotFoundException {
        Path libs = Files.createDirectory(dependencies.resolve(Dependencies.LIBS));
        Files.copy(
                Path.of(JUnitCore.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                libs.resolve("junit.jar"));
        Files.copy(
                Path.of(Class.forName("org.hamcrest.CoreMatchers").getProtectionDomain().getCodeSource().getLocation().toURI()),
                libs.resolve("hamcrest-core.jar"));
        try (InputStream input = SampleTest.class.getResourceAsStream(SampleTest.class.getSimpleName() + ".class");
             OutputStream output = Files.newOutputStream(Files
                     .createDirectories(classes.resolve(Javac.CLASSES + "sample"))
                     .resolve("SampleTest.class"))) {
            requireNonNull(input).transferTo(output);
        }
        BuildStepResult result = new JUnit4().apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of(
                        "dependencies", new BuildStepArgument(
                                dependencies,
                                Map.of(
                                        Path.of(Dependencies.LIBS + "junit.jar"), ChecksumStatus.ADDED,
                                        Path.of(Dependencies.LIBS + "hamcrest-core.jar"), ChecksumStatus.ADDED)),
                        "classes", new BuildStepArgument(
                                classes,
                                Map.of(Path.of(Javac.CLASSES + "sample/SampleTest.class"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(supplement.resolve("output")).content()
                .contains("JUnit")
                .contains(".Hello world!")
                .contains("OK (1 test)");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }
}
