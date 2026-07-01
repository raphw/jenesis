package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.PathPlacement;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildExecutorModule;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.AssemblyDescriptor;
import build.jenesis.project.InferredComplianceModule;
import build.jenesis.project.InferredMultiProjectAssembler;
import build.jenesis.project.ProjectModule;
import build.jenesis.project.ProjectModuleDescriptor;
import build.jenesis.project.TestModule;
import build.jenesis.step.JPackage;
import build.jenesis.step.ProcessBuildStep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class InferredMultiProjectAssemblerTest {

    @TempDir
    private Path root;

    @Test
    public void main_in_module_properties_yields_main_class_argument_for_jar() throws IOException {
        Fixture fixture = setUp("main=com.example.Entry\n", false, false, false);
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties jarArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jar.properties"));
        assertThat(jarArguments.getProperty("--main-class")).isEqualTo("com.example.Entry");
    }

    @Test
    public void absent_main_in_module_properties_yields_no_jar_arguments() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        assertThat(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jar.properties")).doesNotExist();
    }

    @Test
    public void empty_main_in_module_properties_yields_no_jar_arguments() throws IOException {
        Fixture fixture = setUp("main=\n", false, false, false);
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        assertThat(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jar.properties")).doesNotExist();
    }

    @Test
    public void main_in_module_properties_yields_jpackage_arguments() throws IOException {
        Fixture fixture = setUp("main=com.example.Entry\n", false, false, false);
        Files.writeString(fixture.manifests().resolve(BuildStep.METADATA), "artifact=demo\n");
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties jpackageArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jpackage.properties"));
        assertThat(jpackageArguments.getProperty("--name")).isEqualTo("demo");
        assertThat(jpackageArguments.getProperty("--main-jar")).isEqualTo("classes.jar");
        assertThat(jpackageArguments.getProperty("--main-class")).isEqualTo("com.example.Entry");
    }

    @Test
    public void snapshot_version_is_sanitized_for_jpackage_app_version() throws IOException {
        Fixture fixture = setUp("main=com.example.Entry\n", false, false, false);
        Files.writeString(fixture.manifests().resolve(BuildStep.METADATA), "artifact=demo\nversion=0-SNAPSHOT\n");
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties jpackageArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jpackage.properties"));
        assertThat(jpackageArguments.getProperty("--app-version"))
                .as("jpackage rejects qualifiers like -SNAPSHOT on Windows, so only the dotted numeric prefix is passed")
                .isEqualTo("0");
    }

    @Test
    public void non_numeric_version_yields_no_jpackage_app_version() throws IOException {
        Fixture fixture = setUp("main=com.example.Entry\n", false, false, false);
        Files.writeString(fixture.manifests().resolve(BuildStep.METADATA), "artifact=demo\nversion=RELEASE\n");
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties jpackageArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jpackage.properties"));
        assertThat(jpackageArguments.stringPropertyNames())
                .as("a version with no dotted numeric prefix leaves jpackage to its own default")
                .doesNotContain("--app-version");
    }

    @Test
    public void absent_main_in_module_properties_yields_no_jpackage_arguments() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        assertThat(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jpackage.properties")).doesNotExist();
    }

    @Test
    public void package_type_enabled_adds_jpackage_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false, "app-image");
        Path packageOutput = fixture.execute("package/jpackage").get("package/jpackage");
        assertThat(packageOutput.resolve(JPackage.PACKAGES))
                .as("a module without a main class produces no application image")
                .doesNotExist();
    }

    @Test
    public void package_type_disabled_omits_jpackage_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/jpackage"))
                .rootCause()
                .hasMessage("Unknown selector: jpackage");
    }

    @Test
    public void modular_main_yields_module_jpackage_argument() throws IOException {
        Fixture fixture = setUp("main=com.example.Entry\nmodule=com.example.foo\n", false, false, false);
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties jpackageArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jpackage.properties"));
        assertThat(jpackageArguments.getProperty("--module")).isEqualTo("com.example.foo/com.example.Entry");
        assertThat(jpackageArguments.stringPropertyNames())
                .as("modular launch uses --module, not the classpath --main-jar/--main-class")
                .doesNotContain("--main-jar", "--main-class");
    }

    @Test
    public void module_in_module_properties_yields_add_modules_for_jlink() throws IOException {
        Fixture fixture = setUp("module=foo\n", false, false, false);
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties jlinkArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jlink.properties"));
        assertThat(jlinkArguments.getProperty("--add-modules")).isEqualTo("foo");
    }

    @Test
    public void absent_module_in_module_properties_yields_no_jlink_arguments() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        assertThat(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jlink.properties")).doesNotExist();
    }

    @Test
    public void a_process_command_file_in_configuration_yields_tool_arguments() throws IOException {
        Fixture fixture = setUp("main=\n", false, false, false);
        Files.writeString(fixture.configuration().resolve("process-javac.properties"), "-g=\n-parameters=\n");
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties javacArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("javac.properties"));
        assertThat(javacArguments.getProperty("-g")).isEqualTo("");
        assertThat(javacArguments.getProperty("-parameters")).isEqualTo("");
    }

    @Test
    public void a_process_command_file_adds_to_and_overrides_generated_arguments() throws IOException {
        Fixture fixture = setUp("main=\n", false, false, false);
        Files.writeString(fixture.manifests().resolve(BuildStep.METADATA), "version=1.0\n");
        Files.writeString(fixture.configuration().resolve("process-javac.properties"), "--module-version=9.9\n-g=\n");
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties javacArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("javac.properties"));
        assertThat(javacArguments.getProperty("--module-version"))
                .as("a configuration key overrides the build-generated one")
                .isEqualTo("9.9");
        assertThat(javacArguments.getProperty("-g"))
                .as("a configuration key without a build-generated counterpart is added")
                .isEqualTo("");
    }

    @Test
    public void an_empty_higher_precedence_process_command_file_shadows_a_lower_one() throws IOException {
        Fixture fixture = setUp("main=\n", false, false, false);
        Files.writeString(fixture.manifests().resolve(BuildStep.METADATA), "version=1.0\n");
        Files.writeString(fixture.configuration().resolve("process-javac.properties"), "-g=\n");
        Files.writeString(fixture.profile().resolve("process-javac.properties"), "");
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        SequencedProperties javacArguments = readProperties(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("javac.properties"));
        assertThat(javacArguments.getProperty("--module-version"))
                .as("the build-generated arguments remain")
                .isEqualTo("1.0");
        assertThat(javacArguments.getProperty("-g"))
                .as("the first-discovered (empty) file wins, shadowing the lower location's arguments")
                .isNull();
    }

    @Test
    public void jmod_flag_enabled_packages_a_module_archive() throws IOException {
        Fixture fixture = setUp("module=foo\n", false, false, false, null, true, false);
        Files.writeString(
                Files.createDirectory(fixture.sources.resolve(BuildStep.SOURCES)).resolve("module-info.java"),
                "module foo { }\n");
        Path jmodOutput = fixture.execute("sub/jmod").get("sub/jmod");
        assertThat(jmodOutput.resolve("jmods").resolve("foo.jmod")).isNotEmptyFile();
    }

    @Test
    public void jmod_flag_disabled_omits_jmod_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/jmod"))
                .rootCause()
                .hasMessage("Unknown selector: jmod");
    }

    @Test
    public void jlink_flag_disabled_omits_jlink_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/jlink"))
                .rootCause()
                .hasMessage("Unknown selector: jlink");
    }

    @Test
    public void native_image_flag_disabled_omits_native_image_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/native-image"))
                .rootCause()
                .hasMessage("Unknown selector: native-image");
    }

    @Test
    public void native_image_enabled_wires_package_inventory_so_the_binary_is_stageable() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false, null, false, false, true);
        assertThat(fixture.execute("package/inventory"))
                .as("a native-image-only package phase still feeds the binary through an inventory step")
                .containsKey("package/inventory");
    }

    @Test
    public void source_flag_enabled_adds_sources_jar_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, true, false);
        Files.createDirectory(fixture.sources.resolve(BuildStep.SOURCES));
        Files.writeString(fixture.sources.resolve(BuildStep.SOURCES).resolve("foo.java"), "// dummy");
        Path sourcesOutput = fixture.execute("sub/sources/archive").get("sub/sources/archive");
        assertThat(sourcesOutput.resolve("sources").resolve("sources.jar")).exists();
    }

    @Test
    public void source_flag_disabled_omits_sources_jar_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/sources/archive"))
                .rootCause()
                .hasMessage("Unknown selector: sources/archive");
    }

    @Test
    public void javadoc_flag_enabled_adds_javadoc_sub_module() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, true);
        Files.createDirectory(fixture.sources.resolve(BuildStep.SOURCES));
        Files.writeString(fixture.sources.resolve(BuildStep.SOURCES).resolve("module-info.java"), """
                module foo {
                }
                """);
        Path javadocOutput = fixture.execute("sub/documentation/archive").get("sub/documentation/archive");
        assertThat(javadocOutput.resolve("documentation").resolve("javadoc.jar")).exists();
    }

    @Test
    public void javadoc_flag_disabled_omits_javadoc_sub_module() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/documentation/archive"))
                .rootCause()
                .hasMessage("Unknown selector: documentation/archive");
    }

    @Test
    public void tests_flag_enabled_for_test_variant_without_engine_in_dependencies_fails_resolution() throws IOException {
        Fixture fixture = setUp("path=\ntest=main_artifact\n", true, false, false);
        Files.writeString(
                Files.createDirectory(fixture.sources.resolve(BuildStep.SOURCES)).resolve("Sample.java"),
                "public class Sample {}");
        assertThatThrownBy(() -> fixture.execute("sub/observed/test/resolved"))
                .rootCause()
                .hasMessageContaining("No test engine could be resolved");
    }

    @Test
    public void tests_flag_disabled_omits_test_sub_module() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/test/resolved"))
                .rootCause()
                .hasMessage("Unknown selector: test/resolved");
    }

    private Fixture setUp(String moduleProperties,
                          boolean tests,
                          boolean source,
                          boolean documentation) throws IOException {
        return setUp(moduleProperties, tests, source, documentation, null, false, false);
    }

    private Fixture setUp(String moduleProperties,
                          boolean tests,
                          boolean source,
                          boolean documentation,
                          String packageType) throws IOException {
        return setUp(moduleProperties, tests, source, documentation, packageType, false, false);
    }

    private Fixture setUp(String moduleProperties,
                          boolean tests,
                          boolean source,
                          boolean documentation,
                          String packageType,
                          boolean jmod,
                          boolean jlink) throws IOException {
        return setUp(moduleProperties, tests, source, documentation, packageType, jmod, jlink, false);
    }

    private Fixture setUp(String moduleProperties,
                          boolean tests,
                          boolean source,
                          boolean documentation,
                          String packageType,
                          boolean jmod,
                          boolean jlink,
                          boolean nativeImage) throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        Files.writeString(manifests.resolve(BuildStep.MODULE), moduleProperties);
        Path sources = Files.createDirectory(root.resolve("sources"));
        Path artifacts = Files.createDirectory(root.resolve("artifacts"));
        Path configuration = Files.createDirectory(root.resolve("configuration"));
        Path profile = Files.createDirectory(root.resolve("profile"));
        StringBuilder packaging = new StringBuilder();
        if (jmod) {
            packaging.append("jmod=true\n");
        }
        if (jlink) {
            packaging.append("jlink=true\n");
        }
        if (nativeImage) {
            packaging.append("native=true\n");
        }
        if (packageType != null) {
            packaging.append("jpackage=").append(packageType).append("\n");
        }
        if (!packaging.isEmpty()) {
            Files.writeString(configuration.resolve("packaging.properties"), packaging.toString());
        }
        Path build = Files.createDirectory(root.resolve("build"));
        ProjectModule base = new ProjectModule() {
            @Override
            public String name() {
                return "module";
            }

            @Override
            public SequencedSet<String> dependencies() {
                return Collections.emptyNavigableSet();
            }

            @Override
            public SequencedSet<String> sources() {
                return new LinkedHashSet<>(List.of(BuildExecutorModule.PREVIOUS + "sources"));
            }

            @Override
            public SequencedSet<String> resources() {
                return Collections.emptyNavigableSet();
            }

            @Override
            public SequencedSet<String> manifests() {
                return new LinkedHashSet<>(List.of(BuildExecutorModule.PREVIOUS + "manifests"));
            }

            @Override
            public SequencedSet<String> coordinates() {
                return new LinkedHashSet<>(List.of(BuildExecutorModule.PREVIOUS + "coordinates"));
            }

            @Override
            public SequencedSet<String> artifacts() {
                return new LinkedHashSet<>(List.of(BuildExecutorModule.PREVIOUS + "artifacts"));
            }

            @Override
            public SequencedSet<String> spdx() {
                return Collections.emptyNavigableSet();
            }
        };
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, new LinkedHashSet<>(List.of(profile, configuration)), tests, source, documentation, null, PathPlacement.INFERRED);
        AssemblyDescriptor assembled = new InferredMultiProjectAssembler().apply(descriptor, Map.of(), Map.of());
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addSource("manifests", manifests);
        executor.addSource("sources", sources);
        executor.addSource("artifacts", artifacts);
        executor.addModule("sub", assembled.build(),
                "manifests", "sources", "artifacts");
        for (Map.Entry<String, BuildExecutorModule> phase : assembled.tail().entrySet()) {
            executor.addModule(phase.getKey(), phase.getValue(), "sub");
        }
        return new Fixture(executor, manifests, sources, configuration, profile);
    }

    private record Fixture(BuildExecutor executor, Path manifests, Path sources, Path configuration, Path profile) {

        SequencedMap<String, Path> execute(String selector) {
            return executor.execute(Runnable::run, selector).toCompletableFuture().join();
        }
    }

    @Test
    public void sub_module_configurators_default_to_identity_and_round_trip() {
        InferredMultiProjectAssembler assembler = new InferredMultiProjectAssembler();
        assertThat(assembler.check().apply(null)).as("check configurator defaults to identity").isNull();
        assertThat(assembler.format().apply(null)).as("format configurator defaults to identity").isNull();
        assertThat(assembler.validate().apply(null)).as("validate configurator defaults to identity").isNull();
        assertThat(assembler.observe().apply(null)).as("observe configurator defaults to identity").isNull();
        assertThat(assembler.test().apply(null)).as("test configurator defaults to identity").isNull();
        assertThat(assembler.compliance().apply(null)).as("compliance configurator defaults to identity").isNull();

        Function<TestModule, BuildExecutorModule> custom = test -> test.requireEngine(false);
        assertThat(assembler.test(custom).test()).as("the test wither stores the configurator").isSameAs(custom);

        Function<InferredComplianceModule, BuildExecutorModule> customCompliance = compliance -> compliance;
        assertThat(assembler.compliance(customCompliance).compliance())
                .as("the compliance wither stores the configurator").isSameAs(customCompliance);
    }

    private static SequencedProperties readProperties(Path path) throws IOException {
        assertThat(path).exists();
        return SequencedProperties.ofFiles(path);
    }
}
