package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.DependencyScope;
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
            int colon = coordinate.lastIndexOf(':');
            String identifier = colon < 0 ? coordinate : coordinate.substring(0, colon);
            String type = colon < 0 ? "jar" : coordinate.substring(colon + 1);
            Path file;
            try {
                file = Files.write(
                        artifacts.resolve(identifier.replace('/', '-') + "." + type),
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
    public void passes_module_aliases_to_resolver() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties aliases = new SequencedProperties();
        aliases.setProperty("main/foo/toolkit.lib", "org.example/plain-lib");
        aliases.store(dependencies.resolve(BuildStep.ALIASES));
        SequencedMap<String, String> observed = new LinkedHashMap<>();
        Resolver capturing = new Resolver() {
            @Override
            public Resolver.Resolution dependencies(Executor executor,
                                                    String prefix,
                                                    Map<String, Repository> repositories,
                                                    SequencedMap<String, SequencedSet<String>> descriptors,
                                                    SequencedMap<String, String> versions,
                                                    DependencyScope scope) throws IOException {
                SequencedMap<String, String> resolved = new LinkedHashMap<>();
                descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                return new Resolver.Resolution(Resolver.materializeAll(executor, repositories, prefix, resolved), List.of(), new LinkedHashMap<>());
            }

            @Override
            public Resolver.Resolution dependencies(Executor executor,
                                                    String prefix,
                                                    Map<String, Repository> repositories,
                                                    SequencedMap<String, SequencedSet<String>> descriptors,
                                                    SequencedMap<String, String> versions,
                                                    SequencedMap<String, String> aliases,
                                                    DependencyScope scope) throws IOException {
                observed.putAll(aliases);
                return dependencies(executor, prefix, repositories, descriptors, versions, scope);
            }
        };
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())), Map.of("foo", capturing)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(BuildStep.REQUIRES),
                                Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.ALIASES),
                                Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(observed).containsEntry("toolkit.lib", "org.example/plain-lib");
    }

    @Test
    public void can_resolve_dependencies_from_streaming_repository() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        // A repository without local files - like the Maven repository when no ~/.m2 exists and
        // no artifacts cache is configured - only streams its items; the step must materialize
        // them into its own output rather than fail.
        Repository streaming = (_, coordinate) -> Optional.of(
                () -> new ByteArrayInputStream(coordinate.getBytes(StandardCharsets.UTF_8)));
        BuildStepResult result = new Dependencies(Map.of("foo", streaming), Map.of("foo", (executor, prefix, repositories, descriptors, _, _) -> {
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
        assertThat(SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES)).stringPropertyNames())
                .containsExactly("main/compile/foo/qux");
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
                            List.of(new License(null, null, "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"))));
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
        assertThat(licenses.getProperty("foo/qux#0#name"))
                .as("the declared name is captured verbatim")
                .isEqualTo("Apache License 2.0");
        assertThat(licenses.getProperty("foo/qux#0#url")).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0.txt");
        assertThat(licenses.getProperty("foo/qux#0#id"))
                .as("the declared name is normalized to its SPDX identifier at extraction")
                .isEqualTo("Apache-2.0");
        assertThat(licenses.getProperty("foo/qux#0#category"))
                .as("the SPDX identifier is classified")
                .isEqualTo("permissive");
    }

    @Test
    public void appends_license_aliases_and_categories_from_input_files() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        Path licenses = Files.createDirectory(root.resolve("license-config"));
        Files.writeString(licenses.resolve(Dependencies.SPDX),
                "alias/acme-license=Acme-1.0\ncategory/Acme-1.0=permissive\n");
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())),
                Map.of("foo", (executor, prefix, repositories, descriptors, bom, scope) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                    SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
                    vertices.put(prefix + "/qux", new Resolver.Vertex(null, null, false,
                            List.of(new License(null, null, "Acme-License", null))));
                    return new Resolver.Resolution(
                            Resolver.materializeAll(executor, repositories, prefix, resolved),
                            List.of(),
                            vertices);
                }))
                .apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "dependencies", new BuildStepArgument(dependencies,
                                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED))),
                                "licenses", new BuildStepArgument(licenses, Map.of()))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties sidecar = SequencedProperties.ofFiles(next.resolve("licenses.properties"));
        assertThat(sidecar.getProperty("foo/qux#0#id"))
                .as("a supplied license-aliases.properties file extends the built-in alias table")
                .isEqualTo("Acme-1.0");
        assertThat(sidecar.getProperty("foo/qux#0#category"))
                .as("a supplied license-categories.properties file extends the built-in category table")
                .isEqualTo("permissive");
    }

    @Test
    public void an_empty_spdx_value_removes_the_matching_built_in_entry() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        Path licenses = Files.createDirectory(root.resolve("license-config"));
        Files.writeString(licenses.resolve(Dependencies.SPDX), "category/Apache-2.0=\n");
        BuildStepResult result = new Dependencies(Map.of("foo", files(Map.of())),
                Map.of("foo", (executor, prefix, repositories, descriptors, bom, scope) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                    SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
                    vertices.put(prefix + "/qux", new Resolver.Vertex(null, null, false,
                            List.of(new License("Apache-2.0", null, null, null))));
                    return new Resolver.Resolution(
                            Resolver.materializeAll(executor, repositories, prefix, resolved),
                            List.of(),
                            vertices);
                }))
                .apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of(
                                "dependencies", new BuildStepArgument(dependencies,
                                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED))),
                                "licenses", new BuildStepArgument(licenses, Map.of()))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties sidecar = SequencedProperties.ofFiles(next.resolve("licenses.properties"));
        assertThat(sidecar.getProperty("foo/qux#0#id")).isEqualTo("Apache-2.0");
        assertThat(sidecar.getProperty("foo/qux#0#category"))
                .as("an empty value removes the built-in Apache-2.0 classification")
                .isNull();
    }

    @Test
    public void an_unprefixed_spdx_key_fails_the_build() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("main/compile/foo/qux", "");
        properties.store(dependencies.resolve(BuildStep.REQUIRES));
        Path licenses = Files.createDirectory(root.resolve("license-config"));
        Files.writeString(licenses.resolve(Dependencies.SPDX), "bogus/apache=Apache-2.0\n");
        Dependencies step = new Dependencies(Map.of("foo", files(Map.of())),
                Map.of("foo", (executor, prefix, repositories, descriptors, bom, scope) -> {
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                    return new Resolver.Resolution(
                            Resolver.materializeAll(executor, repositories, prefix, resolved),
                            List.of(),
                            new LinkedHashMap<>());
                }));
        assertThatThrownBy(() -> step.apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "dependencies", new BuildStepArgument(dependencies,
                                Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED))),
                        "licenses", new BuildStepArgument(licenses, Map.of())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected key to be prefixed")
                .hasMessageContaining("bogus/apache");
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
    public void ignore_pinning_keeps_classifiers_and_versionless_declaration_management() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/foo/bar", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/foo/bar", ":win:1.0 SHA-256/aaaa");
        versions.setProperty("main/foo/plain", "2.0 SHA-256/bbbb");
        versions.setProperty("main/foo/transitive", ":mac:3.0 SHA-256/cccc");
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
        assertThat(received).containsOnly(Map.entry("bar", ":win:1.0"), Map.entry("transitive", ":mac"));
    }

    @Test
    public void ignore_pinning_keeps_managed_version_only_for_versionless_declaration() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/foo/bar", "");
        requires.setProperty("main/compile/foo/other/2.0", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/foo/bar", "1.0 SHA-256/aaaa");
        versions.setProperty("main/foo/other", "1.5 SHA-256/bbbb");
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
        assertThat(received).containsOnly(Map.entry("bar", "1.0"));
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

    private Resolver versioning() {
        return (executor, prefix, repositories, descriptors, bom, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            descriptors.sequencedKeySet().forEach(descriptor -> {
                String pin = bom.getOrDefault(descriptor, "FLOAT");
                int space = pin.indexOf(' ');
                resolved.put(prefix + "/" + descriptor + "/" + (space < 0 ? pin : pin.substring(0, space)),
                        space < 0 ? "" : pin.substring(space + 1));
            });
            return new Resolver.Resolution(
                    Resolver.materializeAll(executor, repositories, prefix, resolved),
                    List.of(),
                    new LinkedHashMap<>());
        };
    }

    @Test
    public void bom_entries_manage_resolution_and_local_pins_win() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/bar", "");
        requires.setProperty("main/compile/module/qux", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/module/bar", "9.9");
        versions.store(dependencies.resolve(BuildStep.VERSIONS));
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of("acme.platform/1.0:properties", "bar = 1.0\nqux = 2.0\n"))),
                Map.of("module", versioning())).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.VERSIONS), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties index = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(index.stringPropertyNames()).containsExactlyInAnyOrder(
                "main/compile/module/bar/9.9",
                "main/compile/module/qux/2.0");
    }

    @Test
    public void resolved_boms_are_written_for_the_pin_step() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/qux", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0");
        boms.setProperty("entry/main/module/extra", "3.0");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of("acme.platform/1.0:properties", "qux = 2.0\n"))),
                Map.of("module", versioning())).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties resolved = SequencedProperties.ofFiles(next.resolve(BuildStep.BOMS));
        assertThat(resolved.stringPropertyNames()).containsExactlyInAnyOrder(
                "bom/main/module/acme.platform/1.0",
                "entry/main/module/extra",
                "entry/main/module/qux");
        assertThat(next.resolve(resolved.getProperty("bom/main/module/acme.platform/1.0")))
                .content().isEqualTo("qux = 2.0\n");
        assertThat(resolved.getProperty("entry/main/module/qux")).isEqualTo("2.0");
        assertThat(resolved.getProperty("entry/main/module/extra")).isEqualTo("3.0");
    }

    @Test
    public void floating_bom_fetches_unversioned_coordinate() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/qux", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of("acme.platform:properties", "qux = 2.0\n"))),
                Map.of("module", versioning())).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties index = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(index.stringPropertyNames()).containsExactly("main/compile/module/qux/2.0");
    }

    @Test
    public void first_declared_bom_wins() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/qux", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/first", "1.0");
        boms.setProperty("bom/main/module/second", "1.0");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of(
                        "first/1.0:properties", "qux = 1.0\n",
                        "second/1.0:properties", "qux = 2.0\n"))),
                Map.of("module", versioning())).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties index = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(index.stringPropertyNames()).containsExactly("main/compile/module/qux/1.0");
    }

    @Test
    public void expanded_local_bom_entries_apply_without_fetching() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/qux", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("entry/main/module/qux", "3.0");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of())),
                Map.of("module", versioning())).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties index = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(index.stringPropertyNames()).containsExactly("main/compile/module/qux/3.0");
    }

    @Test
    public void mismatched_bom_digest_fails_the_fetch() throws IOException {
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0 " + checksum("other"));
        boms.store(dependencies.resolve(BuildStep.BOMS));
        Dependencies resolve = new Dependencies(
                Map.of("module", files(Map.of("acme.platform/1.0:properties", "qux = 2.0\n"))),
                Map.of("module", versioning()));
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join())
                .hasStackTraceContaining("Failed to fetch BOM main/module/acme.platform")
                .hasStackTraceContaining("Mismatched digest");
    }

    @Test
    public void versions_pinning_skips_bom_digest_validation() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/qux", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0 " + checksum("other"));
        boms.store(dependencies.resolve(BuildStep.BOMS));
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of("acme.platform/1.0:properties", "qux = 2.0\n"))),
                Map.of("module", versioning())).pinning(Pinning.VERSIONS).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties index = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(index.stringPropertyNames()).containsExactly("main/compile/module/qux/2.0");
    }

    @Test
    public void strict_pinning_rejects_hashless_bom_reference() throws IOException {
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        Dependencies resolve = new Dependencies(
                Map.of("module", files(Map.of("acme.platform/1.0:properties", "qux = 2.0\n"))),
                Map.of("module", versioning())).pinning(Pinning.STRICT);
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No checksum pinned for BOM main/module/acme.platform")
                .hasMessageContaining("strict pinning");
    }

    @Test
    public void strict_pinning_accepts_bom_entry_checksums() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/bar", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        String content = "bar = 1.0 " + checksum("bar/1.0") + "\n";
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0 " + checksum(content));
        boms.store(dependencies.resolve(BuildStep.BOMS));
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of("acme.platform/1.0:properties", content))),
                Map.of("module", versioning())).pinning(Pinning.STRICT).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        SequencedProperties index = SequencedProperties.ofFiles(next.resolve(BuildStep.DEPENDENCIES));
        assertThat(index.getProperty("main/compile/module/bar/1.0")).endsWith(" " + checksum("bar/1.0"));
    }

    @Test
    public void ignore_pinning_skips_bom_validation_and_keeps_versionless_declaration_entries() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/bar", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0 " + checksum("other"));
        boms.store(dependencies.resolve(BuildStep.BOMS));
        SequencedMap<String, String> received = new LinkedHashMap<>();
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of("acme.platform/1.0:properties", "bar = 1.0 SHA-256/aaaa\n"))),
                Map.of("module", (executor, prefix, repositories, descriptors, pins, _) -> {
                    received.putAll(pins);
                    SequencedMap<String, String> resolved = new LinkedHashMap<>();
                    descriptors.sequencedKeySet().forEach(descriptor -> resolved.put(prefix + "/" + descriptor, ""));
                    return new Resolver.Resolution(
                            Resolver.materializeAll(executor, repositories, prefix, resolved),
                            List.of(),
                            new LinkedHashMap<>());
                })).pinning(Pinning.IGNORE).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(received).containsOnly(Map.entry("bar", "1.0"));
    }

    @Test
    public void unknown_repository_for_bom_fails_loudly() throws IOException {
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/nope/acme.platform", "1.0");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        Dependencies resolve = new Dependencies(
                Map.of("module", files(Map.of())),
                Map.of("module", versioning()));
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown repository for BOM: main/nope/acme.platform");
    }

    @Test
    public void unresolved_bom_fails_loudly() throws IOException {
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        Dependencies resolve = new Dependencies(
                Map.of("module", (_, _) -> Optional.empty()),
                Map.of("module", versioning()));
        assertThatThrownBy(() -> resolve.apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join())
                .hasStackTraceContaining("Failed to fetch BOM main/module/acme.platform")
                .hasStackTraceContaining("Unresolved");
    }

    @Test
    public void bom_entries_reach_managed_prefixes() throws IOException {
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("main/compile/module/bar", "");
        requires.store(dependencies.resolve(BuildStep.REQUIRES));
        SequencedProperties boms = new SequencedProperties();
        boms.setProperty("bom/main/module/acme.platform", "1.0");
        boms.store(dependencies.resolve(BuildStep.BOMS));
        SequencedMap<String, String> received = new LinkedHashMap<>();
        Resolver resolver = new Resolver() {
            @Override
            public Resolver.Resolution dependencies(Executor executor,
                                                    String prefix,
                                                    Map<String, Repository> repositories,
                                                    SequencedMap<String, SequencedSet<String>> coordinates,
                                                    SequencedMap<String, String> versions,
                                                    DependencyScope scope) throws IOException {
                received.putAll(versions);
                SequencedMap<String, String> resolved = new LinkedHashMap<>();
                coordinates.sequencedKeySet().forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
                return new Resolver.Resolution(
                        Resolver.materializeAll(executor, repositories, prefix, resolved),
                        List.of(),
                        new LinkedHashMap<>());
            }

            @Override
            public SequencedSet<String> managedPrefixes() {
                return new LinkedHashSet<>(List.of("maven"));
            }
        };
        BuildStepResult result = new Dependencies(
                Map.of("module", files(Map.of("acme.platform/1.0:properties", "org.slf4j/slf4j-api = 2.0.17\n"))),
                Map.of("module", resolver)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(Path.of(BuildStep.REQUIRES), Checksum.of(ChecksumStatus.ADDED),
                                Path.of(BuildStep.BOMS), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(received).containsEntry("org.slf4j/slf4j-api", "2.0.17");
    }
}
