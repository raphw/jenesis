package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.PathPlacement;

import static org.assertj.core.api.Assertions.assertThat;

public class PathPlacementTest {

    @TempDir
    private Path root;

    @Test
    public void exposes_modular_flag() {
        assertThat(PathPlacement.CLASS_PATH.modular()).isFalse();
        assertThat(PathPlacement.MODULE_PATH.modular()).isTrue();
        assertThat(PathPlacement.INFERRED.modular()).isTrue();
    }

    @Test
    public void class_path_never_resolves_to_module_path() throws IOException {
        assertThat(PathPlacement.CLASS_PATH.test(root)).isFalse();
    }

    @Test
    public void module_path_always_resolves_to_module_path() throws IOException {
        assertThat(PathPlacement.MODULE_PATH.test(root)).isTrue();
    }

    @Test
    public void for_module_info_downgrades_to_class_path_without_module_info() {
        assertThat(PathPlacement.CLASS_PATH.forModuleInfo(false)).isEqualTo(PathPlacement.CLASS_PATH);
        assertThat(PathPlacement.MODULE_PATH.forModuleInfo(false)).isEqualTo(PathPlacement.CLASS_PATH);
        assertThat(PathPlacement.INFERRED.forModuleInfo(false)).isEqualTo(PathPlacement.CLASS_PATH);
    }

    @Test
    public void for_module_info_retains_placement_with_module_info() {
        assertThat(PathPlacement.CLASS_PATH.forModuleInfo(true)).isEqualTo(PathPlacement.INFERRED);
        assertThat(PathPlacement.MODULE_PATH.forModuleInfo(true)).isEqualTo(PathPlacement.MODULE_PATH);
        assertThat(PathPlacement.INFERRED.forModuleInfo(true)).isEqualTo(PathPlacement.INFERRED);
    }

    @Test
    public void inferred_detects_module_directory() throws IOException {
        Path directory = Files.createDirectory(root.resolve("classes"));
        assertThat(PathPlacement.INFERRED.test(directory)).isFalse();
        Files.write(directory.resolve("module-info.class"), moduleInfo("sample.directory"));
        assertThat(PathPlacement.INFERRED.test(directory)).isTrue();
    }

    @Test
    public void inferred_detects_explicit_module_jar() throws IOException {
        Path jar = root.resolve("explicit.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("module-info.class"));
            output.write(moduleInfo("sample.explicit"));
            output.closeEntry();
        }
        assertThat(PathPlacement.INFERRED.test(jar)).isTrue();
    }

    @Test
    public void inferred_detects_automatic_module_name_jar() throws IOException {
        Path jar = root.resolve("named.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "sample.automatic");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("sample/resource.txt"));
            output.closeEntry();
        }
        assertThat(PathPlacement.INFERRED.test(jar)).isTrue();
    }

    @Test
    public void inferred_rejects_plain_jar() throws IOException {
        Path jar = root.resolve("plain.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("sample/resource.txt"));
            output.closeEntry();
        }
        assertThat(PathPlacement.INFERRED.test(jar)).isFalse();
    }

    @Test
    public void inferred_rejects_absent_path() throws IOException {
        assertThat(PathPlacement.INFERRED.test(root.resolve("absent.jar"))).isFalse();
    }

    @Test
    public void module_descriptor_reads_an_explicit_module() throws IOException {
        Path jar = root.resolve("explicit.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("module-info.class"));
            output.write(moduleInfo("sample.explicit"));
            output.closeEntry();
        }
        ModuleDescriptor descriptor = PathPlacement.moduleDescriptor(jar);
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.name()).isEqualTo("sample.explicit");
        assertThat(descriptor.isAutomatic()).isFalse();
    }

    @Test
    public void module_descriptor_reads_a_declared_automatic_module() throws IOException {
        Path jar = root.resolve("named.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", "sample.automatic");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("sample/resource.txt"));
            output.closeEntry();
        }
        ModuleDescriptor descriptor = PathPlacement.moduleDescriptor(jar);
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.name()).isEqualTo("sample.automatic");
        assertThat(descriptor.isAutomatic()).isTrue();
    }

    @Test
    public void module_descriptor_ignores_a_filename_derived_name() throws IOException {
        Path jar = root.resolve("commons-lang3-3.12.0.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("sample/resource.txt"));
            output.closeEntry();
        }
        assertThat(PathPlacement.moduleDescriptor(jar)).isNull();
    }

    @Test
    public void module_descriptor_ignores_a_manifest_without_an_automatic_name() throws IOException {
        Path jar = root.resolve("manifest.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("sample/resource.txt"));
            output.closeEntry();
        }
        assertThat(PathPlacement.moduleDescriptor(jar)).isNull();
    }

    @Test
    public void module_descriptor_uses_the_latest_supported_version_in_a_multi_release_jar() throws IOException {
        Path jar = multiReleaseJar("multi.jar", Map.of(
                "module-info.class", moduleInfo("sample.base"),
                "META-INF/versions/9/module-info.class", moduleInfo("sample.nine")));
        assertThat(PathPlacement.moduleDescriptor(jar).name()).isEqualTo("sample.nine");
    }

    @Test
    public void module_descriptor_ignores_a_version_above_the_runtime() throws IOException {
        Path jar = multiReleaseJar("future.jar", Map.of(
                "module-info.class", moduleInfo("sample.base"),
                "META-INF/versions/9999/module-info.class", moduleInfo("sample.future")));
        assertThat(PathPlacement.moduleDescriptor(jar).name()).isEqualTo("sample.base");
    }

    @Test
    public void module_descriptor_ignores_versioned_entries_without_the_multi_release_flag() throws IOException {
        Path jar = root.resolve("not-multi.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("module-info.class"));
            output.write(moduleInfo("sample.base"));
            output.closeEntry();
            output.putNextEntry(new JarEntry("META-INF/versions/9/module-info.class"));
            output.write(moduleInfo("sample.nine"));
            output.closeEntry();
        }
        assertThat(PathPlacement.moduleDescriptor(jar).name()).isEqualTo("sample.base");
    }

    @Test
    public void module_descriptor_reads_a_module_directory() throws IOException {
        Path directory = Files.createDirectory(root.resolve("classes"));
        Files.write(directory.resolve("module-info.class"), moduleInfo("sample.directory"));
        ModuleDescriptor descriptor = PathPlacement.moduleDescriptor(directory);
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.name()).isEqualTo("sample.directory");
    }

    @Test
    public void module_descriptor_rejects_a_directory_without_module_info() throws IOException {
        Path directory = Files.createDirectory(root.resolve("plain"));
        assertThat(PathPlacement.moduleDescriptor(directory)).isNull();
    }

    @Test
    public void module_descriptor_rejects_an_absent_path() {
        assertThat(PathPlacement.moduleDescriptor(root.resolve("absent.jar"))).isNull();
    }

    private static byte[] moduleInfo(String name) {
        return ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of(name),
                builder -> builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), 0, null))));
    }

    private Path multiReleaseJar(String file, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path jar = root.resolve(file);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }
}
