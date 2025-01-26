package build.buildbuddy.test.module;

import build.buildbuddy.RepositoryItem;
import build.buildbuddy.module.ModularJarResolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.classfile.attribute.ModuleRequireInfo;
import java.lang.constant.ModuleDesc;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class ModularJarResolverTest {

    @Test
    public void can_parse_module_info() throws IOException {
        SequencedMap<String, String> dependencies = new ModularJarResolver(false).dependencies(
                Runnable::run,
                (_, coordinate) -> {
                    RepositoryItem item = switch (coordinate) {
                        case "root" -> () -> toJar("sample", "transitive");
                        case "transitive" -> () -> toJar("transitive", "last");
                        case "last" -> () -> toJar("last");
                        default -> null;
                    };
                    return Optional.ofNullable(item);
                },
                new LinkedHashSet<>(Set.of("root")));
        assertThat(dependencies).containsExactly(
                Map.entry("root", ""),
                Map.entry("transitive", ""),
                Map.entry("last", ""));
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
