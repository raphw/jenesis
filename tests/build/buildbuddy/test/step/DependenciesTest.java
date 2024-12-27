package build.buildbuddy.test.step;

import build.buildbuddy.*;
import build.buildbuddy.step.Dependencies;
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

    private Path previous, next, supplement, flattened;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        flattened = Files.createDirectory(root.resolve("flattened"));
    }

    @Test
    public void can_resolve_dependencies() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        Path folder = Files.createDirectory(flattened.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("bar".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("dependency.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "foo",
                bar -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                flattened,
                Map.of(Path.of(Dependencies.FLATTENED, "dependency.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_dependencies_from_file() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        Path folder = Files.createDirectory(flattened.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("bar".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("dependency.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "foo",
                bar -> {
                    Path file = Files.writeString(temporaryFolder.newFile(bar).toPath(), bar);
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
                flattened,
                Map.of(Path.of(Dependencies.FLATTENED, "dependency.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("bar");
    }

    @Test
    public void rejects_dependency_with_mismatched_digest() throws IOException, NoSuchAlgorithmException {
        Path folder = Files.createDirectory(flattened.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("other".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("dependency.properties"))) {
            properties.store(writer, null);
        }
        assertThatThrownBy(() -> new Dependencies(Map.of(
                "foo",
                bar -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                flattened,
                Map.of(Path.of(Dependencies.FLATTENED, "dependency.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get())
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mismatched digest for foo/bar");
        assertThat(next.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("bar");
    }

    @Test
    public void can_retain_dependency_from_previous_run() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        Files.writeString(Files
                .createDirectory(Files.createDirectory(previous).resolve(Dependencies.LIBS))
                .resolve("foo:bar"), "other");
        Path folder = Files.createDirectory(flattened.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("other".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("dependency.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "foo",
                bar -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                flattened,
                Map.of(Path.of(Dependencies.FLATTENED, "dependency.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(previous.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("other");
        assertThat(next.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("other");
    }

    @Test
    public void can_resolve_dependencies_without_hash() throws IOException, ExecutionException, InterruptedException {
        Path folder = Files.createDirectory(flattened.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "");
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("dependency.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "foo",
                bar -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                flattened,
                Map.of(Path.of(Dependencies.FLATTENED, "dependency.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_dependencies_from_file_without_hash() throws IOException, ExecutionException, InterruptedException {
        Path folder = Files.createDirectory(flattened.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "");
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("dependency.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "foo",
                bar -> {
                    Path file = Files.writeString(temporaryFolder.newFile(bar).toPath(), bar);
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
                flattened,
                Map.of(Path.of(Dependencies.FLATTENED, "dependency.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("bar");
    }

    @Test
    public void can_retain_dependency_from_previous_run_no_hash() throws IOException, ExecutionException, InterruptedException {
        Files.writeString(Files
                .createDirectory(Files.createDirectory(previous).resolve(Dependencies.LIBS))
                .resolve("foo:bar"), "other");
        Path folder = Files.createDirectory(flattened.resolve(Dependencies.FLATTENED));
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "");
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("dependency.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Dependencies(Map.of(
                "foo",
                bar -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(Runnable::run, new BuildStepContext(previous, next, supplement), Map.of("dependencies", new BuildStepArgument(
                flattened,
                Map.of(Path.of(Dependencies.FLATTENED, "dependency.properties"), ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        assertThat(previous.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("other");
        assertThat(next.resolve(Dependencies.LIBS + "foo:bar")).content().isEqualTo("other");
    }
}
