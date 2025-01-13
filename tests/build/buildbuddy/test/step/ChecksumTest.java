package build.buildbuddy.test.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.step.Checksum;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class ChecksumTest {

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
    public void can_resolve_checksums() throws IOException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
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
                                Path.of(BuildStep.DEPENDENCIES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("foo/qux", "foo/baz");
        assertThat(dependencies.getProperty("foo/qux")).isEqualTo("SHA256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA256").digest("qux".getBytes(StandardCharsets.UTF_8))));
        assertThat(dependencies.getProperty("foo/baz")).isEqualTo("SHA256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA256").digest("baz".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void retains_predefined_checksum() throws IOException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "baz");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
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
                                Path.of(BuildStep.DEPENDENCIES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("foo/qux", "foo/baz");
        assertThat(dependencies.getProperty("foo/qux")).isEqualTo("baz");
        assertThat(dependencies.getProperty("foo/baz")).isEqualTo("SHA256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA256").digest("baz".getBytes(StandardCharsets.UTF_8))));
    }
}
