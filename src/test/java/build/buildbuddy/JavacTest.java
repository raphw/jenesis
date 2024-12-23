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
    public void name() throws IOException, ExecutionException, InterruptedException {
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("Sample.java"))) {
            writer.append("public class Sample { }");
            writer.newLine();
        }
        String result = new Javac().apply(Runnable::run, previous, target, Map.of("sources", new BuildResult(
                sources,
                new ChecksumNopDiff().read(root.resolve("checksums"), sources)))).toCompletableFuture().get();
        assertThat(result).isNotNull();
        assertThat(target.resolve("Sample.class")).isNotEmptyFile();
    }
}