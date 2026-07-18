package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Platform;
import build.jenesis.SequencedProperties;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.project.AssemblyDescriptor;
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
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
        assertThat(dependencies).containsOnlyKeys("main/compile/module/bar", "main/runtime/module/bar");
        assertThat(dependencies.getProperty("main/compile/module/bar")).isEmpty();
        assertThat(dependencies.getProperty("main/runtime/module/bar")).isEmpty();
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void resolves_root_module_despite_skip_marker() throws IOException {
        Files.createFile(project.resolve(BuildExecutor.SKIP_MARKER));
        Files.writeString(project.resolve("module-info.java"), """
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKey("module/module-/sources");
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        Path module = results.get("module/module-/manifests");
        Path versionsFile = module.resolve(BuildStep.VERSIONS);
        assertThat(versionsFile).exists();
        SequencedProperties versions = SequencedProperties.ofFiles(versionsFile);
        assertThat(versions).containsOnly(
                Map.entry("main/module/bar", "1.2.3"),
                Map.entry("main/module/transitive.pin", "9.9.9"));
    }

    @Test
    public void emits_aliases_properties_from_javadoc_declarations() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.alias toolkit.lib org.example/plain-lib 1.0
                 */
                module foo {
                  requires toolkit.lib;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        Path module = results.get("module/module-/manifests");
        Path aliasesFile = module.resolve(BuildStep.ALIASES);
        assertThat(aliasesFile).exists();
        assertThat(SequencedProperties.ofFiles(aliasesFile)).containsOnly(
                Map.entry("main/module/toolkit.lib", "org.example/plain-lib 1.0"));
    }

    @Test
    public void selects_guarded_pin_matching_platform() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar :win:1.0 [windows]
                 * @jenesis.pin bar 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .platform(Platform.of("windows,x86_64")));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties versions = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.VERSIONS));
        assertThat(versions).containsOnly(Map.entry("main/module/bar", ":win:1.0"));
    }

    @Test
    public void guarded_pin_value_carries_checksum() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar :win:1.0 SHA-256/aaa [windows]
                 * @jenesis.pin bar 1.0 SHA-256/bbb
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .platform(Platform.of("windows,x86_64")));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties versions = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.VERSIONS));
        assertThat(versions).containsOnly(Map.entry("main/module/bar", ":win:1.0 SHA-256/aaa"));
    }

    @Test
    public void falls_back_to_unguarded_pin_without_platform_match() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar :win:1.0 [windows]
                 * @jenesis.pin bar 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .platform(Platform.of("linux,x86_64")));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties versions = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.VERSIONS));
        assertThat(versions).containsOnly(Map.entry("main/module/bar", "1.0"));
    }

    @Test
    public void more_specific_guard_wins_over_general_guard() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar :win:1.0 [windows]
                 * @jenesis.pin bar :win-aarch64:1.0 [windows,aarch64]
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .platform(Platform.of("windows,aarch64")));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties versions = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.VERSIONS));
        assertThat(versions).containsOnly(Map.entry("main/module/bar", ":win-aarch64:1.0"));
    }

    @Test
    public void ambiguous_guards_fail_the_build() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar :win:1.0 [windows]
                 * @jenesis.pin bar :x64:1.0 [x86_64]
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .platform(Platform.of("windows,x86_64")));
        assertThatThrownBy(() -> executor.execute(Runnable::run).toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("Ambiguous")
                .hasMessageContaining("main/module/bar");
    }

    @Test
    public void unmatched_guard_without_fallback_yields_no_pin() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar :win:1.0 [windows]
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .platform(Platform.of("linux,x86_64")));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results.get("module/module-/manifests").resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void emits_boms_properties_from_javadoc_declarations() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.bom acme.platform
                 * @jenesis.bom kotlinc/module/other.platform 2.1.0 SHA-256/cafebabe
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties boms = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.BOMS));
        assertThat(boms).containsOnly(
                Map.entry("bom/main/module/acme.platform", ""),
                Map.entry("bom/kotlinc/module/other.platform", "2.1.0 SHA-256/cafebabe"));
    }

    @Test
    public void expands_local_bom_file_to_entries() throws IOException {
        Files.writeString(project.resolve("bom-team.properties"), """
                bar = 1.2.3 SHA-256/aaa
                org.slf4j/slf4j-api = 2.0.17
                """);
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.bom bom-team.properties
                 * @jenesis.bom acme.platform 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .boms(new LinkedHashSet<>(List.of(project))));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties boms = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.BOMS));
        assertThat(boms.stringPropertyNames()).containsExactly(
                "entry/main/module/bar",
                "entry/main/maven/org.slf4j/slf4j-api",
                "bom/main/module/acme.platform");
        assertThat(boms.getProperty("entry/main/module/bar")).isEqualTo("1.2.3 SHA-256/aaa");
        assertThat(boms.getProperty("entry/main/maven/org.slf4j/slf4j-api")).isEqualTo("2.0.17");
        assertThat(boms.getProperty("bom/main/module/acme.platform")).isEqualTo("1.0");
    }

    @Test
    public void local_bom_with_group_qualifier_prefixes_entries() throws IOException {
        Files.writeString(project.resolve("bom-team.properties"), """
                bar = 1.2.3
                """);
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.bom kotlinc/bom-team.properties
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .boms(new LinkedHashSet<>(List.of(project))));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties boms = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.BOMS));
        assertThat(boms).containsOnly(Map.entry("entry/kotlinc/module/bar", "1.2.3"));
    }

    @Test
    public void first_boms_location_shadows_later_ones() throws IOException {
        Path first = Files.createDirectory(project.resolve("first"));
        Path second = Files.createDirectory(project.resolve("second"));
        Files.writeString(first.resolve("bom-team.properties"), """
                bar = 1.0
                """);
        Files.writeString(second.resolve("bom-team.properties"), """
                bar = 2.0
                """);
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.bom bom-team.properties
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .boms(new LinkedHashSet<>(List.of(first, second))));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties boms = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.BOMS));
        assertThat(boms).containsOnly(Map.entry("entry/main/module/bar", "1.0"));
    }

    @Test
    public void selects_guarded_bom_matching_platform() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.bom acme.platform 2.0 [windows]
                 * @jenesis.bom acme.platform 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .platform(Platform.of("windows,x86_64")));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties boms = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.BOMS));
        assertThat(boms).containsOnly(Map.entry("bom/main/module/acme.platform", "2.0"));
    }

    @Test
    public void unmatched_guarded_bom_without_fallback_is_skipped() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.bom acme.platform 2.0 [windows]
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .platform(Platform.of("linux,x86_64")));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results.get("module/module-/manifests").resolve(BuildStep.BOMS)).doesNotExist();
    }

    @Test
    public void missing_local_bom_fails_the_build() throws IOException {
        Files.writeString(project.resolve("module-info.java"), """
                /**
                 * @jenesis.bom bom-missing.properties
                 */
                module foo {
                  requires bar;
                }
                """);
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project)
                .boms(new LinkedHashSet<>(List.of(project))));
        assertThatThrownBy(() -> executor.execute(Runnable::run).toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Local BOM not found: main/bom-missing.properties");
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        root.addModule("modules", ModularProject.make(project,
                "main",
                "module",
                _ -> true,
                Map.of(),
                Map.of("module", new ModularJarResolver(false)),
                null,
                true,
                Collections.emptyNavigableSet(),
                Collections.emptyNavigableSet(),
                (descriptor, _, _) -> {
                    switch (descriptor.name()) {
                        case "module-foo" -> {
                            assertThat(descriptor.dependencies()).isEmpty();
                            assertThat(descriptor.location()).isEqualTo(project.resolve("foo"));
                        }
                        case "module-bar" -> {
                            assertThat(descriptor.dependencies()).containsExactly("module-foo");
                            assertThat(descriptor.location()).isEqualTo(project.resolve("bar"));
                        }
                        default -> fail("Unexpected module: " + descriptor.name());
                    }
                    return new AssemblyDescriptor((buildExecutor, inherited) -> {
                        switch (descriptor.name()) {
                            case "module-foo" -> assertThat(inherited).containsOnlyKeys(
                                    "../manifests",
                                    "../coordinates",
                                    "../sources",
                                    "../dependencies/artifacts");
                            case "module-bar" -> assertThat(inherited).containsOnlyKeys(
                                    "../manifests",
                                    "../coordinates",
                                    "../sources",
                                    "../dependencies/artifacts",
                                    "../../module-foo/dependencies/prepare",
                                    "../../module-foo/dependencies/artifacts",
                                    "../../module-foo/produce/java/classes",
                                    "../../module-foo/produce/java/artifacts",
                                    "../../module-foo/assign",
                                    "../../module-foo/inventory");
                            default -> fail("Unexpected module: " + descriptor.name());
                        }
                        buildExecutor.addModule("java", new JavaToolchainModule(),
                                "../sources", "../manifests",
                                "../dependencies/artifacts");
                    });
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
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
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addModule("module", new ModularProject("module", project));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties module = SequencedProperties.ofFiles(
                results.get("module/module-/manifests").resolve(BuildStep.MODULE));
        assertThat(module.getProperty("main")).isNull();
    }

}
