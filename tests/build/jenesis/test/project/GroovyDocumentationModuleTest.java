package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.project.GroovyDocumentationModule;
import build.jenesis.step.ProcessHandler;

import static org.assertj.core.api.Assertions.assertThat;

public class GroovyDocumentationModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void honors_release_from_upstream_javac_properties() throws IOException {
        Path sample = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sample.resolve("Sample.groovy"), "package sample\nclass Sample {}");
        Path process = Files.createDirectories(project.resolve("process"));
        SequencedProperties javac = new SequencedProperties();
        javac.setProperty("--release", "11");
        javac.store(process.resolve("javac.properties"));
        Path groovydocJar = Files.createFile(project.resolve("groovydoc.jar"));

        List<String> captured = new ArrayList<>();
        ToolProvider noop = new ToolProvider() {
            @Override
            public String name() {
                return "groovydoc";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... args) {
                return 0;
            }
        };

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "groovydoc",
                new GroovyDocumentationModule(
                        Map.of("maven", (_, _) -> Optional.of(RepositoryItem.ofFile(groovydocJar))),
                        Map.of("maven", Resolver.identity()))
                        .factory(commands -> {
                            captured.addAll(commands);
                            return ProcessHandler.OfTool.of(noop).apply(commands);
                        }),
                "project");
        executor.execute();

        assertThat(captured)
                .as("--release=11 from process/javac.properties drives -javaVersion JAVA_11")
                .containsSequence("-javaVersion", "JAVA_11");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }
}
