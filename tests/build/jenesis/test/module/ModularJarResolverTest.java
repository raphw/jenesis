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
                        case "root" -> () -> toJar("sample", "transitive");
                        case "transitive" -> () -> toJar("transitive", "last");
                        case "last" -> () -> toJar("last");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                }),
                new LinkedHashSet<>(Set.of("root")));
        assertThat(dependencies).containsExactly(
                Map.entry("foo/root", ""),
                Map.entry("foo/transitive", ""),
                Map.entry("foo/last", ""));
    }

    private static InputStream toJar(String module, String... requires) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (JarOutputStream jarOutputStream = new JarOutputStream(outputStream)) {
            jarOutputStream.putNextEntry(new JarEntry("module-info.class"));
            jarOutputStream.write(ClassFile.of().buildModule(ModuleAttribute.of(
                    ModuleDesc.of(module),
                    builder -> {
                        builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), 0, null));
                        for (String require : requires) {
                            builder.requires(ModuleRequireInfo.of(ModuleDesc.of(require), 0, null));
                        }
                    })));
            jarOutputStream.closeEntry();
        }
        return new ByteArrayInputStream(outputStream.toByteArray());
    }
}
