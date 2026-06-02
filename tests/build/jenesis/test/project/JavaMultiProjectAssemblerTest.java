package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.PathPlacement;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildExecutorModule;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.DependencyScope;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.ProjectModule;
import build.jenesis.project.ProjectModuleDescriptor;
import build.jenesis.step.JPackage;
import build.jenesis.step.ProcessBuildStep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JavaMultiProjectAssemblerTest {

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
    public void absent_main_in_module_properties_yields_no_jpackage_arguments() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        Path prepareOutput = fixture.execute("sub/prepare").get("sub/prepare");
        assertThat(prepareOutput.resolve(ProcessBuildStep.PROCESS).resolve("jpackage.properties")).doesNotExist();
    }

    @Test
    public void package_type_enabled_adds_package_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false, "app-image");
        Path packageOutput = fixture.execute("sub/package").get("sub/package");
        assertThat(packageOutput.resolve(JPackage.PACKAGES))
                .as("a module without a main class produces no application image")
                .doesNotExist();
    }

    @Test
    public void package_type_disabled_omits_package_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/package"))
                .rootCause()
                .hasMessage("Unknown selector: package");
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
    public void source_flag_enabled_adds_sources_jar_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, true, false);
        Files.createDirectory(fixture.sources.resolve(BuildStep.SOURCES));
        Files.writeString(fixture.sources.resolve(BuildStep.SOURCES).resolve("foo.java"), "// dummy");
        Path sourcesOutput = fixture.execute("sub/sources").get("sub/sources");
        assertThat(sourcesOutput.resolve("sources").resolve("sources.jar")).exists();
    }

    @Test
    public void source_flag_disabled_omits_sources_jar_step() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/sources"))
                .rootCause()
                .hasMessage("Unknown selector: sources");
    }

    @Test
    public void javadoc_flag_enabled_adds_javadoc_sub_module() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, true);
        Files.createDirectory(fixture.sources.resolve(BuildStep.SOURCES));
        Files.writeString(fixture.sources.resolve(BuildStep.SOURCES).resolve("module-info.java"), """
                module foo {
                }
                """);
        Path javadocOutput = fixture.execute("sub/javadoc/artifacts").get("sub/javadoc/artifacts");
        assertThat(javadocOutput.resolve("documentation").resolve("javadoc.jar")).exists();
    }

    @Test
    public void javadoc_flag_disabled_omits_javadoc_sub_module() throws IOException {
        Fixture fixture = setUp("path=\n", false, false, false);
        assertThatThrownBy(() -> fixture.execute("sub/javadoc/artifacts"))
                .rootCause()
                .hasMessage("Unknown selector: javadoc/artifacts");
    }

    @Test
    public void tests_flag_enabled_for_test_variant_without_engine_in_dependencies_fails_resolution() throws IOException {
        Fixture fixture = setUp("path=\ntest=main_artifact\n", true, false, false);
        Files.writeString(
                Files.createDirectory(fixture.sources.resolve(BuildStep.SOURCES)).resolve("Sample.java"),
                "public class Sample {}");
        assertThatThrownBy(() -> fixture.execute("sub/test/resolved"))
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
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        Files.writeString(manifests.resolve(BuildStep.MODULE), moduleProperties);
        Path sources = Files.createDirectory(root.resolve("sources"));
        Path compileResolved = Files.createDirectory(root.resolve("compile-resolved"));
        Path runtimeResolved = Files.createDirectory(root.resolve("runtime-resolved"));
        Path compileArtifacts = Files.createDirectory(root.resolve("compile-artifacts"));
        Path runtimeArtifacts = Files.createDirectory(root.resolve("runtime-artifacts"));
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
            public SequencedSet<String> resolved(DependencyScope scope) {
                return new LinkedHashSet<>(List.of(BuildExecutorModule.PREVIOUS + scope.label() + "-resolved"));
            }

            @Override
            public SequencedSet<String> artifacts(DependencyScope scope) {
                return new LinkedHashSet<>(List.of(BuildExecutorModule.PREVIOUS + scope.label() + "-artifacts"));
            }
        };
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, tests, source, documentation, false, PathPlacement.INFERRED);
        BuildExecutorModule assembled = new JavaMultiProjectAssembler(false, null, packageType, jmod, jlink).apply(descriptor, Map.of(), Map.of());
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addSource("manifests", manifests);
        executor.addSource("sources", sources);
        executor.addSource("compile-resolved", compileResolved);
        executor.addSource("runtime-resolved", runtimeResolved);
        executor.addSource("compile-artifacts", compileArtifacts);
        executor.addSource("runtime-artifacts", runtimeArtifacts);
        executor.addModule("sub", assembled,
                "manifests", "sources",
                "compile-resolved", "runtime-resolved",
                "compile-artifacts", "runtime-artifacts");
        return new Fixture(executor, manifests, sources);
    }

    private record Fixture(BuildExecutor executor, Path manifests, Path sources) {

        SequencedMap<String, Path> execute(String selector) {
            return executor.execute(Runnable::run, selector).toCompletableFuture().join();
        }
    }

    private static SequencedProperties readProperties(Path path) throws IOException {
        assertThat(path).exists();
        return SequencedProperties.ofFiles(path);
    }
}
