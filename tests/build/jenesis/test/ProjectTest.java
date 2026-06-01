package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Project;
import build.jenesis.module.JenesisModuleRepositoryExport;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ProjectTest {

    @TempDir
    private Path root;

    @AfterEach
    public void clearProperties() {
        System.clearProperty("jenesis.project.layout");
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
        assertThat(Project.Layout.of(root)).isSameAs(Project.Layout.MODULAR_TO_MAVEN);
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
        Files.createFile(nested.resolve(BuildExecutor.SKIP_MARKER));
        Files.writeString(nested.resolve("module-info.java"), "module hidden {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        assertThat(Project.Layout.of(root)).isSameAs(Project.Layout.MAVEN);
    }

    @Test
    public void build_throws_when_no_descriptor_is_detected() {
        assertThatThrownBy(() -> new Project().root(root).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No build descriptor found");
    }

    @Test
    public void layout_setter_round_trips_each_concrete_layout() {
        for (Project.Layout layout : List.of(Project.Layout.MAVEN, Project.Layout.MODULAR, Project.Layout.MODULAR_TO_MAVEN)) {
            assertThat(new Project().layout(layout).resolveProperties().layout()).isSameAs(layout);
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
            assertThat(new Project().resolveProperties().layout())
                    .as("layout=%s", name)
                    .isSameAs(layout);
        });
    }

    @Test
    public void system_property_overrides_an_explicit_layout() {
        System.setProperty("jenesis.project.layout", "maven");
        assertThat(new Project().layout(Project.Layout.MODULAR).resolveProperties().layout())
                .isSameAs(Project.Layout.MAVEN);
    }

    @Test
    public void system_property_rejects_unknown_layout() {
        System.setProperty("jenesis.project.layout", "nonsense");
        assertThatThrownBy(() -> new Project().resolveProperties())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown layout");
    }

    @Test
    public void system_property_disables_tests() {
        System.setProperty("jenesis.project.skipTests", "");
        assertThat(new Project().resolveProperties().tests()).isFalse();
    }

    @Test
    public void skip_tests_setter_skips_tests() {
        assertThat(new Project().tests(false).tests()).isFalse();
    }

    @Test
    public void defaults_keep_tests_enabled() {
        Project project = new Project();
        assertThat(project.tests()).isTrue();
    }

    @Test
    public void default_target_is_build() {
        assertThat(new Project().defaultTarget()).containsExactly("build");
    }

    @Test
    public void default_target_can_be_overridden() {
        assertThat(new Project().defaultTarget("foo", "bar").defaultTarget())
                .containsExactly("foo", "bar");
    }

    @Test
    public void system_property_overrides_root() {
        System.setProperty("jenesis.project.root", root.toString());
        assertThat(new Project().resolveProperties().root()).isEqualTo(Path.of(root.toString()));
    }

    @Test
    public void system_property_overrides_target() {
        System.setProperty("jenesis.project.target", "custom-target");
        assertThat(new Project().resolveProperties().target()).isEqualTo(Path.of("custom-target"));
    }

    @Test
    public void system_property_overrides_cache() {
        System.setProperty("jenesis.project.cache", "custom-cache");
        assertThat(new Project().resolveProperties().cache()).isEqualTo(Path.of("custom-cache"));
    }

    @Test
    public void default_assembler_is_set() {
        assertThat(new Project().assembler()).isNotNull();
    }

    @Test
    public void assembler_can_be_overridden() {
        MultiProjectAssembler<ProjectModuleDescriptor> custom = (_, _, _) -> (_, _) -> {};
        assertThat(new Project().assembler(custom).assembler()).isSameAs(custom);
    }

    @Test
    public void default_layout_is_auto() {
        assertThat(new Project().layout()).isSameAs(Project.Layout.AUTO);
    }

    @Test
    public void maven_layout_resolver_maps_named_and_unnamed_modules() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project project = new Project().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MAVEN.apply(
                BuildExecutor.of(target,
                        Duration.ZERO,
                        new HashDigestFunction("MD5"),
                        BuildStepHashFunction.ofSerializationDigest("MD5"),
                        BuildExecutorCallback.nop(), false),
                project,
                new JavaMultiProjectAssembler());
        assertThat(resolver.apply("sources")).isEqualTo("build/maven/compose/module/module-sources");
        assertThat(resolver.apply("")).isEqualTo("build/maven/compose/module/module-");
    }

    @Test
    public void modular_layout_resolver_maps_named_and_unnamed_modules() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project project = new Project().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MODULAR.apply(
                BuildExecutor.of(target,
                        Duration.ZERO,
                        new HashDigestFunction("MD5"),
                        BuildStepHashFunction.ofSerializationDigest("MD5"),
                        BuildExecutorCallback.nop(), false),
                project,
                new JavaMultiProjectAssembler());
        assertThat(resolver.apply("sources")).isEqualTo("build/modules/compose/module/module-sources");
        assertThat(resolver.apply("")).isEqualTo("build/modules/compose/module/module-");
    }

    @Test
    public void maven_layout_resolver_preserves_step_path_after_slash() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project project = new Project().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MAVEN.apply(
                BuildExecutor.of(target,
                        Duration.ZERO,
                        new HashDigestFunction("MD5"),
                        BuildStepHashFunction.ofSerializationDigest("MD5"),
                        BuildExecutorCallback.nop(), false),
                project,
                new JavaMultiProjectAssembler());
        assertThat(resolver.apply("sources/compile/dependencies/resolved"))
                .isEqualTo("build/maven/compose/module/module-sources/compile/dependencies/resolved");
        assertThat(resolver.apply("/compile"))
                .isEqualTo("build/maven/compose/module/module-/compile");
    }

    @Test
    public void modular_layout_resolver_preserves_step_path_after_slash() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project project = new Project().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MODULAR.apply(
                BuildExecutor.of(target,
                        Duration.ZERO,
                        new HashDigestFunction("MD5"),
                        BuildStepHashFunction.ofSerializationDigest("MD5"),
                        BuildExecutorCallback.nop(), false),
                project,
                new JavaMultiProjectAssembler());
        assertThat(resolver.apply("sources/compile/dependencies/resolved"))
                .isEqualTo("build/modules/compose/module/module-sources/compile/dependencies/resolved");
        assertThat(resolver.apply("/compile"))
                .isEqualTo("build/modules/compose/module/module-/compile");
    }

    @Test
    public void modular_to_maven_layout_resolver_preserves_step_path_after_slash() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project project = new Project().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MODULAR_TO_MAVEN.apply(
                BuildExecutor.of(target,
                        Duration.ZERO,
                        new HashDigestFunction("MD5"),
                        BuildStepHashFunction.ofSerializationDigest("MD5"),
                        BuildExecutorCallback.nop(), false),
                project,
                new JavaMultiProjectAssembler());
        assertThat(resolver.apply("sources/compile/dependencies/resolved"))
                .isEqualTo("build/modules/compose/module/module-sources/compile/dependencies/resolved");
        assertThat(resolver.apply("/compile"))
                .isEqualTo("build/modules/compose/module/module-/compile");
    }

    @Test
    public void modular_layout_registers_export_step() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project project = new Project().root(root).target(target);
        BuildExecutor executor = BuildExecutor.of(target,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);

        Project.Layout.MODULAR.apply(executor, project, new JavaMultiProjectAssembler());

        // replaceStep throws when nothing is registered at the identity, so a no-throw call proves
        // the layout wired the `export` registration.
        executor.replaceStep(Project.EXPORT, new JenesisModuleRepositoryExport(target.resolve("module-repository")));
    }

    @Test
    public void modular_to_maven_layout_resolver_maps_named_and_unnamed_modules() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Project project = new Project().root(root).target(target);
        Function<String, String> resolver = Project.Layout.MODULAR_TO_MAVEN.apply(
                BuildExecutor.of(target,
                        Duration.ZERO,
                        new HashDigestFunction("MD5"),
                        BuildStepHashFunction.ofSerializationDigest("MD5"),
                        BuildExecutorCallback.nop(), false),
                project,
                new JavaMultiProjectAssembler());
        assertThat(resolver.apply("sources")).isEqualTo("build/modules/compose/module/module-sources");
        assertThat(resolver.apply("")).isEqualTo("build/modules/compose/module/module-");
    }

    @Test
    public void build_returns_paths_for_the_default_target() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path source = Files.createDirectory(root.resolve("source"));
        Project.Layout layout = (executor, _, _) -> {
            executor.addSource(Project.BUILD, source);
            return name -> name;
        };
        SequencedMap<String, Path> result = new Project()
                .root(root)
                .target(target)
                .layout(layout)
                .build();
        assertThat(result).containsExactly(Map.entry(Project.BUILD, source));
    }

    @Test
    public void build_returns_paths_for_explicit_selectors() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path alpha = Files.createDirectory(root.resolve("alpha"));
        Path beta = Files.createDirectory(root.resolve("beta"));
        Project.Layout layout = (executor, _, _) -> {
            executor.addSource("alpha", alpha);
            executor.addSource("beta", beta);
            return name -> name;
        };
        SequencedMap<String, Path> result = new Project()
                .root(root)
                .target(target)
                .layout(layout)
                .build("beta");
        assertThat(result).containsExactly(Map.entry("beta", beta));
    }

    @Test
    public void build_resolves_plus_prefixed_selectors_via_the_layout() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path source = Files.createDirectory(root.resolve("source"));
        Project.Layout layout = (executor, _, _) -> {
            executor.addSource("resolved", source);
            return name -> "resolved";
        };
        SequencedMap<String, Path> result = new Project()
                .root(root)
                .target(target)
                .layout(layout)
                .build("+anything");
        assertThat(result).containsExactly(Map.entry("resolved", source));
    }
}
