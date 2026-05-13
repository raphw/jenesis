package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.Project;
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.maven.MavenModuleDescriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ProjectTest {

    @TempDir
    private Path root;

    @AfterEach
    public void clearProperties() {
        System.clearProperty("jenesis.project.layout");
        System.clearProperty("jenesis.project.hashAlgorithm");
        System.clearProperty("jenesis.project.skipTests");
        System.clearProperty("jenesis.project.root");
        System.clearProperty("jenesis.project.target");
        System.clearProperty("jenesis.project.cache");
    }

    @Test
    public void auto_detects_maven_from_pom_xml() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        assertThat(Project.Layout.of(root)).isSameAs(Project.Layout.MAVEN);
    }

    @Test
    public void auto_detects_modular_from_module_info() throws IOException {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module example {}");
        assertThat(Project.Layout.of(root)).isSameAs(Project.Layout.MODULAR);
    }

    @Test
    public void auto_prefers_maven_when_both_are_present() throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module example {}");
        assertThat(Project.Layout.of(root)).isSameAs(Project.Layout.MAVEN);
    }

    @Test
    public void auto_throws_when_neither_descriptor_is_present() {
        assertThatThrownBy(() -> Project.Layout.of(root))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No build descriptor found");
    }

    @Test
    public void auto_skips_module_info_under_a_nested_build_marker() throws IOException {
        Path nested = Files.createDirectory(root.resolve("nested"));
        Files.createFile(nested.resolve(BuildExecutor.BUILD_MARKER));
        Files.writeString(nested.resolve("module-info.java"), "module hidden {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        assertThat(Project.Layout.of(root)).isSameAs(Project.Layout.MAVEN);
    }

    @Test
    public void build_throws_when_no_descriptor_is_detected() {
        assertThatThrownBy(() -> Project.builder().root(root).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No build descriptor found");
    }

    @Test
    public void layout_setter_round_trips_each_concrete_layout() {
        for (Project.Layout layout : List.of(Project.Layout.MAVEN, Project.Layout.MODULAR, Project.Layout.MODULAR_TO_MAVEN)) {
            assertThat(Project.builder().layout(layout).resolveProperties().layout()).isSameAs(layout);
        }
    }

    @Test
    public void system_property_picks_each_concrete_layout() {
        Map<String, Project.Layout> cases = Map.of(
                "maven", Project.Layout.MAVEN,
                "modular", Project.Layout.MODULAR,
                "modular_to_maven", Project.Layout.MODULAR_TO_MAVEN);
        cases.forEach((name, layout) -> {
            System.setProperty("jenesis.project.layout", name);
            assertThat(Project.builder().resolveProperties().layout())
                    .as("layout=%s", name)
                    .isSameAs(layout);
        });
    }

    @Test
    public void system_property_overrides_an_explicit_layout() {
        System.setProperty("jenesis.project.layout", "maven");
        assertThat(Project.builder().layout(Project.Layout.MODULAR).resolveProperties().layout())
                .isSameAs(Project.Layout.MAVEN);
    }

    @Test
    public void system_property_rejects_unknown_layout() {
        System.setProperty("jenesis.project.layout", "nonsense");
        assertThatThrownBy(() -> Project.builder().resolveProperties())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown layout");
    }

    @Test
    public void system_property_overrides_hash_algorithm() {
        System.setProperty("jenesis.project.hashAlgorithm", "SHA512");
        assertThat(Project.builder().resolveProperties().hashAlgorithm()).isEqualTo("SHA512");
    }

    @Test
    public void system_property_disables_tests() {
        System.setProperty("jenesis.project.skipTests", "");
        assertThat(Project.builder().resolveProperties().tests()).isFalse();
    }

    @Test
    public void skip_tests_setter_skips_tests() {
        assertThat(Project.builder().tests(false).tests()).isFalse();
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
    public void default_assembler_is_set() {
        assertThat(Project.builder().assembler()).isNotNull();
    }

    @Test
    public void assembler_can_be_overridden() {
        Project.Assembler custom = (_, _) -> (_, _) -> {};
        assertThat(Project.builder().assembler(custom).assembler()).isSameAs(custom);
    }

    @Test
    public void default_assembler_returns_a_module() {
        ModuleDescriptor descriptor = new MavenModuleDescriptor("module-sources", new LinkedHashSet<>());
        Project.Context context = new Project.Context(true, false, false, null, "SHA256", Map.of(), Map.of());
        assertThat(Project.Assembler.ofJava().apply(context, descriptor)).isNotNull();
    }

    @Test
    public void default_layout_is_auto() {
        assertThat(Project.builder().layout()).isSameAs(Project.Layout.AUTO);
    }

    @Test
    public void maven_layout_resolver_maps_named_and_unnamed_modules() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project.Builder builder = Project.builder().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MAVEN.apply(
                BuildExecutor.of(target), builder, Project.Assembler.ofJava());
        assertThat(resolver.apply("sources")).isEqualTo("build/maven/compose/module/module-sources");
        assertThat(resolver.apply("")).isEqualTo("build/maven/compose/module/module-");
    }

    @Test
    public void modular_layout_resolver_maps_named_and_unnamed_modules() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project.Builder builder = Project.builder().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MODULAR.apply(
                BuildExecutor.of(target), builder, Project.Assembler.ofJava());
        assertThat(resolver.apply("sources")).isEqualTo("build/modules/compose/module/module-sources");
        assertThat(resolver.apply("")).isEqualTo("build/modules/compose/module/module-");
    }

    @Test
    public void modular_to_maven_layout_resolver_maps_named_and_unnamed_modules() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project.Builder builder = Project.builder().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MODULAR_TO_MAVEN.apply(
                BuildExecutor.of(target), builder, Project.Assembler.ofJava());
        assertThat(resolver.apply("sources")).isEqualTo("build/modules/compose/module/module-sources");
        assertThat(resolver.apply("")).isEqualTo("build/modules/compose/module/module-");
    }
}
