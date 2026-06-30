package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildExecutorFileCache;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Project;
import build.jenesis.module.JenesisModuleRepositoryExport;
import build.jenesis.project.AssemblyDescriptor;
import build.jenesis.project.InferredMultiProjectAssembler;
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
        System.clearProperty("jenesis.test.skip");
        System.clearProperty("jenesis.project.root");
        System.clearProperty("jenesis.project.configuration");
        System.clearProperty("jenesis.project.target");
        System.clearProperty("jenesis.project.artifacts");
        System.clearProperty("jenesis.project.cache");
        System.clearProperty("jenesis.project.digest");
        System.clearProperty("jenesis.project.properties");
        System.clearProperty("jenesis.test.sample.key");
        System.clearProperty("jenesis.test.sample.a");
        System.clearProperty("jenesis.test.sample.b");
        System.clearProperty("jenesis.test.sample.c");
        System.clearProperty("jenesis.test.sample.d");
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
            assertThat(new Project().layout(layout).layout()).isSameAs(layout);
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
            assertThat(new Project().layout())
                    .as("layout=%s", name)
                    .isSameAs(layout);
        });
    }

    @Test
    public void explicit_layout_overrides_system_property() {
        System.setProperty("jenesis.project.layout", "maven");
        assertThat(new Project().layout(Project.Layout.MODULAR).layout())
                .isSameAs(Project.Layout.MODULAR);
    }

    @Test
    public void system_property_rejects_unknown_layout() {
        System.setProperty("jenesis.project.layout", "nonsense");
        assertThatThrownBy(() -> new Project())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown layout");
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
    public void configuration_defaults_to_the_root() {
        assertThat(new Project().configuration()).containsExactly(Path.of("."));
    }

    @Test
    public void empty_configuration_property_skips_the_global_configuration() {
        System.setProperty("jenesis.project.configuration", "");
        assertThat(new Project().configuration()).isEmpty();
    }

    @Test
    public void configuration_folders_are_absolute_deduplicated_and_must_exist() throws IOException {
        Path existing = Files.createDirectories(root.resolve("module").resolve("config"));
        SequencedSet<Path> folders = Project.Layout.configurations(
                root.resolve("module").resolve("config"),
                new LinkedHashSet<>(Arrays.asList(
                        root.resolve("module/config"),
                        root.resolve("missing"),
                        null)),
                Collections.emptyNavigableSet());
        assertThat(folders).hasSize(1);
        Path only = folders.getFirst();
        assertThat(only.isAbsolute()).isTrue();
        assertThat(only).isEqualTo(existing.toAbsolutePath().normalize());
    }

    @Test
    public void profiles_default_to_the_selected_profile_names() {
        System.setProperty("jenesis.project.properties", "release, supply-chain.properties");
        assertThat(new Project().profiles())
                .containsExactly(Path.of("release"), Path.of("supply-chain"));
    }

    @Test
    public void profiles_default_to_empty_without_a_selection() {
        assertThat(new Project().profiles()).isEmpty();
    }

    @Test
    public void profiles_wither_round_trips() {
        assertThat(new Project().profiles(Path.of("release"), Path.of("ci")).profiles())
                .containsExactly(Path.of("release"), Path.of("ci"));
    }

    @Test
    public void profile_folders_precede_plain_folders_module_before_root() throws IOException {
        Path moduleConfig = Files.createDirectories(root.resolve("module").resolve("config"));
        Path rootConfig = Files.createDirectories(root.resolve("root"));
        Path moduleProfile = Files.createDirectories(moduleConfig.resolve("release"));
        Path rootProfile = Files.createDirectories(rootConfig.resolve("release"));
        SequencedSet<Path> folders = Project.Layout.configurations(
                moduleConfig,
                new LinkedHashSet<>(List.of(rootConfig)),
                new LinkedHashSet<>(List.of(Path.of("release"))));
        assertThat(folders).containsExactly(
                moduleProfile.toAbsolutePath().normalize(),
                rootProfile.toAbsolutePath().normalize(),
                moduleConfig.toAbsolutePath().normalize(),
                rootConfig.toAbsolutePath().normalize());
    }

    @Test
    public void default_target_can_be_overridden() {
        assertThat(new Project().defaultTarget("foo", "bar").defaultTarget())
                .containsExactly("foo", "bar");
    }

    @Test
    public void system_property_overrides_root() {
        System.setProperty("jenesis.project.root", root.toString());
        assertThat(new Project().root()).isEqualTo(Path.of(root.toString()));
    }

    @Test
    public void system_property_overrides_target() {
        System.setProperty("jenesis.project.target", "custom-target");
        assertThat(new Project().target()).isEqualTo(Path.of("custom-target"));
    }

    @Test
    public void system_property_overrides_artifacts() {
        System.setProperty("jenesis.project.artifacts", "custom-artifacts");
        assertThat(new Project().artifacts()).isEqualTo(Path.of("custom-artifacts"));
    }

    @Test
    public void local_build_cache_is_disabled_by_default() {
        assertThat(new Project().cache()).isNull();
    }

    @Test
    public void local_build_cache_rejects_a_uri_value() {
        System.setProperty("jenesis.project.cache", "file:///tmp/cache");
        assertThatThrownBy(() -> new Project()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void empty_property_enables_local_build_cache_at_default_location() {
        System.setProperty("jenesis.project.root", root.toString());
        System.setProperty("jenesis.project.cache", "");
        BuildExecutorCache cache = new Project().cache();
        assertThat(cache).isInstanceOf(BuildExecutorFileCache.class);
        assertThat(((BuildExecutorFileCache) cache).root().endsWith(Path.of(".jenesis", "cache"))).isTrue();
    }

    @Test
    public void system_property_overrides_local_build_cache_location() {
        System.setProperty("jenesis.project.root", root.toString());
        System.setProperty("jenesis.project.cache", "custom-cache");
        BuildExecutorCache cache = new Project().cache();
        assertThat(cache).isInstanceOf(BuildExecutorFileCache.class);
        assertThat(((BuildExecutorFileCache) cache).root().endsWith(Path.of("custom-cache"))).isTrue();
    }

    @Test
    public void default_digest_is_sha_256() {
        assertThat(new Project().hashFunction()).isEqualTo(new HashDigestFunction("SHA-256"));
    }

    @Test
    public void system_property_overrides_digest() {
        System.setProperty("jenesis.project.digest", "SHA-512");
        assertThat(new Project().hashFunction())
                .isEqualTo(new HashDigestFunction("SHA-512"));
    }

    @Test
    public void digest_can_be_overridden() {
        HashDigestFunction digest = new HashDigestFunction("SHA-512");
        assertThat(new Project().hashFunction(digest).hashFunction()).isSameAs(digest);
    }

    @Test
    public void default_assembler_is_set() {
        assertThat(new Project().assembler()).isNotNull();
    }

    @Test
    public void assembler_can_be_overridden() {
        MultiProjectAssembler<ProjectModuleDescriptor> custom = (_, _, _) -> new AssemblyDescriptor((_, _) -> {});
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
                        BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false),
                project,
                new InferredMultiProjectAssembler(), false);
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
                        BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false),
                project,
                new InferredMultiProjectAssembler(), false);
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
                        BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false),
                project,
                new InferredMultiProjectAssembler(), false);
        assertThat(resolver.apply("sources/compile/dependencies/artifacts"))
                .isEqualTo("build/maven/compose/module/module-sources/compile/dependencies/artifacts");
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
                        BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false),
                project,
                new InferredMultiProjectAssembler(), false);
        assertThat(resolver.apply("sources/compile/dependencies/artifacts"))
                .isEqualTo("build/modules/compose/module/module-sources/compile/dependencies/artifacts");
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
                        BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false),
                project,
                new InferredMultiProjectAssembler(), false);
        assertThat(resolver.apply("sources/compile/dependencies/artifacts"))
                .isEqualTo("build/modules/compose/module/module-sources/compile/dependencies/artifacts");
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);

        Project.Layout.MODULAR.apply(executor, project, new InferredMultiProjectAssembler(), false);

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
                        BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false),
                project,
                new InferredMultiProjectAssembler(), false);
        assertThat(resolver.apply("sources")).isEqualTo("build/modules/compose/module/module-sources");
        assertThat(resolver.apply("")).isEqualTo("build/modules/compose/module/module-");
    }

    @Test
    public void build_returns_paths_for_the_default_target() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path source = Files.createDirectory(root.resolve("source"));
        Project.Layout layout = (executor, _, _, _) -> {
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
        Project.Layout layout = (executor, _, _, _) -> {
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
        Project.Layout layout = (executor, _, _, _) -> {
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

    @Test
    public void load_jenesis_properties_reads_a_file_from_root() throws IOException {
        Files.writeString(root.resolve("jenesis.properties"), "jenesis.test.sample.key=fromFile\n");
        Project.loadJenesisProperties(root);
        assertThat(System.getProperty("jenesis.test.sample.key")).isEqualTo("fromFile");
    }

    @Test
    public void load_jenesis_properties_does_not_override_an_explicit_system_property() throws IOException {
        System.setProperty("jenesis.test.sample.key", "fromCommandLine");
        Files.writeString(root.resolve("jenesis.properties"), "jenesis.test.sample.key=fromFile\n");
        Project.loadJenesisProperties(root);
        assertThat(System.getProperty("jenesis.test.sample.key")).isEqualTo("fromCommandLine");
    }

    @Test
    public void load_jenesis_properties_is_a_no_op_when_absent() throws IOException {
        Project.loadJenesisProperties(root);
        assertThat(System.getProperty("jenesis.test.sample.key")).isNull();
    }

    @Test
    public void load_jenesis_properties_chains_profile_files() throws IOException {
        Files.writeString(root.resolve("jenesis.properties"),
                "jenesis.project.properties=profile-a, profile-b\njenesis.test.sample.a=fromBase\n");
        Files.writeString(root.resolve("jenesis-profile-a.properties"),
                "jenesis.project.properties=profile-c\njenesis.test.sample.b=fromA\n");
        Files.writeString(root.resolve("jenesis-profile-b.properties"), "jenesis.test.sample.c=fromB\n");
        Files.writeString(root.resolve("jenesis-profile-c.properties"), "jenesis.test.sample.d=fromC\n");
        Project.loadJenesisProperties(root);
        assertThat(System.getProperty("jenesis.test.sample.a")).isEqualTo("fromBase");
        assertThat(System.getProperty("jenesis.test.sample.b")).isEqualTo("fromA");
        assertThat(System.getProperty("jenesis.test.sample.c")).isEqualTo("fromB");
        assertThat(System.getProperty("jenesis.test.sample.d")).isEqualTo("fromC");
    }

    @Test
    public void load_jenesis_properties_fails_on_a_missing_profile() throws IOException {
        Files.writeString(root.resolve("jenesis.properties"), "jenesis.project.properties=absent\n");
        assertThatThrownBy(() -> Project.loadJenesisProperties(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jenesis-absent.properties");
    }
}
