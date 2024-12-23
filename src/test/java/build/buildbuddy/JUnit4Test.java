package build.buildbuddy;

import org.hamcrest.CoreMatchers;
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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.in;

public class JUnit4Test {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, dependencies, classes;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        dependencies = Files.createDirectory(root.resolve("dependencies"));
        classes = Files.createDirectory(root.resolve("classes"));
    }

    @Test
    public void can_execute_java() throws IOException, ExecutionException, InterruptedException, URISyntaxException {
        Files.copy(
                Path.of(JUnitCore.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                dependencies.resolve("junit.jar"));
        Files.copy(
                Path.of(CoreMatchers.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                dependencies.resolve("hamcrest-core.jar"));
        Path folder = Files.createDirectory(classes.resolve("sample"));
        try (
                InputStream input = SampleTest.class.getResourceAsStream(SampleTest.class.getSimpleName() + ".class");
                OutputStream output = Files.newOutputStream(folder.resolve("SampleTest.class"))
        ) {
            requireNonNull(input).transferTo(output);
        }
        BuildStepResult result = new JUnit4().apply(Runnable::run,
                new BuildStepContext(previous, next),
                Map.of(
                        "dependencies", new BuildStepArgument(
                                dependencies,
                                Map.of(
                                        Path.of("junit.jar"), ChecksumStatus.ADDED,
                                        Path.of("hamcrest-core.jar"), ChecksumStatus.ADDED)),
                        "classes", new BuildStepArgument(
                                classes,
                                Map.of(Path.of("sample/SampleTest.class"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("output")).content()
                .contains("JUnit")
                .contains(".Hello world!")
                .contains("OK (1 test)");
        assertThat(next.resolve("error")).isEmptyFile();
    }

}