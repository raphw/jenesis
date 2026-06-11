package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.project.NativeImageAgentModule;

import static org.assertj.core.api.Assertions.assertThat;

public class NativeImageAgentModuleTest {

    @TempDir
    private Path root, test;

    @Test
    public void stages_the_captured_configuration_as_a_report() throws IOException {
        Path captured = Files.createDirectories(test.resolve("native-image"));
        Files.writeString(captured.resolve("reflect-config.json"), "[{\"name\":\"sample.Sample\"}]");

        BuildExecutor executor = newExecutor();
        executor.addSource("test", test);
        executor.addModule("native-image", new NativeImageAgentModule(), "test");
        executor.execute();

        Path report = root.resolve("native-image").resolve("report").resolve("output")
                .resolve("reports").resolve("native-image").resolve("reflect-config.json");
        assertThat(report).content().isEqualTo("[{\"name\":\"sample.Sample\"}]");
    }

    @Test
    public void produces_no_report_when_nothing_was_captured() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("test", test);
        executor.addModule("native-image", new NativeImageAgentModule(), "test");
        executor.execute();

        assertThat(root.resolve("native-image").resolve("report").resolve("output").resolve("reports"))
                .as("an empty capture stages no report directory")
                .doesNotExist();
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }
}
