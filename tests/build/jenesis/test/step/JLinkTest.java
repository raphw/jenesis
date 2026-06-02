package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.JLink;

import static org.assertj.core.api.Assertions.assertThat;

public class JLinkTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, bundle;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        bundle = Files.createDirectory(root.resolve("bundle"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_jlink(boolean process) throws IOException {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module sample { }\n");
        Files.writeString(Files.createDirectory(sources.resolve("sample")).resolve("Sample.java"),
                "package sample; public class Sample { }\n");
        Path classes = Files.createDirectory(root.resolve("classes"));
        int compiled = ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-d", classes.toString(),
                sources.resolve("module-info.java").toString(),
                sources.resolve("sample/Sample.java").toString());
        assertThat(compiled).isZero();
        Path artifacts = Files.createDirectory(bundle.resolve(BuildStep.ARTIFACTS));
        int archived = ToolProvider.findFirst("jar").orElseThrow().run(System.out, System.err,
                "--create", "--file", artifacts.resolve("sample.jar").toString(),
                "-C", classes.toString(), ".");
        assertThat(archived).isZero();
        SequencedProperties configuration = new SequencedProperties();
        configuration.setProperty("--add-modules", "sample");
        configuration.store(Files.createDirectory(bundle.resolve("process")).resolve("jlink.properties"));
        BuildStepResult result = (process ? JLink.process() : JLink.tool()).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/sample.jar"), ChecksumStatus.ADDED,
                                Path.of("process/jlink.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JLink.RUNTIME + "release")).isRegularFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void skips_when_no_modules_are_present(boolean process) throws IOException {
        BuildStepResult result = (process ? JLink.process() : JLink.tool()).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("metadata.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JLink.RUNTIME)).doesNotExist();
    }
}
