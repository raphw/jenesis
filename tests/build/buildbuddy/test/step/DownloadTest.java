package build.buildbuddy.test.step;

import build.buildbuddy.*;
import build.buildbuddy.step.Download;
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
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DownloadTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, dependencies;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectory(root.resolve("dependencies"));
    }

    @Test
    public void can_resolve_dependencies() throws IOException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("bar".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Download(Map.of(
                "foo",
                (_, bar) -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8))))).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_dependencies_from_file() throws IOException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("bar".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Download(Map.of("foo", (_, bar) -> {
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
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("bar");
    }

    @Test
    public void rejects_dependency_with_mismatched_digest() throws IOException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("other".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        assertThatThrownBy(() -> new Download(Map.of(
                "foo",
                (_, bar) -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join())
                .cause()
                .isInstanceOf(RuntimeException.class)
                .cause()
                .hasMessageContaining("Mismatched digest for foo/bar");
        assertThat(next.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("bar");
    }

    @Test
    public void can_retain_dependency_from_previous_run() throws IOException, NoSuchAlgorithmException {
        Files.writeString(Files
                .createDirectory(Files.createDirectory(previous).resolve(BuildStep.ARTIFACTS))
                .resolve("foo-bar.jar"), "other");
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA256").digest("other".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Download(Map.of(
                "foo",
                (_, bar) -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(previous.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("other");
        assertThat(next.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("other");
    }

    @Test
    public void can_resolve_dependencies_without_hash() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "");
        try (BufferedWriter writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Download(Map.of(
                "foo",
                (_, bar) -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_dependencies_from_file_without_hash() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "");
        try (BufferedWriter writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Download(Map.of("foo", (_, bar) -> {
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
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("bar");
    }

    @Test
    public void can_retain_dependency_from_previous_run_no_hash() throws IOException, ExecutionException, InterruptedException {
        Files.writeString(Files
                .createDirectory(Files.createDirectory(previous).resolve(BuildStep.ARTIFACTS))
                .resolve("foo-bar.jar"), "other");
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "");
        try (BufferedWriter writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Download(Map.of(
                "foo",
                (_, bar) -> Optional.of(() -> new ByteArrayInputStream(bar.getBytes(StandardCharsets.UTF_8)))
        )).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(previous.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("other");
        assertThat(next.resolve(BuildStep.ARTIFACTS + "foo-bar.jar")).content().isEqualTo("other");
    }
}
