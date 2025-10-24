package build.jenesis.test.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.Repository;
import build.jenesis.step.Resolve;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class ResolveTest {

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
    public void can_resolve_dependencies() throws IOException {
        Properties properties = new Properties();
        properties.setProperty("foo/qux", "");
        properties.setProperty("foo/baz", "");
        try (Writer writer = Files.newBufferedWriter(dependencies.resolve(BuildStep.DEPENDENCIES))) {
            properties.store(writer, null);
        }
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.forEach(descriptor -> {
                        resolved.put(prefix + "/" +descriptor, "");
                        resolved.put(prefix + "/transitive/" + descriptor, "");
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
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.forEach(descriptor -> {
                resolved.put(prefix + "/" + descriptor, "");
                resolved.put(prefix + "/transitive/" + descriptor, "");
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
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.forEach(descriptor -> {
                resolved.put(prefix + "/" + descriptor, "qux/" + descriptor);
                resolved.put(prefix + "/" + "transitive/" + descriptor, "baz/" + descriptor);
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
