package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaModule;
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
                 * @tests other.module
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("module", new ModularProject("module", project, _ -> true));
        assertThatThrownBy(() -> executor.execute(Runnable::run).toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Test module 'foo' declares @tests other.module but does not"
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
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("module", new ModularProject("module", project, _ -> true));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("module/module-/sources", "module/module-/manifests");
        assertThat(results.get("module/module-/sources").resolve(BuildStep.SOURCES + "module-info.java")).exists();
        Path module = results.get("module/module-/manifests");
        assertThat(module.resolve(BuildStep.IDENTITY)).exists();
        Properties coordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(module.resolve(BuildStep.IDENTITY))) {
            coordinates.load(reader);
        }
        assertThat(coordinates).containsOnlyKeys("module/foo");
        assertThat(coordinates.getProperty("module/foo")).isEmpty();
        Path moduleRequires = module.resolve(BuildStep.REQUIRES);
        assertThat(moduleRequires).exists();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(moduleRequires)) {
            dependencies.load(reader);
        }
        assertThat(dependencies).containsOnlyKeys("module/bar");
        assertThat(dependencies.getProperty("module/bar")).isEmpty();
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void emits_versions_properties_from_javadoc_pins() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @requires bar 1.2.3
                 * @requires transitive.pin 9.9.9
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("module", new ModularProject("module", project, _ -> true));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        Path module = results.get("module/module-/manifests");
        Properties compileVersions = new Properties();
        Path compileVersionsFile = module.resolve(BuildStep.VERSIONS);
        assertThat(compileVersionsFile).exists();
        try (Reader reader = Files.newBufferedReader(compileVersionsFile)) {
            compileVersions.load(reader);
        }
        assertThat(compileVersions).containsOnly(
                Map.entry("module/bar", "1.2.3"),
                Map.entry("module/transitive.pin", "9.9.9"));
        Properties runtimeVersions = new Properties();
        Path runtimeVersionsFile = module.resolve(BuildStep.VERSIONS);
        assertThat(runtimeVersionsFile).exists();
        try (Reader reader = Files.newBufferedReader(runtimeVersionsFile)) {
            runtimeVersions.load(reader);
        }
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
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("module", new ModularProject("module", project, _ -> true));
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
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        root.addModule("modules", ModularProject.make(project,
                "module",
                _ -> true,
                "SHA256",
                Map.of(),
                Map.of("module", new ModularJarResolver(false)),
                descriptor -> {
                    switch (descriptor.name()) {
                        case "module-foo" -> assertThat(descriptor.dependencies()).isEmpty();
                        case "module-bar" -> assertThat(descriptor.dependencies()).containsExactly("module-foo");
                        default -> fail("Unexpected module: " + descriptor.name());
                    }
                    return (buildExecutor, inherited) -> {
                        switch (descriptor.name()) {
                            case "module-foo" -> assertThat(inherited).containsOnlyKeys(
                                    "../manifests",
                                    "../sources",
                                    "../compile/dependencies/checked",
                                    "../compile/dependencies/artifacts",
                                    "../runtime/dependencies/checked",
                                    "../runtime/dependencies/artifacts");
                            case "module-bar" -> assertThat(inherited).containsOnlyKeys(
                                    "../manifests",
                                    "../sources",
                                    "../compile/dependencies/checked",
                                    "../compile/dependencies/artifacts",
                                    "../runtime/dependencies/checked",
                                    "../runtime/dependencies/artifacts",
                                    "../../module-foo/compile/prepare",
                                    "../../module-foo/compile/dependencies/resolved",
                                    "../../module-foo/compile/dependencies/checked",
                                    "../../module-foo/compile/dependencies/artifacts",
                                    "../../module-foo/runtime/prepare",
                                    "../../module-foo/runtime/dependencies/resolved",
                                    "../../module-foo/runtime/dependencies/checked",
                                    "../../module-foo/runtime/dependencies/artifacts",
                                    "../../module-foo/produce/java/classes",
                                    "../../module-foo/produce/java/versions",
                                    "../../module-foo/produce/java/artifacts",
                                    "../../module-foo/assign");
                            default -> fail("Unexpected module: " + descriptor.name());
                        }
                        buildExecutor.addModule("java", new JavaModule(),
                                "../sources", "../manifests",
                                "../compile/dependencies/artifacts",
                                "../runtime/dependencies/artifacts");
                    };
                }));
        SequencedMap<String, Path> results = root.execute(Runnable::run).toCompletableFuture().join();
        Properties foo = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(results
                .get("modules/compose/module/module-foo/assign")
                .resolve(BuildStep.IDENTITY))) {
            foo.load(reader);
        }
        assertThat(foo.stringPropertyNames()).containsExactly("module/foo");
        assertThat(foo.getProperty("module/foo"))
                .isEqualTo("../../produce/java/artifacts/output/artifacts/classes.jar");
        Properties bar = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(results
                .get("modules/compose/module/module-bar/assign")
                .resolve(BuildStep.IDENTITY))) {
            bar.load(reader);
        }
        assertThat(bar.stringPropertyNames()).containsExactly("module/bar");
        assertThat(bar.getProperty("module/bar"))
                .isEqualTo("../../produce/java/artifacts/output/artifacts/classes.jar");
    }

    @Test
    public void artifactsByModule_links_classes_sources_and_javadoc_under_sub_module_folder() {
        Function<Path, Optional<Path>> placement = ModularProject.artifactsByModule();
        Path classes = Path.of("/wrap/build/module/module-foo/produce/java/artifacts/output/artifacts/classes.jar");
        Path sources = Path.of("/wrap/build/module/module-foo/produce/sources/output/artifacts/sources.jar");
        Path javadoc = Path.of("/wrap/build/module/module-foo/produce/javadoc/artifacts/output/artifacts/javadoc.jar");
        Path pom = Path.of("/wrap/build/module/module-foo/build/pom/output/pom.xml");
        Path other = Path.of("/wrap/build/module/module-foo/build/java/classes/output/A.class");
        assertThat(placement.apply(classes)).contains(Path.of("module-foo", "classes.jar"));
        assertThat(placement.apply(sources)).contains(Path.of("module-foo", "sources.jar"));
        assertThat(placement.apply(javadoc)).contains(Path.of("module-foo", "javadoc.jar"));
        assertThat(placement.apply(pom)).isEmpty();
        assertThat(placement.apply(other)).isEmpty();
    }
}
