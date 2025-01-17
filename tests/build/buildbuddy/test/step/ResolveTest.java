package build.buildbuddy.test.step;

import build.buildbuddy.*;
import build.buildbuddy.step.Resolve;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;

public class ResolveTest {

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
    public void can_resolve_dependencies() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, _, descriptors) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.forEach(descriptor -> {
                        resolved.put(descriptor, "");
                        resolved.put("transitive/" + descriptor, "");
                    });
                    return resolved;
                })).apply(
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
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("foo/qux",
                "foo/transitive/qux",
                "foo/baz",
                "foo/transitive/baz");
        for (String property : dependencies.stringPropertyNames()) {
            assertThat(dependencies.getProperty(property)).isEmpty();
        }
    }

    @Test
    public void can_resolve_dependencies_with_predefined_checksum() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "bar");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, _, descriptors) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.forEach(descriptor -> {
                resolved.put(descriptor, "");
                resolved.put("transitive/" + descriptor, "");
            });
            return resolved;
        })).apply(
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
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("foo/qux",
                "foo/transitive/qux",
                "foo/baz",
                "foo/transitive/baz");
        assertThat(dependencies.getProperty("foo/qux")).isEqualTo("bar");
        assertThat(dependencies.getProperty("foo/transitive/qux")).isEmpty();
        assertThat(dependencies.getProperty("foo/baz")).isEmpty();
        assertThat(dependencies.getProperty("foo/transitive/baz")).isEmpty();
    }

    @Test
    public void can_resolve_dependencies_with_resolved_checksum() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "bar");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, _, descriptors) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.forEach(descriptor -> {
                resolved.put(descriptor, "qux/" + descriptor);
                resolved.put("transitive/" + descriptor, "baz/" + descriptor);
            });
            return resolved;
        })).apply(
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
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("foo/qux",
                "foo/transitive/qux",
                "foo/baz",
                "foo/transitive/baz");
        assertThat(dependencies.getProperty("foo/qux")).isEqualTo("bar");
        assertThat(dependencies.getProperty("foo/transitive/qux")).isEqualTo("baz/qux");
        assertThat(dependencies.getProperty("foo/baz")).isEmpty();
        assertThat(dependencies.getProperty("foo/transitive/baz")).isEqualTo("baz/baz");
    }
}
