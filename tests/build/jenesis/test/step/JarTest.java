package build.jenesis.test.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.Javadoc;
import sample.Sample;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

public class JarTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, classes;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        classes = Files.createDirectory(root.resolve("classes"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_classes_jar(boolean process) throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javac.CLASSES));
        try (InputStream inputStream = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(inputStream), Files
                    .createDirectory(folder.resolve("sample"))
                    .resolve("Sample.class"));
        }
        BuildStepResult result = (process ? Jar.process(Jar.Sort.CLASSES) : Jar.tool(Jar.Sort.CLASSES)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.class"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "classes.jar")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_sources_jar(boolean process) throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javac.SOURCES));
        Files.writeString(Files
                .createDirectory(folder.resolve("sample"))
                .resolve("Sample.java"), """
                package sample;
                public class Sample {
                    public static void main(String[] args) {
                        System.out.print("Hello world!");
                    }
                }
                """);
        BuildStepResult result = (process ? Jar.process(Jar.Sort.SOURCES) : Jar.tool(Jar.Sort.SOURCES)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "sources.jar")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_javadoc_jar(boolean process) throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javadoc.JAVADOC));
        Files.writeString(Files
                .createDirectory(folder.resolve("sample"))
                .resolve("Sample.html"), """
                <html>
                  <p>This is a javadoc.</p>
                </html>
                """);
        BuildStepResult result = (process ? Jar.process(Jar.Sort.JAVADOC) : Jar.tool(Jar.Sort.JAVADOC)).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.html"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "javadoc.jar")).isNotEmptyFile();
    }
}
