package build.jenesis.test.project;

import module java.base;
import module java.compiler;
import module org.junit.jupiter.api;
import javax.tools.ToolProvider;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Repository;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.project.ExternalModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ExternalModuleTest {

    @TempDir
    private static Path shared;

    private static Path jenesisJar;

    @TempDir
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
                BuildExecutorCallback.nop());
    }

    @AfterEach
    public void tearDown() {
        buildExecutor = null;
        System.gc();
    }

    @Test
    public void can_load_and_run_modular_plugin() throws IOException {
        Path pluginJar = compileModule(work.resolve("plugin"),
                work.resolve("plugin.jar"),
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
                                executor.addStep("marker", (e, context, args) -> {
                                    Files.writeString(context.next().resolve("out.txt"), "hello");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                            }
                        }
                        """));

        buildExecutor.addModule("external", new ExternalModule(
                "module/test.plugin",
                Map.of("module", Repository.ofFiles(Map.of(
                        "test.plugin", pluginJar,
                        "build.jenesis", jenesisJar))),
                Map.of("module", new ModularJarResolver(true))));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKey("external/marker");
        assertThat(steps.get("external/marker").resolve("out.txt")).content().isEqualTo("hello");
    }

    @Test
    public void plugin_step_can_read_predecessor_argument_folder() throws IOException {
        Path pluginJar = compileModule(work.resolve("plugin"),
                work.resolve("plugin.jar"),
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
                                executor.addStep("first", (e, context, args) -> {
                                    Files.writeString(context.next().resolve("payload.txt"), "produced");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                                executor.addStep("second", (e, context, args) -> {
                                    Path predecessor = args.get("first").folder();
                                    String content = Files.readString(predecessor.resolve("payload.txt"));
                                    Files.writeString(context.next().resolve("out.txt"), "seen:" + content);
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                }, "first");
                            }
                        }
                        """));

        buildExecutor.addModule("external", new ExternalModule(
                "module/test.plugin",
                Map.of("module", Repository.ofFiles(Map.of(
                        "test.plugin", pluginJar,
                        "build.jenesis", jenesisJar))),
                Map.of("module", new ModularJarResolver(true))));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps.get("external/second").resolve("out.txt")).content().isEqualTo("seen:produced");
    }

    @Test
    public void plugin_can_add_nested_module() throws IOException {
        Path pluginJar = compileModule(work.resolve("plugin"),
                work.resolve("plugin.jar"),
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
                                executor.addModule("inner", (sub, subInherited) -> {
                                    sub.addStep("marker", (e, context, args) -> {
                                        Files.writeString(context.next().resolve("out.txt"), "nested");
                                        return CompletableFuture.completedStage(new BuildStepResult(true));
                                    });
                                });
                            }
                        }
                        """));

        buildExecutor.addModule("external", new ExternalModule(
                "module/test.plugin",
                Map.of("module", Repository.ofFiles(Map.of(
                        "test.plugin", pluginJar,
                        "build.jenesis", jenesisJar))),
                Map.of("module", new ModularJarResolver(true))));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps.get("external/inner/marker").resolve("out.txt")).content().isEqualTo("nested");
    }

    @Test
    public void resolves_named_provider_when_load_build_module_set() throws IOException {
        Path pluginJar = compileModule(work.resolve("plugin"),
                work.resolve("plugin.jar"),
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
                                executor.addStep("marker", (e, context, args) -> {
                                    Files.writeString(context.next().resolve("out.txt"), "named");
                                    return CompletableFuture.completedStage(new BuildStepResult(true));
                                });
                            }
                        }
                        """));

        buildExecutor.addModule("external", new ExternalModule(
                "module/test.plugin",
                Map.of("module", Repository.ofFiles(Map.of(
                        "test.plugin", pluginJar,
                        "build.jenesis", jenesisJar))),
                Map.of("module", new ModularJarResolver(true)))
                .loadBuildModule("foo"));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps.get("external/marker").resolve("out.txt")).content().isEqualTo("named");
    }

    @Test
    public void fails_when_no_provider_with_requested_name() throws IOException {
        Path pluginJar = compileModule(work.resolve("plugin"),
                work.resolve("plugin.jar"),
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

        buildExecutor.addModule("external", new ExternalModule(
                "module/test.plugin",
                Map.of("module", Repository.ofFiles(Map.of(
                        "test.plugin", pluginJar,
                        "build.jenesis", jenesisJar))),
                Map.of("module", new ModularJarResolver(true)))
                .loadBuildModule("bar"));

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("named bar");
    }

    @Test
    public void fails_when_module_has_no_provider() throws IOException {
        Path pluginJar = compileModule(work.resolve("plugin"),
                work.resolve("plugin.jar"),
                "module test.plugin { requires build.jenesis; }",
                Map.of("test/plugin/Empty.java", """
                        package test.plugin;
                        public class Empty {}
                        """));

        buildExecutor.addModule("external", new ExternalModule(
                "module/test.plugin",
                Map.of("module", Repository.ofFiles(Map.of(
                        "test.plugin", pluginJar,
                        "build.jenesis", jenesisJar))),
                Map.of("module", new ModularJarResolver(true))));

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No unnamed");
    }

    @Test
    public void fails_when_multiple_unnamed_providers() throws IOException {
        Path pluginJar = compileModule(work.resolve("plugin"),
                work.resolve("plugin.jar"),
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

        buildExecutor.addModule("external", new ExternalModule(
                "module/test.plugin",
                Map.of("module", Repository.ofFiles(Map.of(
                        "test.plugin", pluginJar,
                        "build.jenesis", jenesisJar))),
                Map.of("module", new ModularJarResolver(true))));

        assertThatThrownBy(() -> buildExecutor.execute())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple");
    }

    private static Path compileModule(Path sourceDir,
                                      Path jarOut,
                                      String moduleInfo,
                                      Map<String, String> sources) throws IOException {
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("module-info.java"), moduleInfo);
        List<Path> files = new ArrayList<>();
        files.add(sourceDir.resolve("module-info.java"));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = sourceDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            files.add(file);
        }
        Path classes = Files.createDirectories(sourceDir.resolveSibling(sourceDir.getFileName() + "-classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            fileManager.setLocationFromPaths(StandardLocation.MODULE_PATH, List.of(jenesisJar));
            boolean success = compiler.getTask(null,
                    fileManager,
                    diagnostics,
                    null,
                    null,
                    fileManager.getJavaFileObjectsFromPaths(files)).call();
            if (!Boolean.TRUE.equals(success)) {
                throw new IllegalStateException("Compilation failed: " + diagnostics.getDiagnostics());
            }
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarOut))) {
            Files.walkFileTree(classes, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String entry = classes.relativize(file).toString().replace(File.separatorChar, '/');
                    jar.putNextEntry(new JarEntry(entry));
                    Files.copy(file, jar);
                    jar.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return jarOut;
    }
}
