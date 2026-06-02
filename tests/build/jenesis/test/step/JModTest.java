package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.JMod;

import static org.assertj.core.api.Assertions.assertThat;

public class JModTest {

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
    public void can_execute_jmod(boolean process) throws IOException {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module sample { }\n");
        Files.writeString(Files.createDirectory(sources.resolve("sample")).resolve("Sample.java"),
                "package sample; public class Sample { }\n");
        Path classes = Files.createDirectory(bundle.resolve(BuildStep.CLASSES));
        int code = ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-d", classes.toString(),
                sources.resolve("module-info.java").toString(),
                sources.resolve("sample/Sample.java").toString());
        assertThat(code).isZero();
        BuildStepResult result = (process ? JMod.process() : JMod.tool()).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("classes", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("classes/module-info.class"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "sample.jmod")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void skips_when_no_module_is_present(boolean process) throws IOException {
        Files.createDirectory(bundle.resolve(BuildStep.CLASSES));
        BuildStepResult result = (process ? JMod.process() : JMod.tool()).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("classes", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("classes/Sample.class"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS)).doesNotExist();
    }
}
