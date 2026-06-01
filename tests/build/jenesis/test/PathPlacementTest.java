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
        Files.createFile(directory.resolve("module-info.class"));
        assertThat(PathPlacement.INFERRED.test(directory)).isTrue();
    }

    @Test
    public void inferred_detects_explicit_module_jar() throws IOException {
        Path jar = root.resolve("explicit.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("module-info.class"));
            output.write(ClassFile.of().buildModule(ModuleAttribute.of(
                    ModuleDesc.of("sample.explicit"),
                    builder -> builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), 0, null)))));
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
}
