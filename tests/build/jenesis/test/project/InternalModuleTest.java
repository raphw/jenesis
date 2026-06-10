package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.project.InternalModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class InternalModuleTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    private static Path shared;

    private static Path jenesisJar;

    @TempDir(cleanup = CleanupMode.NEVER)
    private Path root, work;

    private BuildExecutor buildExecutor;

    @BeforeAll
    public static void locateJenesisJar() throws Exception {
        Path location = Path.of(BuildExecutor.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(location)) {
            Path target = shared.resolve("build.jenesis.jar");
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
                Files.walkFileTree(location, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String entry = location.relativize(file).toString().replace(File.separatorChar, '/');
                        jar.putNextEntry(new JarEntry(entry));
                        Files.copy(file, jar);
                        jar.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            jenesisJar = target;
        } else {
            jenesisJar = location;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }

    @AfterEach
    public void tearDown() {
        buildExecutor = null;
        System.gc();
    }

    @Test
    public void can_compile_and_run_modular_plugin() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildStepResult;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        import java.util.concurrent.CompletableFuture;
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                                executor.addStep("marker", (_, context, _) -> {
                                    Files.writeString(context.next().resolve("out.txt"), "hello");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                            }
                        }
                        """));

        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKey("internal/marker");
        assertThat(steps.get("internal/marker").resolve("out.txt")).content().isEqualTo("hello");
    }

    @Test
    public void plugin_step_can_read_predecessor_argument_folder() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildStepResult;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        import java.util.concurrent.CompletableFuture;
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                                executor.addStep("first", (_, context, _) -> {
                                    Files.writeString(context.next().resolve("payload.txt"), "produced");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                                executor.addStep("second", (_, context, args) -> {
                                    Path predecessor = args.get("first").folder();
                                    String content = Files.readString(predecessor.resolve("payload.txt"));
                                    Files.writeString(context.next().resolve("out.txt"), "seen:" + content);
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                }, "first");
                            }
                        }
                        """));

        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps.get("internal/second").resolve("out.txt")).content().isEqualTo("seen:produced");
    }

    @Test
    public void plugin_can_add_nested_module() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildStepResult;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        import java.util.concurrent.CompletableFuture;
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                                executor.addModule("inner", (sub, _) -> {
                                    sub.addStep("marker", (_, context, _) -> {
                                        Files.writeString(context.next().resolve("out.txt"), "nested");
                                        return CompletableFuture.completedStage(new BuildStepResult(true));
                                    });
                                });
                            }
                        }
                        """));

        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps.get("internal/inner/marker").resolve("out.txt")).content().isEqualTo("nested");
    }

    @Test
    public void fails_when_source_lacks_module_info() throws IOException {
        Path source = Files.createDirectory(work.resolve("non-modular"));
        Files.writeString(source.resolve("Foo.java"), "public class Foo {}");

        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))));

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not modular");
    }

    @Test
    public void resolves_named_provider_when_load_build_module_set() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildModuleName;
                        import build.jenesis.BuildStepResult;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        import java.util.concurrent.CompletableFuture;
                        @BuildModuleName("foo")
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                                executor.addStep("marker", (_, context, _) -> {
                                    Files.writeString(context.next().resolve("out.txt"), "named");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                            }
                        }
                        """));

        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true)))
                .buildModuleName("foo"));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps.get("internal/marker").resolve("out.txt")).content().isEqualTo("named");
    }

    @Test
    public void fails_when_no_provider_with_requested_name() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildModuleName;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        @BuildModuleName("foo")
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {}
                        }
                        """));

        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true)))
                .buildModuleName("bar"));

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("named bar");
    }

    @Test
    public void fails_when_only_annotated_provider_exists_without_load_build_module() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildModuleName;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        @BuildModuleName("foo")
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {}
                        }
                        """));

        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))));

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No unnamed");
    }

    @Test
    public void fails_when_multiple_unnamed_providers() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.PluginA, test.plugin.PluginB; }",
                Map.of(
                        "test/plugin/PluginA.java", """
                                package test.plugin;
                                import build.jenesis.BuildExecutor;
                                import build.jenesis.BuildExecutorModule;
                                import java.nio.file.Path;
                                import java.util.SequencedMap;
                                public class PluginA implements BuildExecutorModule {
                                    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {}
                                }
                                """,
                        "test/plugin/PluginB.java", """
                                package test.plugin;
                                import build.jenesis.BuildExecutor;
                                import build.jenesis.BuildExecutorModule;
                                import java.nio.file.Path;
                                import java.util.SequencedMap;
                                public class PluginB implements BuildExecutorModule {
                                    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {}
                                }
                                """));

        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))));

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple");
    }

    @Test
    public void honors_pinned_version_and_checksum() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildStepResult;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        import java.util.concurrent.CompletableFuture;
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                                executor.addStep("marker", (_, context, _) -> {
                                    Files.writeString(context.next().resolve("out.txt"), "hello");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                            }
                        }
                        """));

        buildExecutor.addStep("manifests", (_, context, _) -> {
            Files.writeString(context.next().resolve("versions.properties"),
                    "main/module/build.jenesis=1.0.0 SHA-256/" + sha256(jenesisJar) + "\n");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        });
        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))), "manifests");

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps.get("internal/marker").resolve("out.txt")).content().isEqualTo("hello");
    }

    @Test
    public void fails_when_pinned_checksum_mismatches() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildStepResult;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        import java.util.concurrent.CompletableFuture;
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                                executor.addStep("marker", (_, context, _) -> {
                                    Files.writeString(context.next().resolve("out.txt"), "hello");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                            }
                        }
                        """));

        buildExecutor.addStep("manifests", (_, context, _) -> {
            Files.writeString(context.next().resolve("versions.properties"),
                    "main/module/build.jenesis=1.0.0 SHA-256/" + "00".repeat(32) + "\n");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        });
        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))), "manifests");

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mismatched digest");
    }

    @Test
    public void fails_when_group_pinned_checksum_mismatches_constrains_runtime_scope() throws IOException {
        Path source = writeModuleSource(work.resolve("plugin"),
                "module test.plugin { requires build.jenesis; provides build.jenesis.BuildExecutorModule with test.plugin.Plugin; }",
                Map.of("test/plugin/Plugin.java", """
                        package test.plugin;
                        import build.jenesis.BuildExecutor;
                        import build.jenesis.BuildExecutorModule;
                        import build.jenesis.BuildStepResult;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.util.SequencedMap;
                        import java.util.concurrent.CompletableFuture;
                        public class Plugin implements BuildExecutorModule {
                            public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                                executor.addStep("marker", (_, context, _) -> {
                                    Files.writeString(context.next().resolve("out.txt"), "hello");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                            }
                        }
                        """));

        buildExecutor.addStep("manifests", (_, context, _) -> {
            Files.writeString(context.next().resolve("versions.properties"),
                    "main/module/build.jenesis=1.0.0 SHA-256/" + "00".repeat(32) + "\n");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        });
        buildExecutor.addModule("internal", new InternalModule(
                "module",
                null,
                source)
                .repositories(Map.of("module", versionInsensitive(Map.of("build.jenesis", jenesisJar))))
                .resolvers(Map.of("module", new ModularJarResolver(true))), "manifests");

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Mismatched digest");
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeModuleSource(Path target, String moduleInfo, Map<String, String> sources) throws IOException {
        Files.createDirectories(target);
        Files.writeString(target.resolve("module-info.java"), moduleInfo);
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = target.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        return target;
    }

    private static Repository versionInsensitive(Map<String, Path> files) {
        return (_, coordinate) -> {
            int slash = coordinate.indexOf('/');
            String name = slash < 0 ? coordinate : coordinate.substring(0, slash);
            Path file = files.get(name);
            return file == null ? Optional.empty() : Optional.of(RepositoryItem.ofFile(file));
        };
    }
}
