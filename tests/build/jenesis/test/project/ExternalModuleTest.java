package build.jenesis.test.project;

import module java.base;
import module java.compiler;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.HashDigestFunction;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.project.ExternalModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ExternalModuleTest {

    private static final String JENESIS_MODULE = "Jenesis-Module";

    @TempDir
    private Path root, jars;

    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
    }

    @Test
    public void can_load_external_module_from_jar() throws IOException {
        Path jar = buildModuleJar(jars, "gen.NoArgs", """
                package gen;
                import build.jenesis.BuildExecutor;
                import build.jenesis.BuildExecutorModule;
                import build.jenesis.BuildStepResult;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.SequencedMap;
                import java.util.concurrent.CompletableFuture;
                public class NoArgs implements BuildExecutorModule {
                    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                        executor.addStep("marker", (e, context, args) -> {
                            Files.writeString(context.next().resolve("out.txt"), "hello");
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        });
                    }
                }
                """);

        buildExecutor.addModule("external", new ExternalModule(
                "foo/bar",
                Map.of("foo", (executor, coordinate) -> Optional.of(RepositoryItem.ofFile(jar))),
                Map.of("foo", Resolver.identity())));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKey("external/marker");
        assertThat(steps.get("external/marker").resolve("out.txt")).content().isEqualTo("hello");
    }

    @Test
    public void can_pass_constructor_arguments_to_external_module() throws IOException {
        Path jar = buildModuleJar(jars, "gen.WithArgs", """
                package gen;
                import build.jenesis.BuildExecutor;
                import build.jenesis.BuildExecutorModule;
                import build.jenesis.BuildStepResult;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.SequencedMap;
                import java.util.concurrent.CompletableFuture;
                public class WithArgs implements BuildExecutorModule {
                    private final String left;
                    private final String right;
                    public WithArgs(String left, String right) {
                        this.left = left;
                        this.right = right;
                    }
                    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) {
                        String captured = left + ":" + right;
                        executor.addStep("marker", (e, context, args) -> {
                            Files.writeString(context.next().resolve("out.txt"), captured);
                            return CompletableFuture.completedStage(new BuildStepResult(true));
                        });
                    }
                }
                """);

        buildExecutor.addModule("external", new ExternalModule(
                "foo/bar",
                Map.of("foo", (executor, coordinate) -> Optional.of(RepositoryItem.ofFile(jar))),
                Map.of("foo", Resolver.identity()),
                "hello",
                "world"));

        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps.get("external/marker").resolve("out.txt")).content().isEqualTo("hello:world");
    }

    @Test
    public void fails_when_manifest_lacks_jenesis_module_entry() throws IOException {
        Path jar = jars.resolve("no-attribute.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // intentionally empty
        }

        buildExecutor.addModule("external", new ExternalModule(
                "foo/bar",
                Map.of("foo", (executor, coordinate) -> Optional.of(RepositoryItem.ofFile(jar))),
                Map.of("foo", Resolver.identity())));

        assertThatThrownBy(() -> buildExecutor.execute())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("manifest entry");
    }

    @Test
    public void fails_when_loaded_class_is_not_a_build_executor_module() throws IOException {
        Path jar = buildModuleJar(jars, "gen.NotAModule", """
                package gen;
                public class NotAModule {
                    public NotAModule() {}
                }
                """);

        buildExecutor.addModule("external", new ExternalModule(
                "foo/bar",
                Map.of("foo", (executor, coordinate) -> Optional.of(RepositoryItem.ofFile(jar))),
                Map.of("foo", Resolver.identity())));

        assertThatThrownBy(() -> buildExecutor.execute())
                .hasRootCauseInstanceOf(ClassCastException.class);
    }

    private static Path buildModuleJar(Path folder, String fqcn, String source) throws IOException {
        int dot = fqcn.lastIndexOf('.');
        String pkg = dot == -1 ? "" : fqcn.substring(0, dot);
        String simpleName = fqcn.substring(dot + 1);
        Path src = pkg.isEmpty()
                ? Files.createDirectory(folder.resolve("src-" + simpleName))
                : Files.createDirectories(folder.resolve("src-" + simpleName).resolve(pkg.replace('.', '/')));
        Path classes = Files.createDirectory(folder.resolve("classes-" + simpleName));
        Path javaFile = src.resolve(simpleName + ".java");
        Files.writeString(javaFile, source);

        JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            boolean success = compiler.getTask(null,
                    fileManager,
                    diagnostics,
                    null,
                    null,
                    fileManager.getJavaFileObjects(javaFile)).call();
            if (!Boolean.TRUE.equals(success)) {
                throw new IllegalStateException("Compilation failed: " + diagnostics.getDiagnostics());
            }
        }

        Path jar = folder.resolve(simpleName + ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue(JENESIS_MODULE, fqcn);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            Files.walkFileTree(classes, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".class")) {
                        String entryName = classes.relativize(file).toString().replace(File.separatorChar, '/');
                        out.putNextEntry(new JarEntry(entryName));
                        Files.copy(file, out);
                        out.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return jar;
    }
}
