package build.buildbuddy.steps;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
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
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class PropertyDependenciesTest {

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
    public void can_resolve_dependencies() throws IOException,
            ExecutionException,
            InterruptedException,
            NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectory(dependencies.resolve(PropertyDependencies.DEPENDENCIES))
                .resolve("dependencies.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new PropertyDependencies(
                Map.of("foo", descriptors -> descriptors.stream()
                        .flatMap(descriptor -> Stream.of(descriptor, "transitive/" + descriptor))
                        .toList()),
                Map.of("foo", coordinate -> Optional.of(
                        () -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8)))),
                "SHA256").apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(PropertyDependencies.DEPENDENCIES, "dependencies.properties"),
                                ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(Dependencies.FLATTENED + "dependencies.properties"))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("qux",
                "transitive/qux",
                "baz",
                "transitive/baz");
        for (String property : dependencies.stringPropertyNames()) {
            assertThat(dependencies.getProperty(property)).isEqualTo("SHA256/"
                    + Base64.getEncoder().encodeToString(MessageDigest
                    .getInstance("SHA256")
                    .digest(property.getBytes(StandardCharsets.UTF_8))));
        }
    }

    @Test
    public void can_resolve_dependencies_without_checksum() throws IOException,
            ExecutionException,
            InterruptedException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectory(dependencies.resolve(PropertyDependencies.DEPENDENCIES))
                .resolve("dependencies.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new PropertyDependencies(
                Map.of("foo", descriptors -> descriptors.stream()
                        .flatMap(descriptor -> Stream.of(descriptor, "transitive/" + descriptor))
                        .toList()),
                Map.of("foo", coordinate -> Optional.of(
                        () -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8)))),
                null).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(PropertyDependencies.DEPENDENCIES, "dependencies.properties"),
                                ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(Dependencies.FLATTENED + "dependencies.properties"))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("qux",
                "transitive/qux",
                "baz",
                "transitive/baz");
        for (String property : dependencies.stringPropertyNames()) {
            assertThat(dependencies.getProperty(property)).isEmpty();
        }
    }

    @Test
    public void can_resolve_dependencies_with_predefined_checksum() throws IOException,
            ExecutionException,
            InterruptedException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "bar");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectory(dependencies.resolve(PropertyDependencies.DEPENDENCIES))
                .resolve("dependencies.properties"))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new PropertyDependencies(
                Map.of("foo", descriptors -> descriptors.stream()
                        .flatMap(descriptor -> Stream.of(descriptor, "transitive/" + descriptor))
                        .toList()),
                Map.of("foo", coordinate -> Optional.of(
                        () -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8)))),
                null).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(PropertyDependencies.DEPENDENCIES, "dependencies.properties"),
                                ChecksumStatus.ADDED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(Dependencies.FLATTENED + "dependencies.properties"))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("qux",
                "transitive/qux",
                "baz",
                "transitive/baz");
        assertThat(dependencies.getProperty("qux")).isEqualTo("bar");
        assertThat(dependencies.getProperty("transitive/qux")).isEmpty();
        assertThat(dependencies.getProperty("baz")).isEmpty();
        assertThat(dependencies.getProperty("transitive/baz")).isEmpty();
    }
}
