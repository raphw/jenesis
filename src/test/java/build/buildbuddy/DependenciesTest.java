package build.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

public class DependenciesTest {

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
    public void can_resolve_dependencies() throws IOException, ExecutionException, InterruptedException {
        Path folder = Files.createDirectory(classes.resolve("sample"));
        Properties properties = new Properties();
        properties.setProperty("sample:coordinate", "");
        try (OutputStream output = Files.newOutputStream(folder.resolve("sample.dependencies"))) {
            properties.storeToXML(output, "Sample dependencies");
        }
        BuildStepResult result = new Dependencies(Map.of(
                "sample",
                coordinate -> new ByteArrayInputStream("foo".getBytes(StandardCharsets.UTF_8))
        )).apply(Runnable::run, previous, target, Map.of("dependencies", new BuildStepArgument(
                classes,
                Map.of(Path.of("sample/sample.dependencies"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(target.resolve("sample:coordinate")).content().isEqualTo("foo");
    }
}