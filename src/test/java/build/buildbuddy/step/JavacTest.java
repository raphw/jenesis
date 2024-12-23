package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
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

    private Path previous, next, supplement, sources;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        sources = Files.createDirectory(root.resolve("sources"));
    }

    @Test
    public void can_execute_javac() throws IOException, ExecutionException, InterruptedException {
        Path folder = Files.createDirectories(sources.resolve(Resolve.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        BuildStepResult result = new Javac().apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
    }
}
