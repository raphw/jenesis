package build.jenesis.test.module;

import module java.base;
import java.util.jar.Attributes;
import build.jenesis.DependencyScope;
import module org.junit.jupiter.api;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.module.ModularJarResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ModularJarResolverTest {

    @TempDir
    private Path jars;

    @Test
    public void can_parse_module_info() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", require("transitive", 0));
                        case "transitive" -> toJar("transitive", require("last", 0));
                        case "last" -> toJar("last");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root",
                "foo/transitive",
                "foo/last");
    }

    @Test
    public void emits_followed_and_not_followed_module_edges() throws IOException {
        Resolver.Resolution resolution = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", require("a", 0), require("b", 0));
                        case "a" -> toJar("a");
                        case "b" -> toJar("b", require("a", 0));
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE);
        List<String> followedEdges = new ArrayList<>();
        List<String> notFollowedEdges = new ArrayList<>();
        for (Resolver.Edge edge : resolution.edges()) {
            (edge.followed() ? followedEdges : notFollowedEdges).add(edge.parent() + " -> " + edge.coordinate());
        }
        assertThat(followedEdges).containsExactly(
                "null -> foo/root",
                "foo/root -> foo/a",
                "foo/root -> foo/b");
        assertThat(notFollowedEdges).containsExactly("foo/b -> foo/a");
    }

    @Test
    public void skips_non_transitive_static_requires_in_compile_scope() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", require("optional", ClassFile.ACC_STATIC_PHASE));
                        case "optional" -> toJar("optional");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root");
    }

    @Test
    public void includes_static_transitive_requires_in_compile_scope() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", require("propagated",
                                ClassFile.ACC_STATIC_PHASE | ClassFile.ACC_TRANSITIVE));
                        case "propagated" -> toJar("propagated");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root",
                "foo/propagated");
    }

    @Test
    public void emits_transitive_requires_in_sorted_order_independent_of_declaration_order() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root",
                                require("zeta", 0),
                                require("alpha", 0),
                                require("middle", 0));
                        case "alpha", "middle", "zeta" -> toJar(coordinate);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root",
                "foo/alpha",
                "foo/middle",
                "foo/zeta");
    }

    @Test
    public void skips_static_transitive_requires_in_runtime_scope() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", require("propagated",
                                ClassFile.ACC_STATIC_PHASE | ClassFile.ACC_TRANSITIVE));
                        case "propagated" -> toJar("propagated");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.RUNTIME).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root");
    }

    private static ModuleRequireInfo require(String name, int flags) {
        return ModuleRequireInfo.of(ModuleDesc.of(name), flags, null);
    }

    private static ModuleRequireInfo require(String name, int flags, String compiledVersion) {
        return ModuleRequireInfo.of(ModuleDesc.of(name), flags, compiledVersion);
    }

    private RepositoryItem toJar(String module, ModuleRequireInfo... requires) throws IOException {
        return toJar(module, null, requires);
    }

    private RepositoryItem toJar(String module, String version, ModuleRequireInfo... requires) throws IOException {
        Path file = Files.createTempFile(jars, module, ".jar");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(file))) {
            jarOutputStream.putNextEntry(new JarEntry("module-info.class"));
            jarOutputStream.write(ClassFile.of().buildModule(ModuleAttribute.of(
                    ModuleDesc.of(module),
                    builder -> {
                        if (version != null) {
                            builder.moduleVersion(version);
                        }
                        builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), 0, null));
                        for (ModuleRequireInfo require : requires) {
                            builder.requires(require);
                        }
                    })));
            jarOutputStream.closeEntry();
        }
        return RepositoryItem.ofFile(file);
    }

    @Test
    public void uses_version_from_module_info_class() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.2.3");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root/1.2.3");
    }

    @Test
    public void unversioned_module_info_yields_unversioned_coordinate() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", (String) null);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root");
    }

    @Test
    public void rejects_module_with_unexpected_name() {
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("imposter");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root")
                .hasMessageContaining("imposter");
    }

    @Test
    public void input_pin_drives_versioned_fetch() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root/9.9" -> toJar("root", "9.9");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", "9.9")),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root/9.9");
    }

    @Test
    public void input_pin_rejects_mismatched_module_info_version() {
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root/9.9" -> toJar("root", "1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", "9.9")),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9.9")
                .hasMessageContaining("1.0");
    }

    @Test
    public void input_pin_does_not_fall_back_to_unversioned_coordinate() {
        Map<String, String> fetched = new LinkedHashMap<>();
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", "9.9")),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No module found for root");
        assertThat(fetched).containsOnlyKeys("root/9.9");
    }

    @Test
    public void tolerates_version_mismatch_when_automatic_modules_are_allowed() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(true).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root/9.9" -> toJar("root", "1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", "9.9")),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root/9.9");
    }

    @Test
    public void input_pin_supplies_version_for_unversioned_module() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root/7.0" -> toJar("root", (String) null);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", "7.0")),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root/7.0");
    }

    @Test
    public void transitive_carries_its_own_module_info_version() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("transitive", 0));
                        case "transitive" -> toJar("transitive", "2.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root/1.0",
                "foo/transitive/2.0");
    }

    @Test
    public void mixed_versioned_and_unversioned_transitives() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0",
                                require("alpha", 0),
                                require("beta", 0));
                        case "alpha" -> toJar("alpha", "2.0");
                        case "beta" -> toJar("beta", (String) null);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root/1.0",
                "foo/alpha/2.0",
                "foo/beta");
    }

    @Test
    public void propagates_compiled_version_from_parent_requires() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("pinned", 0, "1.0"));
                        case "pinned/1.0" -> toJar("pinned", "1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(fetched).containsOnlyKeys("root", "pinned/1.0");
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root/1.0",
                "foo/pinned/1.0");
    }

    @Test
    public void compiled_version_falls_back_to_bare_lookup_when_absent() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("plain", 0));
                        case "plain" -> toJar("plain", "2.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(fetched).containsOnlyKeys("root", "plain");
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root/1.0",
                "foo/plain/2.0");
    }

    @Test
    public void input_pin_overrides_compiled_version_propagation() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("dep", 0, "1.0"));
                        case "dep/9.9" -> toJar("dep", "9.9");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("dep", "9.9")),
                DependencyScope.COMPILE).artifacts();
        assertThat(fetched).containsOnlyKeys("root", "dep/9.9");
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root/1.0",
                "foo/dep/9.9");
    }

    @Test
    public void compiled_version_propagates_through_chain() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("middle", 0, "1.0"));
                        case "middle/1.0" -> toJar("middle", "1.0", require("deep", 0, "1.0"));
                        case "deep/1.0" -> toJar("deep", "1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(fetched).containsOnlyKeys("root", "middle/1.0", "deep/1.0");
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root/1.0",
                "foo/middle/1.0",
                "foo/deep/1.0");
    }

    @Test
    public void compiled_version_first_seen_wins_when_two_parents_disagree() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0",
                                require("middle", 0, "1.0"),
                                require("shared", 0, "1.0"));
                        case "middle/1.0" -> toJar("middle", "1.0", require("shared", 0, "2.0"));
                        case "shared/1.0" -> toJar("shared", "1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(fetched).contains(Map.entry("shared/1.0", ""));
        assertThat(dependencies).containsKey("foo/shared/1.0");
    }

    @Test
    public void input_pin_overrides_only_named_module_others_use_class_file() throws IOException {
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("transitive", 0));
                        case "transitive/9.9" -> toJar("transitive", "9.9");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("transitive", "9.9")),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root/1.0",
                "foo/transitive/9.9");
    }

    @Test
    public void rejects_propagated_compiled_version_with_path_traversal() {
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("dep", 0, "../../secret"));
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("../../secret");
    }

    @Test
    public void picks_highest_versioned_module_info_under_runtime() throws IOException {
        int runtime = Runtime.version().feature();
        LinkedHashMap<Integer, String> versions = new LinkedHashMap<>();
        versions.put(runtime + 100, "9.9");
        versions.put(runtime, "2.0");
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toMultiReleaseJar("root", "1.0", versions);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root/2.0");
    }

    @Test
    public void picks_root_when_only_future_versions_exist() throws IOException {
        int runtime = Runtime.version().feature();
        LinkedHashMap<Integer, String> versions = new LinkedHashMap<>();
        versions.put(runtime + 100, "9.9");
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toMultiReleaseJar("root", "1.0", versions);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root/1.0");
    }

    @Test
    public void classifier_pin_drives_classified_fetch() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root-windows-x86_64/9.9" -> toJar("root", "9.9");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", ":windows-x86_64:9.9")),
                DependencyScope.COMPILE).artifacts();
        assertThat(fetched).containsOnlyKeys("root-windows-x86_64/9.9");
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root-windows-x86_64/9.9");
    }

    @Test
    public void classifier_pin_without_version_uses_module_info_version() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root-windows-x86_64" -> toJar("root", "1.2.3");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", ":windows-x86_64")),
                DependencyScope.COMPILE).artifacts();
        assertThat(fetched).containsOnlyKeys("root-windows-x86_64");
        assertThat(dependencies.sequencedKeySet()).containsExactly("foo/root-windows-x86_64/1.2.3");
    }

    @Test
    public void classifier_pin_applies_to_transitive_module() throws IOException {
        Map<String, String> fetched = new LinkedHashMap<>();
        SequencedMap<String, Resolver.Resolved> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    fetched.put(coordinate, "");
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("dep", 0));
                        case "dep-win/2.0" -> toJar("dep", "2.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("dep", ":win:2.0")),
                DependencyScope.COMPILE).artifacts();
        assertThat(fetched).containsOnlyKeys("root", "dep-win/2.0");
        assertThat(dependencies.sequencedKeySet()).containsExactly(
                "foo/root/1.0",
                "foo/dep-win/2.0");
    }

    @Test
    public void classifier_pin_rejects_mismatched_module_info_version() {
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root-win/9.9" -> toJar("root", "1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", ":win:9.9")),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("9.9")
                .hasMessageContaining("1.0");
    }

    @Test
    public void rejects_classifier_pin_without_classifier() {
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, _) -> Optional.empty()),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", ":")),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed classifier");
    }

    @Test
    public void rejects_classifier_pin_with_empty_version() {
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, _) -> Optional.empty()),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of("root", ":win:")),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed classifier");
    }

    @Test
    public void rejects_module_info_version_with_classifier_syntax() {
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", ":win:1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe")
                .hasMessageContaining(":win:1.0");
    }

    @Test
    public void rejects_propagated_compiled_version_with_classifier_syntax() {
        assertThatThrownBy(() -> new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> toJar("root", "1.0", require("dep", 0, ":win:1.0"));
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashMap<>(Map.of("root", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe compiled version")
                .hasMessageContaining(":win:1.0");
    }

    private RepositoryItem toMultiReleaseJar(String module,
                                             String rootVersion,
                                             Map<Integer, String> versions) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path file = Files.createTempFile(jars, module, ".jar");
        try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            jarOutputStream.putNextEntry(new JarEntry("module-info.class"));
            jarOutputStream.write(buildModuleInfo(module, rootVersion));
            jarOutputStream.closeEntry();
            for (Map.Entry<Integer, String> entry : versions.entrySet()) {
                jarOutputStream.putNextEntry(new JarEntry(
                        "META-INF/versions/" + entry.getKey() + "/module-info.class"));
                jarOutputStream.write(buildModuleInfo(module, entry.getValue()));
                jarOutputStream.closeEntry();
            }
        }
        return RepositoryItem.ofFile(file);
    }

    private static byte[] buildModuleInfo(String module, String version) {
        return ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(module),
                builder -> {
                    if (version != null) {
                        builder.moduleVersion(version);
                    }
                    builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), 0, null));
                }));
    }

}
