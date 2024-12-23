package build.buildbuddy.test.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.step.Java;
import build.buildbuddy.step.Javac;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import build.buildbuddy.sample.Sample;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class JavaTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, classes;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        classes = Files.createDirectory(root.resolve("classes"));
    }

    @Test
    public void can_execute_java() throws IOException, ExecutionException, InterruptedException {
        Path folder = Files.createDirectories(classes.resolve(Javac.CLASSES + "sample"));
        try (InputStream input = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(input), folder.resolve("Sample.class"));
        }
        BuildStepResult result = Java.of("buiild.buildbuddy.sample.Sample").apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("classes", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.class"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(supplement.resolve("output")).content().isEqualTo("Hello world!");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }
}
