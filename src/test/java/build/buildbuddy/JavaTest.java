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
        try (InputStream input = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class");
             OutputStream output = Files.newOutputStream(folder.resolve("Sample.class"))) {
            requireNonNull(input).transferTo(output);
        }
        BuildStepResult result = Java.of("sample.Sample").apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("classes", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.class"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(supplement.resolve("output")).content().isEqualTo("Hello world!");
        assertThat(supplement.resolve("error")).isEmptyFile();
    }
}
