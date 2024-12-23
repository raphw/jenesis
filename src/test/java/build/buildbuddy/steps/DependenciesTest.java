package build.buildbuddy.steps;

import build.buildbuddy.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DependenciesTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, dependencies;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectory(root.resolve("classes"));
    }

    @Test
    public void can_resolve_dependencies() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        Path folder = Files.createDirectory(dependencies.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("sample|coordinate", "SHA256|" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("coordinate".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("sample.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "sample",
                coordinate -> Optional.of(() -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8)))
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                dependencies,
                Map.of(Path.of(Dependencies.FLATTENED, "sample.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Dependencies.LIBS + "sample|coordinate")).content().isEqualTo("coordinate");
    }

    @Test
    public void can_resolve_dependencies_from_file() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        Path folder = Files.createDirectory(dependencies.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("sample|coordinate", "SHA256|" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("coordinate".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("sample.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "sample",
                coordinate -> {
                    Path file = Files.writeString(temporaryFolder.newFile(coordinate).toPath(), coordinate);
                    return Optional.of(new RepositoryItem() {
                        @Override
                        public InputStream toInputStream() {
                            throw new AssertionError();
                        }

                        @Override
                        public Optional<Path> getFile() {
                            return Optional.of(file);
                        }
                    });
                }
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                dependencies,
                Map.of(Path.of(Dependencies.FLATTENED, "sample.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Dependencies.LIBS + "sample|coordinate")).content().isEqualTo("coordinate");
    }

    @Test
    public void rejects_dependency_with_mismatched_digest() throws IOException, NoSuchAlgorithmException {
        Path folder = Files.createDirectory(dependencies.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("sample|coordinate", "SHA256|" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("other".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("sample.properties"))) {
            properties.store(writer, null);
        }
        assertThatThrownBy(() -> new Dependencies(Map.of(
                "sample",
                coordinate -> Optional.of(() -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8)))
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                dependencies,
                Map.of(Path.of(Dependencies.FLATTENED, "sample.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get())
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mismatched digest for sample|coordinate");
        assertThat(next.resolve(Dependencies.LIBS + "sample|coordinate")).content().isEqualTo("coordinate");
    }

    @Test
    public void can_retain_dependency_from_previous_run() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        Files.writeString(Files.createDirectory(Files.createDirectory(previous)
                        .resolve(Dependencies.LIBS))
                .resolve("sample|coordinate"), "other");
        Path folder = Files.createDirectory(dependencies.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("sample|coordinate", "SHA256|" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("other".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("sample.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "sample",
                coordinate -> Optional.of(() -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8)))
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                dependencies,
                Map.of(Path.of(Dependencies.FLATTENED, "sample.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(previous.resolve(Dependencies.LIBS + "sample|coordinate")).content().isEqualTo("other");
        assertThat(next.resolve(Dependencies.LIBS + "sample|coordinate")).content().isEqualTo("other");
    }
}
