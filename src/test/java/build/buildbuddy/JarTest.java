package build.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sample.Sample;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class JarTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path root, previous, target, classes;

    @Before
    public void setUp() throws Exception {
        root = temporaryFolder.newFolder().toPath();
        previous = root.resolve("previous");
        target = Files.createDirectory(root.resolve("target"));
        classes = Files.createDirectory(root.resolve("classes"));
    }

    @Test
    public void can_execute_jar() throws IOException, ExecutionException, InterruptedException {
        Path folder = Files.createDirectory(classes.resolve("sample"));
        try (
                InputStream input = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class");
                OutputStream output = Files.newOutputStream(folder.resolve("Sample.class"))
        ) {
            requireNonNull(input).transferTo(output);
        }
        BuildStepResult result = new Jar().apply(Runnable::run, previous, target, Map.of("sources", new BuildStepArgument(
                classes,
                Map.of(Path.of("sample/Sample.class"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.useTarget()).isTrue();
        assertThat(target.resolve("artifact.jar")).isNotEmptyFile();
    }
}
