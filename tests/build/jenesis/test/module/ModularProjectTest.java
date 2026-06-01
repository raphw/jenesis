package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaToolchainModule;
import build.jenesis.project.MultiProjectModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

public class ModularProjectTest {

    @TempDir
    private Path project, build;

    @Test
    public void at_tests_with_argument_not_in_requires_fails_validation() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.test other.module
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("module", new ModularProject("module", project, _ -> true, true));
        assertThatThrownBy(() -> executor.execute(Runnable::run).toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Test module 'foo' declares @jenesis.test other.module but does not"
                        + " 'requires other.module;' (declared requires: [bar])");
    }

    @Test
    public void can_resolve_module() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("module", new ModularProject("module", project, _ -> true, true));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("module/module-/sources",
                "module/module-/manifests",
                "module/module-/coordinates");
        assertThat(results.get("module/module-/sources").resolve(BuildStep.SOURCES + "module-info.java")).exists();
        Path module = results.get("module/module-/manifests");
        assertThat(module.resolve(BuildStep.IDENTITY)).doesNotExist();
        Path coordinatesFolder = results.get("module/module-/coordinates");
        SequencedProperties coordinates = SequencedProperties.ofFiles(coordinatesFolder.resolve(BuildStep.IDENTITY));
        assertThat(coordinates).containsOnlyKeys("module/foo");
        assertThat(coordinates.getProperty("module/foo")).isEmpty();
        Path moduleRequires = module.resolve(BuildStep.REQUIRES);
        assertThat(moduleRequires).exists();
        SequencedProperties dependencies = SequencedProperties.ofFiles(moduleRequires);
        assertThat(dependencies).containsOnlyKeys("module/bar");
        assertThat(dependencies.getProperty("module/bar")).isEmpty();
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void emits_versions_properties_from_javadoc_pins() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar 1.2.3
                 * @jenesis.pin transitive.pin 9.9.9
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("module", new ModularProject("module", project, _ -> true, true));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        Path module = results.get("module/module-/manifests");
        Path compileVersionsFile = module.resolve(BuildStep.VERSIONS);
        assertThat(compileVersionsFile).exists();
        SequencedProperties compileVersions = SequencedProperties.ofFiles(compileVersionsFile);
        assertThat(compileVersions).containsOnly(
                Map.entry("module/bar", "1.2.3"),
                Map.entry("module/transitive.pin", "9.9.9"));
        Path runtimeVersionsFile = module.resolve(BuildStep.VERSIONS);
        assertThat(runtimeVersionsFile).exists();
        SequencedProperties runtimeVersions = SequencedProperties.ofFiles(runtimeVersionsFile);
        assertThat(runtimeVersions).containsOnly(
                Map.entry("module/bar", "1.2.3"),
                Map.entry("module/transitive.pin", "9.9.9"));
    }

    @Test
    public void omits_versions_properties_when_no_pins() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * Module description with no pin tags.
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("module", new ModularProject("module", project, _ -> true, true));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        Path module = results.get("module/module-/manifests");
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void can_resolve_multi_module() throws IOException {
        Files.writeString(Files.createDirectory(project.resolve("foo")).resolve("module-info.java"), """
                module foo {
                  exports foo;
                }
                """);
        Files.writeString(Files.createDirectories(project.resolve("foo/foo")).resolve("Foo.java"), """
                package foo;
                public class Foo { }
                """);
        Files.writeString(Files.createDirectory(project.resolve("bar")).resolve("module-info.java"), """
                module bar {
                  requires foo;
                }
                """);
        Files.writeString(Files.createDirectories(project.resolve("bar/bar")).resolve("Bar.java"), """
                package bar;
                import foo.Foo;
                public class Bar extends Foo { }
                """);
        BuildExecutor root = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        root.addModule("modules", ModularProject.make(project,
                "module",
                _ -> true,
                Map.of(),
                Map.of("module", new ModularJarResolver(false)),
                false,
                true,
                new HashDigestFunction("MD5"),
                (descriptor, _, _) -> {
                    switch (descriptor.name()) {
                        case "module-foo" -> assertThat(descriptor.dependencies()).isEmpty();
                        case "module-bar" -> assertThat(descriptor.dependencies()).containsExactly("module-foo");
                        default -> fail("Unexpected module: " + descriptor.name());
                    }
                    return (buildExecutor, inherited) -> {
                        switch (descriptor.name()) {
                            case "module-foo" -> assertThat(inherited).containsOnlyKeys(
                                    "../manifests",
                                    "../coordinates",
                                    "../sources",
                                    "../compile/dependencies/resolved",
                                    "../compile/dependencies/artifacts",
                                    "../runtime/dependencies/resolved",
                                    "../runtime/dependencies/artifacts");
                            case "module-bar" -> assertThat(inherited).containsOnlyKeys(
                                    "../manifests",
                                    "../coordinates",
                                    "../sources",
                                    "../compile/dependencies/resolved",
                                    "../compile/dependencies/artifacts",
                                    "../runtime/dependencies/resolved",
                                    "../runtime/dependencies/artifacts",
                                    "../../module-foo/compile/prepare",
                                    "../../module-foo/compile/dependencies/resolved",
                                    "../../module-foo/compile/dependencies/artifacts",
                                    "../../module-foo/runtime/prepare",
                                    "../../module-foo/runtime/dependencies/resolved",
                                    "../../module-foo/runtime/dependencies/artifacts",
                                    "../../module-foo/produce/java/classes",
                                    "../../module-foo/produce/java/artifacts",
                                    "../../module-foo/assign",
                                    "../../module-foo/inventory");
                            default -> fail("Unexpected module: " + descriptor.name());
                        }
                        buildExecutor.addModule("java", new JavaToolchainModule(),
                                "../sources", "../manifests",
                                "../compile/dependencies/artifacts",
                                "../runtime/dependencies/artifacts");
                    };
                }));
        SequencedMap<String, Path> results = root.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties foo = SequencedProperties.ofFiles(results
                .get("modules/module-foo/assign")
                .resolve(BuildStep.IDENTITY));
        assertThat(foo.stringPropertyNames()).containsExactly("module/foo");
        assertThat(foo.getProperty("module/foo"))
                .isEqualTo("../../produce/java/artifacts/jar/output/artifacts/classes.jar");
        SequencedProperties bar = SequencedProperties.ofFiles(results
                .get("modules/module-bar/assign")
                .resolve(BuildStep.IDENTITY));
        assertThat(bar.stringPropertyNames()).containsExactly("module/bar");
        assertThat(bar.getProperty("module/bar"))
                .isEqualTo("../../produce/java/artifacts/jar/output/artifacts/classes.jar");
        assertThat(results.keySet())
                .contains("modules/module-foo/inventory", "modules/module-bar/inventory")
                .doesNotContain("modules/module-foo/coordinates", "modules/module-bar/coordinates");
        SequencedProperties fooInventory = SequencedProperties.ofFiles(results
                .get("modules/module-foo/inventory")
                .resolve("inventory.properties"));
        assertThat(fooInventory.getProperty("module-foo.module")).isEqualTo("foo");
        assertThat(fooInventory.getProperty("module-foo.runtime.0"))
                .endsWith("/classes.jar");
        assertThat(fooInventory.getProperty("module-foo.artifacts.0"))
                .endsWith("/classes.jar");
        SequencedProperties barInventory = SequencedProperties.ofFiles(results
                .get("modules/module-bar/inventory")
                .resolve("inventory.properties"));
        assertThat(barInventory.getProperty("module-bar.module")).isEqualTo("bar");
        assertThat(barInventory.getProperty("module-bar.runtime.0"))
                .endsWith("/classes.jar");
        assertThat(barInventory.getProperty("module-bar.artifacts.0"))
                .endsWith("/classes.jar");
    }

    @Test
    public void at_main_lands_in_module_properties() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.main com.example.Entry
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("module", new ModularProject("module", project, _ -> true, true));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties module = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.MODULE));
        assertThat(module.getProperty("main")).isEqualTo("com.example.Entry");
    }

    @Test
    public void absent_at_main_leaves_module_properties_without_main_key() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("module", new ModularProject("module", project, _ -> true, true));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties module = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.MODULE));
        assertThat(module.getProperty("main")).isNull();
    }

}
