package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.DependencyScope;
import build.jenesis.Repository;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.setProperty("main/compile/foo/baz", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors, _, _, _) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> {
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
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.TRANSITIVES));
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("compile/foo/qux",
                "compile/foo/transitive/qux",
                "compile/foo/baz",
                "compile/foo/transitive/baz");
        for (String property : dependencies.stringPropertyNames()) {
            assertThat(dependencies.getProperty(property)).isEmpty();
        }
    }

    @Test
    public void resolves_a_non_main_scope_through_its_base_resolver() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("plugin:kotlin/plugin:kotlin/maven/org.jetbrains/something", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Resolve(Map.of("maven", Repository.empty()), Map.of("maven", (_, prefix, _, descriptors, _, _, _) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                    return resolved;
                })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties resolved = SequencedProperties.ofFiles(next.resolve(BuildStep.TRANSITIVES));
        assertThat(resolved.stringPropertyNames()).containsExactly("plugin:kotlin/maven/org.jetbrains/something");
    }

    @Test
    public void can_resolve_dependencies_with_predefined_checksum() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "bar");
        properties.setProperty("main/compile/foo/baz", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors, _, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
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
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.TRANSITIVES));
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("compile/foo/qux",
                "compile/foo/transitive/qux",
                "compile/foo/baz",
                "compile/foo/transitive/baz");
        assertThat(dependencies.getProperty("compile/foo/qux")).isEqualTo("bar");
        assertThat(dependencies.getProperty("compile/foo/transitive/qux")).isEmpty();
        assertThat(dependencies.getProperty("compile/foo/baz")).isEmpty();
        assertThat(dependencies.getProperty("compile/foo/transitive/baz")).isEmpty();
    }

    @Test
    public void can_resolve_dependencies_with_resolved_checksum() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "bar");
        properties.setProperty("main/compile/foo/baz", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors, _, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
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
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.TRANSITIVES));
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("compile/foo/qux",
                "compile/foo/transitive/qux",
                "compile/foo/baz",
                "compile/foo/transitive/baz");
        assertThat(dependencies.getProperty("compile/foo/qux")).isEqualTo("bar");
        assertThat(dependencies.getProperty("compile/foo/transitive/qux")).isEqualTo("baz/qux");
        assertThat(dependencies.getProperty("compile/foo/baz")).isEqualTo("qux/baz");
        assertThat(dependencies.getProperty("compile/foo/transitive/baz")).isEqualTo("baz/baz");
    }

    @Test
    public void runtime_inherits_the_compile_mediated_version() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/lib", "");
        properties.setProperty("main/runtime/foo/lib", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors, bom, intent, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                String version = intent == DependencyScope.COMPILE ? "1.0" : bom.getOrDefault(descriptor, "FLOAT");
                resolved.put(prefix + "/" + descriptor + "/" + version, "");
            });
            return resolved;
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.TRANSITIVES));
        assertThat(dependencies.stringPropertyNames())
                .containsExactlyInAnyOrder("compile/foo/lib/1.0", "runtime/foo/lib/1.0");
    }

    @Test
    public void secondary_scope_in_a_custom_group_inherits_the_compile_mediated_version() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("custom/compile/foo/lib", "");
        properties.setProperty("custom/extra/foo/lib", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors, bom, intent, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                String version = intent == DependencyScope.COMPILE ? "1.0" : bom.getOrDefault(descriptor, "FLOAT");
                resolved.put(prefix + "/" + descriptor + "/" + version, "");
            });
            return resolved;
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.TRANSITIVES));
        assertThat(dependencies.stringPropertyNames())
                .containsExactlyInAnyOrder("compile/foo/lib/1.0", "extra/foo/lib/1.0");
    }

    @Test
    public void same_coordinate_in_distinct_groups_resolves_independently() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/lib", "");
        properties.setProperty("main/extra/foo/lib", "");
        properties.setProperty("other/extra/foo/lib", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Resolve(Map.of("foo", Repository.empty()), Map.of("foo", (_, prefix, _, descriptors, bom, intent, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                String version = intent == DependencyScope.COMPILE ? "1.0" : bom.getOrDefault(descriptor, "FLOAT");
                resolved.put(prefix + "/" + descriptor + "/" + version, "");
            });
            return resolved;
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.TRANSITIVES));
        assertThat(dependencies.stringPropertyNames())
                .containsExactlyInAnyOrder("compile/foo/lib/1.0", "extra/foo/lib/1.0", "extra/foo/lib/FLOAT");
    }

    @Test
    public void malformed_version_pin_fails_loudly() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("bar", "1.0");
        versions.store(dependencies.resolve(BuildStep.VERSIONS));
        Resolve resolve = new Resolve(Map.of("foo", Repository.empty()),
                Map.of("foo", (_, prefix, _, descriptors, _, _, _) -> new LinkedHashMap<>()));
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.VERSIONS),
                                ChecksumStatus.ADDED))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bar")
                .hasMessageContaining("<group>/<scope>/<repository>/<coordinate>");
    }
}
