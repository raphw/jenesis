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
        launcher.setProperty("name", "sample-app");
        launcher.setProperty("mainModule", "sample");
        launcher.setProperty("mainClass", "sample.Sample");
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
        launcher.setProperty("mainClass", "sample.Sample");
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
    public void splits_module_and_class_path_and_roots_all_for_an_inferred_image() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("name", "sample-app");
        launcher.setProperty("mainModule", "sample");
        launcher.setProperty("mainClass", "sample.Sample");
        store(launcher);
        // A real modular jar lands on the module path; the plain app.jar lands on the class path. The
        // inferred placement is therefore not a self-contained module graph, so the whole module path is
        // rooted with --add-modules ALL-MODULE-PATH.
        modularJar(bundle.resolve(BuildStep.ARTIFACTS).resolve("sample.jar"), "sample");

        BuildStepResult result = run(PathPlacement.INFERRED);
        assertThat(result.next()).isTrue();
        assertThat(command())
                .contains("--module-path")
                .contains("-cp")
                .contains("--add-modules ALL-MODULE-PATH")
                .endsWith("--module sample/sample.Sample");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void resolves_runtime_dependencies_from_a_configured_group() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("mainClass", "sample.Sample");
        store(launcher);
        Path deps = Files.createDirectory(bundle.resolve("deps"));
        Files.writeString(deps.resolve("extra-lib.jar"), "jar");
        Files.writeString(deps.resolve("main-lib.jar"), "jar");
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("extra/runtime/maven/extra-lib", "deps/extra-lib.jar");
        dependencies.setProperty("main/runtime/maven/main-lib", "deps/main-lib.jar");
        dependencies.store(bundle.resolve(BuildStep.DEPENDENCIES));

        BuildStepResult result = new NativeImage(PathPlacement.CLASS_PATH, ProcessHandler.OfProcess.of(List.of(shim.toString())))
                .group("extra")
                .apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                                bundle,
                                Map.of(Path.of("artifacts/app.jar"), Checksum.of(ChecksumStatus.ADDED),
                                        Path.of("launcher.properties"), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(command())
                .contains("extra-lib.jar")
                .doesNotContain("main-lib.jar");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void reads_the_artifacts_folder_shallowly() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("mainClass", "sample.Sample");
        store(launcher);
        Path nested = Files.createDirectories(bundle.resolve(BuildStep.ARTIFACTS).resolve("sub"));
        Files.writeString(nested.resolve("nested.jar"), "jar");

        BuildStepResult result = run(PathPlacement.CLASS_PATH);
        assertThat(result.next()).isTrue();
        assertThat(command())
                .as("the artifacts folder is read as direct children, not walked into subfolders")
                .contains("app.jar")
                .doesNotContain("nested.jar");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void passes_a_reachability_config_directory() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("mainModule", "sample");
        launcher.setProperty("mainClass", "sample.Sample");
        store(launcher);
        Path config = Files.createDirectory(bundle.resolve("native-image"));

        BuildStepResult result = run(PathPlacement.MODULE_PATH);
        assertThat(result.next()).isTrue();
        assertThat(command()).contains("-H:ConfigurationFileDirectories=" + config);
    }

    @Test
    public void skips_when_no_launcher_is_configured() throws IOException {
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("name", "sample-app");
        store(launcher);

        BuildStepResult result = run(PathPlacement.MODULE_PATH);
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(NativeImage.NATIVE)).doesNotExist();
        assertThat(supplement.resolve("command")).doesNotExist();
    }

    private void store(SequencedProperties launcher) throws IOException {
        launcher.store(bundle.resolve("launcher.properties"));
    }

    private void modularJar(Path target, String moduleName) throws IOException {
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(moduleName),
                builder -> builder.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null)));
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            out.putNextEntry(new ZipEntry("module-info.class"));
            out.write(moduleInfo);
            out.closeEntry();
        }
    }

    private BuildStepResult run(PathPlacement modulePath) throws IOException {
        return new NativeImage(modulePath, ProcessHandler.OfProcess.of(List.of(shim.toString()))).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("artifacts", new BuildStepArgument(
                        bundle,
                        Map.of(Path.of("artifacts/app.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("launcher.properties"), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture().join();
    }

    private String command() throws IOException {
        return Files.readString(supplement.resolve("command"));
    }
}
