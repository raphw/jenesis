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
                true);
        assertThat(dependencies).containsExactly(
                Map.entry("foo/root", ""),
                Map.entry("foo/propagated", ""));
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
                false);
        assertThat(dependencies).containsExactly(Map.entry("foo/root", ""));
    }

    private static ModuleRequireInfo require(String name, int flags) {
        return ModuleRequireInfo.of(ModuleDesc.of(name), flags, null);
    }

    private static InputStream toJar(String module, ModuleRequireInfo... requires) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {
            jarOutputStream.putNextEntry(new JarEntry("module-info.class"));
            jarOutputStream.write(ClassFile.of().buildModule(ModuleAttribute.of(
                    ModuleDesc.of(module),
                    builder -> {
                        builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), 0, null));
                        for (ModuleRequireInfo require : requires) {
                            builder.requires(require);
                        }
                    })));
            jarOutputStream.closeEntry();
        }
        return new ByteArrayInputStream(outputStream.toByteArray());
    }
}
