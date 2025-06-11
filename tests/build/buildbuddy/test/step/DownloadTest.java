package build.buildbuddy.test.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.RepositoryItem;
import build.buildbuddy.step.Download;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DownloadTest {

    @TempDir
    private Path root, files;
    private Path previous, next, supplement, dependencies;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectory(root.resolve("dependencies"));
    }

    @Test
    public void can_resolve_dependencies() throws IOException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/bar", "SHA256/" + HexFormat.of().formatHex(
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
        properties.setProperty("foo/bar", "SHA256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA256").digest("bar".getBytes(StandardCharsets.UTF_8))));
        try (BufferedWriter writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Download(Map.of("foo", (_, bar) -> {
            Path file = Files.writeString(files.resolve(bar), bar);
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
        properties.setProperty("foo/bar", "SHA256/" + HexFormat.of().formatHex(
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
        properties.setProperty("foo/bar", "SHA256/" + HexFormat.of().formatHex(
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
            Path file = Files.writeString(files.resolve(bar), bar);
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
    public void can_retain_dependency_from_previous_run_no_hash() throws IOException {
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
