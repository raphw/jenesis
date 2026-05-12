package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Checksum;

import static org.assertj.core.api.Assertions.assertThat;

public class ChecksumTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, dependencies;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectory(root.resolve("dependencies"));
    }

    @Test
    public void can_resolve_checksums() throws IOException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.REQUIRES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Checksum("SHA256", Map.of(
                "foo",
                (_, coordinate) -> Optional.of(() -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8))))).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.REQUIRES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("foo/qux", "foo/baz");
        assertThat(dependencies.getProperty("foo/qux")).isEqualTo("SHA256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA256").digest("qux".getBytes(StandardCharsets.UTF_8))));
        assertThat(dependencies.getProperty("foo/baz")).isEqualTo("SHA256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA256").digest("baz".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void reuses_prior_checksum_without_fetching() throws IOException {
        Properties requires = new Properties();
        requires.setProperty("foo/qux", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.REQUIRES))) {
            requires.store(writer, null);
        }
        Path priorFolder = Files.createDirectory(root.resolve("prior"));
        Properties priorRequires = new Properties();
        priorRequires.setProperty("foo/qux", "SHA256/0123456789abcdef");
        try (Writer writer = Files.newBufferedWriter(priorFolder.resolve(BuildStep.REQUIRES))) {
            priorRequires.store(writer, null);
        }
        AtomicInteger fetches = new AtomicInteger();
        BuildStepResult result = new Checksum("SHA256", Map.of("foo", (_, coordinate) -> {
            fetches.incrementAndGet();
            return Optional.of(() -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8)));
        })).apply(
                Runnable::run,
                new BuildStepContext(priorFolder, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(fetches).hasValue(0);
        Properties output = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.REQUIRES))) {
            output.load(reader);
        }
        assertThat(output.getProperty("foo/qux")).isEqualTo("SHA256/0123456789abcdef");
    }

    @Test
    public void recomputes_when_prior_digest_uses_different_algorithm() throws IOException, NoSuchAlgorithmException {
        Properties requires = new Properties();
        requires.setProperty("foo/qux", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.REQUIRES))) {
            requires.store(writer, null);
        }
        Path priorFolder = Files.createDirectory(root.resolve("prior"));
        Properties priorRequires = new Properties();
        priorRequires.setProperty("foo/qux", "MD5/abcdef");
        try (Writer writer = Files.newBufferedWriter(priorFolder.resolve(BuildStep.REQUIRES))) {
            priorRequires.store(writer, null);
        }
        BuildStepResult result = new Checksum("SHA256", Map.of(
                "foo",
                (_, coordinate) -> Optional.of(() -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8))))).apply(
                Runnable::run,
                new BuildStepContext(priorFolder, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties output = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.REQUIRES))) {
            output.load(reader);
        }
        assertThat(output.getProperty("foo/qux")).isEqualTo("SHA256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA256").digest("qux".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void retains_predefined_checksum() throws IOException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "baz");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.REQUIRES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Checksum("SHA256", Map.of(
                "foo",
                (_, coordinate) -> Optional.of(() -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8))))).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.REQUIRES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("foo/qux", "foo/baz");
        assertThat(dependencies.getProperty("foo/qux")).isEqualTo("baz");
        assertThat(dependencies.getProperty("foo/baz")).isEqualTo("SHA256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA256").digest("baz".getBytes(StandardCharsets.UTF_8))));
    }
}
