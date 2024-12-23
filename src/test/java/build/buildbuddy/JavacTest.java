package build.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

public class JavacTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path root, previous, target, sources;

    @Before
    public void setUp() throws Exception {
        root = temporaryFolder.newFolder().toPath();
        previous = root.resolve("previous");
        target = Files.createDirectory(root.resolve("target"));
        sources = Files.createDirectory(root.resolve("sources"));
    }

    @Test
    public void can_execute_javac() throws IOException, ExecutionException, InterruptedException {
        Path folder = Files.createDirectory(sources.resolve("sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        BuildStepResult result = new Javac().apply(Runnable::run, previous, target, Map.of("sources", new BuildStepArgument(
                sources,
                Map.of(Path.of("sample/Sample.java"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.useTarget()).isTrue();
        assertThat(target.resolve("sample/Sample.class")).isNotEmptyFile();
    }
}
