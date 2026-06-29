package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.project.ComplianceModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ComplianceModuleTest {

    @TempDir
    private Path root;

    @Test
    public void license_step_is_omitted_without_a_licensing_properties_file() throws Exception {
        Path configuration = Files.createDirectories(root.resolve("configuration"));
        assertThatThrownBy(() -> execute(configuration, "compliance/license"))
                .rootCause()
                .hasMessage("Unknown selector: license");
    }

    @Test
    public void license_step_is_wired_when_a_licensing_properties_file_exists() throws Exception {
        Path configuration = Files.createDirectories(root.resolve("configuration"));
        Files.writeString(configuration.resolve("licensing.properties"), "allowed=Apache\n");
        assertThat(execute(configuration, "compliance/license")).containsKey("compliance/license");
    }

    @Test
    public void vulnerability_step_is_wired_when_a_vulnerability_properties_file_exists() throws Exception {
        Path configuration = Files.createDirectories(root.resolve("configuration"));
        Files.writeString(configuration.resolve("vulnerability.properties"), "severity=high\n");
        assertThat(execute(configuration, "compliance/vulnerability")).containsKey("compliance/vulnerability");
    }

    private SequencedMap<String, Path> execute(Path configuration, String selector) throws IOException {
        Path build = Files.createDirectories(root.resolve("build"));
        Path input = Files.createDirectories(root.resolve("input"));
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addSource("input", input);
        executor.addModule("compliance", new ComplianceModule(configuration), "input");
        return executor.execute(Runnable::run, selector).toCompletableFuture().join();
    }
}
