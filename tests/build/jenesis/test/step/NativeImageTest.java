package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.PathPlacement;
import build.jenesis.SequencedProperties;
import build.jenesis.step.NativeImage;
import build.jenesis.step.ProcessHandler;

import static org.assertj.core.api.Assertions.assertThat;

public class NativeImageTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, bundle, shim;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        bundle = Files.createDirectory(root.resolve("bundle"));
        Files.writeString(Files.createDirectory(bundle.resolve(BuildStep.ARTIFACTS)).resolve("app.jar"), "jar");
        shim = root.resolve("native-image-shim");
        Files.writeString(shim, "#!/bin/sh\nexit 0\n");
        shim.toFile().setExecutable(true);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void emits_a_modular_native_image_command() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("--name", "sample-app");
        launcher.setProperty("--module", "sample/sample.Sample");
        store(launcher);

        BuildStepResult result = run(PathPlacement.MODULE_PATH);
        assertThat(result.next()).isTrue();
        assertThat(command())
                .contains("--no-fallback")
                .contains("--module-path")
                .contains("-o " + next.resolve(NativeImage.NATIVE).resolve("sample-app"))
                .endsWith("--module sample/sample.Sample");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void emits_a_classpath_native_image_command() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("--main-jar", "app.jar");
        launcher.setProperty("--main-class", "sample.Sample");
        store(launcher);

        BuildStepResult result = run(PathPlacement.CLASS_PATH);
        assertThat(result.next()).isTrue();
        assertThat(command())
                .contains("--no-fallback")
                .contains("-cp")
                .doesNotContain("--module-path")
                .endsWith("sample.Sample");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void passes_a_reachability_config_directory() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("--module", "sample/sample.Sample");
        store(launcher);
        Path config = Files.createDirectory(bundle.resolve("native-image"));

        BuildStepResult result = run(PathPlacement.MODULE_PATH);
        assertThat(result.next()).isTrue();
        assertThat(command()).contains("-H:ConfigurationFileDirectories=" + config);
    }

    @Test
    public void skips_when_no_launcher_is_configured() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("--name", "sample-app");
        store(launcher);

        BuildStepResult result = run(PathPlacement.MODULE_PATH);
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(NativeImage.NATIVE)).doesNotExist();
        assertThat(supplement.resolve("command")).doesNotExist();
    }

    private void store(SequencedProperties launcher) throws IOException {
        launcher.store(Files.createDirectory(bundle.resolve("process")).resolve("jpackage.properties"));
    }

    private BuildStepResult run(PathPlacement modulePath) throws IOException {
        return new NativeImage(modulePath, ProcessHandler.OfProcess.of(List.of(shim.toString()))).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/app.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("process/jpackage.properties"), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture().join();
    }

    private String command() throws IOException {
        return Files.readString(supplement.resolve("command"));
    }
}
