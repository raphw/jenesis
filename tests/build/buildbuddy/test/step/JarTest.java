package build.buildbuddy.test.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sample.Sample;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class JarTest {

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
    public void can_execute_jar() throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javac.CLASSES));
        try (InputStream inputStream = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(inputStream), Files
                    .createDirectory(folder.resolve("sample"))
                    .resolve("Sample.class"));
        }
        BuildStepResult result = new Jar().apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.class"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "classes.jar")).isNotEmptyFile();
    }
}
