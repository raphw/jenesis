package build.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DependenciesTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, classes;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        classes = Files.createDirectory(root.resolve("classes"));
    }

    @Test
    public void can_resolve_dependencies() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        Path folder = Files.createDirectory(classes.resolve("sample"));
        Properties properties = new Properties();
        properties.setProperty("sample:coordinate", "SHA256:" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("coordinate".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("sample.dependencies"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "sample",
                coordinate -> () -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8))
        )).apply(Runnable::run, new BuildStepContext(previous, next), Map.of("dependencies", new BuildStepArgument(
                classes,
                Map.of(Path.of("sample/sample.dependencies"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Dependencies.FOLDER + "sample:coordinate")).content().isEqualTo("coordinate");
    }

    @Test
    public void rejects_dependency_with_mismatched_digest() throws IOException, NoSuchAlgorithmException {
        Path folder = Files.createDirectory(classes.resolve("sample"));
        Properties properties = new Properties();
        properties.setProperty("sample:coordinate", "SHA256:" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("other".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("sample.dependencies"))) {
            properties.store(writer, null);
        }
        assertThatThrownBy(() -> new Dependencies(Map.of(
                "sample",
                coordinate -> () -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8))
        )).apply(Runnable::run, new BuildStepContext(previous, next), Map.of("dependencies", new BuildStepArgument(
                classes,
                Map.of(Path.of("sample/sample.dependencies"), ChecksumStatus.ADDED)))).toCompletableFuture().get())
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mismatched digest for sample:coordinate");
        assertThat(next.resolve(Dependencies.FOLDER + "sample:coordinate")).content().isEqualTo("coordinate");
    }

    @Test
    public void can_retain_dependency_from_previous_run() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        try (Writer writer = Files.newBufferedWriter(Files.createDirectory(Files.createDirectory(previous)
                .resolve(Dependencies.FOLDER))
                .resolve("sample:coordinate"))) {
            writer.write("other");
        }
        Path folder = Files.createDirectory(classes.resolve("sample"));
        Properties properties = new Properties();
        properties.setProperty("sample:coordinate", "SHA256:" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("other".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("sample.dependencies"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "sample",
                coordinate -> () -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8))
        )).apply(Runnable::run, new BuildStepContext(previous, next), Map.of("dependencies", new BuildStepArgument(
                classes,
                Map.of(Path.of("sample/sample.dependencies"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(previous.resolve(Dependencies.FOLDER + "sample:coordinate")).content().isEqualTo("other");
        assertThat(next.resolve(Dependencies.FOLDER + "sample:coordinate")).content().isEqualTo("other");
    }
}
