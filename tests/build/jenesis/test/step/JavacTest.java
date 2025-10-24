package build.jenesis.test.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Javac;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;

import static org.assertj.core.api.Assertions.assertThat;

public class JavacTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, sources;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        sources = Files.createDirectory(root.resolve("sources"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_javac(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        BuildStepResult result = (process ? Javac.process() : Javac.tool()).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_javac_with_resources(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        Files.writeString(folder.resolve("foo"), "bar");
        Files.createDirectory(sources.resolve(BuildStep.SOURCES + "folder"));
        BuildStepResult result = (process ? Javac.process() : Javac.tool()).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "sample/foo")).content().isEqualTo("bar");
        assertThat(next.resolve(Javac.CLASSES + "folder")).isDirectory();
    }
}
