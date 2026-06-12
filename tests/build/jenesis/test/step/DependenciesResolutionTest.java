package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.License;
import build.jenesis.Pinning;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Dependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DependenciesResolutionTest {

    @TempDir
    private Path root, artifacts;
    private Path previous, next, supplement, dependencies;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectory(root.resolve("dependencies"));
    }

    private Repository files(Map<String, String> contents) {
        return (_, coordinate) -> {
            String content = contents.getOrDefault(coordinate, coordinate);
            Path file;
            try {
                file = Files.write(
                        artifacts.resolve(coordinate.replace('/', '-') + ".jar"),
                        content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return Optional.of(RepositoryItem.ofFile(file));
        };
    }

    private static String sha256(String content) throws NoSuchAlgorithmException {
        return "SHA-256/" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String checksum(String content) {
        try {
            return sha256(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void can_resolve_dependencies() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.setProperty("main/compile/foo/baz", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, _, _) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> {
                        resolved.put(prefix + "/" +descriptor, "");
                        resolved.put(prefix + "/transitive/" + descriptor, "");
                    });
                    return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
                })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("main/compile/foo/qux",
                "main/compile/foo/transitive/qux",
                "main/compile/foo/baz",
                "main/compile/foo/transitive/baz");
        for (String property : dependencies.stringPropertyNames()) {
            assertThat(dependencies.getProperty(property)).doesNotContain("SHA");
        }
    }

    @Test
    public void writes_resolved_licenses_into_a_sidecar() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())),
                Map.of("foo", (executor, prefix, repositories, descriptors, bom, scope) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                    SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
                    vertices.put(prefix + "/qux", new Resolver.Vertex(null, null, false,
                            List.of(new License("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"))));
                    return new Resolver.Resolution(
                            Resolver.materializeAll(executor, repositories, prefix, resolved),
                            List.of(),
                            vertices);
                }))
                .apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                                dependencies,
                                Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties licenses = SequencedProperties.ofFiles(next.resolve("licenses.properties"));
        assertThat(licenses.getProperty("foo/qux#0#name")).isEqualTo("Apache-2.0");
        assertThat(licenses.getProperty("foo/qux#0#url")).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0.txt");
    }

    @Test
    public void writes_the_resolution_graph_to_a_sidecar() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())),
                Map.of("foo", (executor, prefix, repositories, descriptors, bom, scope) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                    SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
                    vertices.put("foo/qux", new Resolver.Vertex("1", "qux.module", false, List.of()));
                    return new Resolver.Resolution(
                            Resolver.materializeAll(executor, repositories, prefix, resolved),
                            List.of(new Resolver.Edge(null, "foo/qux/1", "1", "compile", true)),
                            vertices);
                }))
                .apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                                dependencies,
                                Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties graph = SequencedProperties.ofFiles(next.resolve("graph.properties"));
        assertThat(graph.getProperty("edge/0")).isEqualTo("main\tcompile\tfoo\ttrue\tcompile\t1\t\tfoo/qux/1");
        assertThat(graph.getProperty("vertex/main/compile/foo/qux")).isEqualTo("1\tqux.module\tfalse");
    }

    @Test
    public void resolves_a_non_main_scope_through_its_base_resolver() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("kotlinc/plugin/maven/org.jetbrains/something", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Dependencies(Map.of("maven", files(Map.of())), Map.of("maven", (executor, prefix, repositories, descriptors, _, _) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                    return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
                })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties resolved = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(resolved.stringPropertyNames()).containsExactly("kotlinc/plugin/maven/org.jetbrains/something");
    }

    @Test
    public void can_resolve_dependencies_with_predefined_checksum() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "bar");
        properties.setProperty("main/compile/foo/baz", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                resolved.put(prefix + "/" + descriptor, "");
                resolved.put(prefix + "/transitive/" + descriptor, "");
            });
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("main/compile/foo/qux",
                "main/compile/foo/transitive/qux",
                "main/compile/foo/baz",
                "main/compile/foo/transitive/baz");
        assertThat(dependencies.getProperty("main/compile/foo/qux")).endsWith(" bar");
        assertThat(dependencies.getProperty("main/compile/foo/transitive/qux")).doesNotContain("SHA");
        assertThat(dependencies.getProperty("main/compile/foo/baz")).doesNotContain("SHA");
        assertThat(dependencies.getProperty("main/compile/foo/transitive/baz")).doesNotContain("SHA");
    }

    @Test
    public void can_resolve_dependencies_with_resolved_checksum() throws IOException, NoSuchAlgorithmException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "bar");
        properties.setProperty("main/compile/foo/baz", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                resolved.put(prefix + "/" + descriptor, checksum(descriptor));
                resolved.put(prefix + "/" + "transitive/" + descriptor, checksum("transitive/" + descriptor));
            });
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(dependencies.stringPropertyNames()).containsExactlyInAnyOrder("main/compile/foo/qux",
                "main/compile/foo/transitive/qux",
                "main/compile/foo/baz",
                "main/compile/foo/transitive/baz");
        assertThat(dependencies.getProperty("main/compile/foo/qux")).endsWith(" bar");
        assertThat(dependencies.getProperty("main/compile/foo/transitive/qux")).endsWith(" " + sha256("transitive/qux"));
        assertThat(dependencies.getProperty("main/compile/foo/baz")).endsWith(" " + sha256("baz"));
        assertThat(dependencies.getProperty("main/compile/foo/transitive/baz")).endsWith(" " + sha256("transitive/baz"));
    }

    @Test
    public void group_pin_applies_to_all_scopes() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/lib", "");
        properties.setProperty("main/runtime/foo/lib", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/foo/lib", "1.0");
        versions.store(dependencies.resolve(BuildStep.VERSIONS));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, bom, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                String version = bom.getOrDefault(descriptor, "FLOAT");
                resolved.put(prefix + "/" + descriptor + "/" + version, "");
            });
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.VERSIONS),
                                Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(dependencies.stringPropertyNames())
                .containsExactlyInAnyOrder("main/compile/foo/lib/1.0", "main/runtime/foo/lib/1.0");
    }

    @Test
    public void group_pin_applies_to_every_scope_in_a_custom_group() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("custom/compile/foo/lib", "");
        properties.setProperty("custom/extra/foo/lib", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("custom/foo/lib", "1.0");
        versions.store(dependencies.resolve(BuildStep.VERSIONS));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, bom, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                String version = bom.getOrDefault(descriptor, "FLOAT");
                resolved.put(prefix + "/" + descriptor + "/" + version, "");
            });
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.VERSIONS),
                                Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(dependencies.stringPropertyNames())
                .containsExactlyInAnyOrder("custom/compile/foo/lib/1.0", "custom/extra/foo/lib/1.0");
    }

    @Test
    public void same_coordinate_in_distinct_groups_resolves_independently() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/lib", "");
        properties.setProperty("main/extra/foo/lib", "");
        properties.setProperty("other/extra/foo/lib", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/foo/lib", "1.0");
        versions.store(dependencies.resolve(BuildStep.VERSIONS));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, bom, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                String version = bom.getOrDefault(descriptor, "FLOAT");
                resolved.put(prefix + "/" + descriptor + "/" + version, "");
            });
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        })).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.VERSIONS),
                                Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties dependencies = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(dependencies.stringPropertyNames())
                .containsExactlyInAnyOrder("main/compile/foo/lib/1.0", "main/extra/foo/lib/1.0", "other/extra/foo/lib/FLOAT");
    }

    @Test
    public void malformed_version_pin_fails_loudly() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("bar", "1.0");
        versions.store(dependencies.resolve(BuildStep.VERSIONS));
        Dependencies resolve = new Dependencies(Map.of("foo", Repository.empty()),
                Map.of("foo", (_, _, _, _, _, _) -> new Resolver.Resolution(new LinkedHashMap<>(), List.of(), new LinkedHashMap<>())));
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.VERSIONS),
                                Checksum.of(ChecksumStatus.ADDED)))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bar")
                .hasMessageContaining("<group>/<repository>/<coordinate>");
    }

    @Test
    public void rejects_dependency_with_mismatched_digest() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/bar", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        Dependencies resolve = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, checksum("other")));
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        }));
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join())
                .hasStackTraceContaining("Mismatched digest for bar");
    }

    @Test
    public void strict_pinning_rejects_unpinned_external_dependency() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/bar", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        Dependencies resolve = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        })).pinning(Pinning.STRICT);
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No checksum pinned for foo-bar.jar")
                .hasMessageContaining("strict pinning");
    }

    @Test
    public void ignore_pinning_drops_checksums_from_the_dependency_index() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/bar", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        })).pinning(Pinning.IGNORE).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties index = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(index.getProperty("main/compile/foo/bar")).isEqualTo("resolved/bar.jar");
        assertThat(next.resolve("resolved/bar.jar")).content().isEqualTo("bar");
    }

    @Test
    public void ignore_pinning_keeps_classifier_selection_floating() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/foo/bar", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/foo/bar", ":win:1.0 SHA-256/aaaa");
        versions.setProperty("main/foo/plain", "2.0 SHA-256/bbbb");
        versions.store(dependencies.resolve(BuildStep.VERSIONS));
        SequencedMap<String, String> received = new LinkedHashMap<>();
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, pins, _) -> {
            received.putAll(pins);
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        })).pinning(Pinning.IGNORE).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.VERSIONS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(received).containsOnly(Map.entry("bar", ":win"));
    }

    @Test
    public void rejects_conflicting_pins_across_scopes() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/bar", "SHA-256/aaaa");
        properties.setProperty("main/runtime/foo/bar", "SHA-256/bbbb");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        Dependencies resolve = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", (executor, prefix, repositories, descriptors, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
            return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
        }));
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join())
                .hasStackTraceContaining("Conflicting checksums pinned for foo-bar.jar");
    }
}
