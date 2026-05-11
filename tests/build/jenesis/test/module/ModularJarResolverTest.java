package build.jenesis.test.module;

import build.jenesis.RepositoryItem;
import build.jenesis.module.ModularJarResolver;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class ModularJarResolverTest {

    @Test
    public void can_parse_module_info() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("sample", require("transitive", 0));
                        case "transitive" -> () -> toJar("transitive", require("last", 0));
                        case "last" -> () -> toJar("last");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                true);
        assertThat(dependencies).containsExactly(
                Map.entry("foo/root", ""),
                Map.entry("foo/transitive", ""),
                Map.entry("foo/last", ""));
    }

    @Test
    public void skips_non_transitive_static_requires_in_compile_scope() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("sample", require("optional", ClassFile.ACC_STATIC_PHASE));
                        case "optional" -> () -> toJar("optional");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                true);
        assertThat(dependencies).containsExactly(Map.entry("foo/root", ""));
    }

    @Test
    public void includes_static_transitive_requires_in_compile_scope() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("sample", require("propagated",
                                ClassFile.ACC_STATIC_PHASE | ClassFile.ACC_TRANSITIVE));
                        case "propagated" -> () -> toJar("propagated");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                true);
        assertThat(dependencies).containsExactly(
                Map.entry("foo/root", ""),
                Map.entry("foo/propagated", ""));
    }

    @Test
    public void emits_transitive_requires_in_sorted_order_independent_of_declaration_order() throws IOException {
        // ModuleDescriptor.requires() returns a Set.of-style set whose iteration order is
        // randomised per JVM run; the resolver must still emit dependencies in a stable order.
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("root",
                                require("zeta", 0),
                                require("alpha", 0),
                                require("middle", 0));
                        case "alpha", "middle", "zeta" -> () -> toJar(coordinate);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                true);
        assertThat(dependencies).containsExactly(
                Map.entry("foo/root", ""),
                Map.entry("foo/alpha", ""),
                Map.entry("foo/middle", ""),
                Map.entry("foo/zeta", ""));
    }

    @Test
    public void skips_static_transitive_requires_in_runtime_scope() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("sample", require("propagated",
                                ClassFile.ACC_STATIC_PHASE | ClassFile.ACC_TRANSITIVE));
                        case "propagated" -> () -> toJar("propagated");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                false);
        assertThat(dependencies).containsExactly(Map.entry("foo/root", ""));
    }

    private static ModuleRequireInfo require(String name, int flags) {
        return ModuleRequireInfo.of(ModuleDesc.of(name), flags, null);
    }

    private static InputStream toJar(String module, ModuleRequireInfo... requires) throws IOException {
        return toJar(module, null, requires);
    }

    private static InputStream toJar(String module, String version, ModuleRequireInfo... requires) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {
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
        return new ByteArrayInputStream(outputStream.toByteArray());
    }

    @Test
    public void uses_version_from_module_info_class() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("root", "1.2.3");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                true);
        assertThat(dependencies).containsExactly(Map.entry("foo/root/1.2.3", ""));
    }

    @Test
    public void unversioned_module_info_yields_unversioned_coordinate() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("root", (String) null);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                true);
        assertThat(dependencies).containsExactly(Map.entry("foo/root", ""));
    }

    @Test
    public void input_pin_overrides_module_info_version() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("root", "1.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(Map.of("root", "9.9")),
                true);
        assertThat(dependencies).containsExactly(Map.entry("foo/root/9.9", ""));
    }

    @Test
    public void input_pin_supplies_version_for_unversioned_module() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("root", (String) null);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(Map.of("root", "7.0")),
                true);
        assertThat(dependencies).containsExactly(Map.entry("foo/root/7.0", ""));
    }

    @Test
    public void transitive_carries_its_own_module_info_version() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("root", "1.0", require("transitive", 0));
                        case "transitive" -> () -> toJar("transitive", "2.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                true);
        assertThat(dependencies).containsExactly(
                Map.entry("foo/root/1.0", ""),
                Map.entry("foo/transitive/2.0", ""));
    }

    @Test
    public void mixed_versioned_and_unversioned_transitives() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("root", "1.0",
                                require("alpha", 0),
                                require("beta", 0));
                        case "alpha" -> () -> toJar("alpha", "2.0");
                        case "beta" -> () -> toJar("beta", (String) null);
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(),
                true);
        assertThat(dependencies).containsExactly(
                Map.entry("foo/root/1.0", ""),
                Map.entry("foo/alpha/2.0", ""),
                Map.entry("foo/beta", ""));
    }

    @Test
    public void input_pin_overrides_only_named_module_others_use_class_file() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                "foo",
                Map.of("foo", (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("root", "1.0", require("transitive", 0));
                        case "transitive" -> () -> toJar("transitive", "2.0");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")),
                new LinkedHashMap<>(Map.of("transitive", "9.9")),
                true);
        assertThat(dependencies).containsExactly(
                Map.entry("foo/root/1.0", ""),
                Map.entry("foo/transitive/9.9", ""));
    }
}
