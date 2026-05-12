package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.Project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ProjectTest {

    @TempDir
    private Path root;

    @AfterEach
    public void clearProperties() {
        System.clearProperty("jenesis.project.kind");
        System.clearProperty("jenesis.project.hashAlgorithm");
        System.clearProperty("jenesis.project.skipTests");
        System.clearProperty("jenesis.project.root");
        System.clearProperty("jenesis.project.target");
        System.clearProperty("jenesis.project.cache");
    }

    @Test
    public void auto_detects_maven_from_pom_xml() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        assertThat(Project.Kind.of(root)).isEqualTo(Project.Kind.MAVEN);
    }

    @Test
    public void auto_detects_modular_from_module_info() throws IOException {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module example {}");
        assertThat(Project.Kind.of(root)).isEqualTo(Project.Kind.MODULAR);
    }

    @Test
    public void auto_prefers_modular_when_both_are_present() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module example {}");
        assertThat(Project.Kind.of(root)).isEqualTo(Project.Kind.MODULAR);
    }

    @Test
    public void auto_returns_auto_when_neither_descriptor_is_present() throws IOException {
        assertThat(Project.Kind.of(root)).isEqualTo(Project.Kind.AUTO);
    }

    @Test
    public void auto_skips_module_info_under_a_nested_build_marker() throws IOException {
        Path nested = Files.createDirectory(root.resolve("nested"));
        Files.createFile(nested.resolve(BuildExecutor.BUILD_MARKER));
        Files.writeString(nested.resolve("module-info.java"), "module hidden {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        assertThat(Project.Kind.of(root)).isEqualTo(Project.Kind.MAVEN);
    }

    @Test
    public void build_throws_when_no_descriptor_is_detected() {
        assertThatThrownBy(() -> Project.builder().root(root).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No build descriptor found");
    }

    @Test
    public void force_overrides_detection_for_each_kind() {
        for (Project.Kind kind : Project.Kind.values()) {
            if (kind == Project.Kind.AUTO) {
                continue;
            }
            assertThat(Project.builder().force(kind).resolveProperties().kind()).isEqualTo(kind);
        }
    }

    @Test
    public void system_property_picks_each_non_auto_kind() {
        for (Project.Kind kind : Project.Kind.values()) {
            if (kind == Project.Kind.AUTO) {
                continue;
            }
            System.setProperty("jenesis.project.kind", kind.name().toLowerCase(Locale.ROOT));
            assertThat(Project.builder().resolveProperties().kind())
                    .as("kind=%s", kind)
                    .isEqualTo(kind);
        }
    }

    @Test
    public void system_property_overrides_an_explicit_force() {
        System.setProperty("jenesis.project.kind", "maven");
        assertThat(Project.builder().force(Project.Kind.MODULAR).resolveProperties().kind())
                .isEqualTo(Project.Kind.MAVEN);
    }

    @Test
    public void system_property_overrides_hash_algorithm() {
        System.setProperty("jenesis.project.hashAlgorithm", "SHA512");
        assertThat(Project.builder().force(Project.Kind.MAVEN).resolveProperties().hashAlgorithm())
                .isEqualTo("SHA512");
    }

    @Test
    public void system_property_disables_tests() {
        System.setProperty("jenesis.project.skipTests", "");
        assertThat(Project.builder().force(Project.Kind.MAVEN).resolveProperties().tests()).isFalse();
    }

    @Test
    public void skip_tests_setter_skips_tests() {
        assertThat(Project.builder().force(Project.Kind.MAVEN).tests(false).tests()).isFalse();
    }

    @Test
    public void defaults_keep_tests_enabled_and_use_sha256() {
        Project.Builder builder = Project.builder();
        assertThat(builder.tests()).isTrue();
        assertThat(builder.hashAlgorithm()).isEqualTo("SHA256");
    }

    @Test
    public void default_target_is_build() {
        assertThat(Project.builder().defaultTarget()).containsExactly("build");
    }

    @Test
    public void default_target_can_be_overridden() {
        assertThat(Project.builder().defaultTarget("foo", "bar").defaultTarget())
                .containsExactly("foo", "bar");
    }

    @Test
    public void system_property_overrides_root() {
        System.setProperty("jenesis.project.root", root.toString());
        assertThat(Project.builder().resolveProperties().root()).isEqualTo(Path.of(root.toString()));
    }

    @Test
    public void system_property_overrides_target() {
        System.setProperty("jenesis.project.target", "custom-target");
        assertThat(Project.builder().resolveProperties().target()).isEqualTo(Path.of("custom-target"));
    }

    @Test
    public void system_property_overrides_cache() {
        System.setProperty("jenesis.project.cache", "custom-cache");
        assertThat(Project.builder().resolveProperties().cache()).isEqualTo(Path.of("custom-cache"));
    }

    @Test
    public void default_factory_is_set() {
        assertThat(Project.builder().factory()).isNotNull();
    }

    @Test
    public void factory_can_be_overridden() {
        Project.Factory custom = (_, _) -> (_, _) -> {};
        assertThat(Project.builder().factory(custom).factory()).isSameAs(custom);
    }

    @Test
    public void default_factory_dispatches_per_kind() {
        Project.Factory factory = Project.Factory.defaults();
        build.jenesis.project.ModuleDescriptor descriptor = new build.jenesis.maven.MavenModuleDescriptor(
                "module-sources", new LinkedHashSet<>());
        for (Project.Kind kind : Project.Kind.values()) {
            if (kind == Project.Kind.AUTO) {
                continue;
            }
            Project.Context context = new Project.Context(kind, true, "SHA256", Map.of(), Map.of());
            assertThat(factory.apply(context, descriptor))
                    .as("kind=%s", kind)
                    .isNotNull();
        }
    }

    @Test
    public void default_factory_rejects_auto() {
        Project.Factory factory = Project.Factory.defaults();
        build.jenesis.project.ModuleDescriptor descriptor = new build.jenesis.maven.MavenModuleDescriptor(
                "module-sources", new LinkedHashSet<>());
        Project.Context context = new Project.Context(
                Project.Kind.AUTO, true, "SHA256", Map.of(), Map.of());
        assertThatThrownBy(() -> factory.apply(context, descriptor))
                .isInstanceOf(AssertionError.class);
    }
}
