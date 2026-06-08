package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.ScalaDocumentationModule;
import build.jenesis.step.ProcessHandler;

import static org.assertj.core.api.Assertions.assertThat;

public class ScalaDocumentationModuleTest {

    @TempDir
    private Path input, root;

    @Test
    public void classpath_carries_compile_dependencies_while_positional_inputs_are_class_roots() throws IOException {
        Path toolJar = Files.createFile(input.resolve("scaladoc.jar"));
        Path compileJar = Files.createFile(input.resolve("compile.jar"));
        Path classes = Files.createDirectory(input.resolve(BuildStep.CLASSES));
        Files.createFile(classes.resolve("Sample.tasty"));
        Files.writeString(Files
                .createDirectories(input.resolve(BuildStep.SOURCES))
                .resolve("Sample.scala"), "class Sample\n");
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("scaladoc/scaladoc/local/scaladoc", "scaladoc.jar");
        dependencies.setProperty("main/compile/local/compile", "compile.jar");
        dependencies.store(input.resolve(BuildStep.DEPENDENCIES));

        List<String>[] captured = new List[1];
        BuildExecutor executor = BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addSource("input", input);
        executor.addModule("scaladoc",
                new ScalaDocumentationModule(
                        Map.of(),
                        Map.of("module", (_, _, _, _, _, _, _) -> new LinkedHashMap<>()))
                        .factory(commands -> {
                            captured[0] = commands;
                            return ProcessHandler.OfTool.of(new ToolProvider() {
                                @Override
                                public String name() {
                                    return "scaladoc";
                                }

                                @Override
                                public int run(PrintWriter out, PrintWriter err, String... args) {
                                    return 0;
                                }
                            }).apply(commands);
                        }),
                "input");
        executor.execute();

        assertThat(captured[0]).isNotNull();
        int classpathIndex = captured[0].indexOf("-classpath");
        assertThat(classpathIndex).isGreaterThanOrEqualTo(0);
        List<String> classpath = Arrays.asList(captured[0].get(classpathIndex + 1).split(File.pathSeparator));
        assertThat(classpath)
                .contains(classes.toString(), compileJar.toString())
                .doesNotContain(toolJar.toString());

        int cpIndex = captured[0].indexOf("-cp");
        assertThat(cpIndex).isGreaterThanOrEqualTo(0);
        assertThat(captured[0].get(cpIndex + 1)).isEqualTo(toolJar.toString());

        int projectIndex = captured[0].indexOf("-project");
        List<String> positional = captured[0].subList(projectIndex + 2, captured[0].size());
        assertThat(positional).containsExactly(classes.toString());
    }
}
