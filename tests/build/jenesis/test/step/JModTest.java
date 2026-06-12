package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.ProcessHandler;
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
        BuildStepResult result = new JMod(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("classes", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("classes/module-info.class"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JMod.JMODS + "sample.jmod")).isNotEmptyFile();
    }

    @Test
    public void config_directory_is_packaged_and_reaches_a_jlinked_runtime() throws IOException {
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
        Files.writeString(Files.createDirectory(bundle.resolve(JMod.CONFIG)).resolve("app.properties"), "greeting=configured");

        BuildStepResult result = new JMod(ProcessHandler.Factory.TOOL).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("classes", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("classes/module-info.class"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("jmodconfig/app.properties"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path jmod = next.resolve(JMod.JMODS + "sample.jmod");
        assertThat(jmod).isNotEmptyFile();

        Path runtime = root.resolve("runtime");
        int linked = ToolProvider.findFirst("jlink").orElseThrow().run(System.out, System.err,
                "--module-path", jmod.toString(),
                "--add-modules", "sample",
                "--output", runtime.toString());
        assertThat(linked).isZero();
        assertThat(runtime.resolve("conf/app.properties")).hasContent("greeting=configured");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void skips_when_no_module_is_present(boolean process) throws IOException {
        Files.createDirectory(bundle.resolve(BuildStep.CLASSES));
        BuildStepResult result = new JMod(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("classes", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("classes/Sample.class"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(JMod.JMODS)).doesNotExist();
    }
}
