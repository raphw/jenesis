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
    public void license_step_is_omitted_without_a_license_policy() throws Exception {
        String allowed = System.getProperty("jenesis.license.allowed");
        String unknown = System.getProperty("jenesis.license.unknown");
        String override = System.getProperty("jenesis.license.override");
        System.clearProperty("jenesis.license.allowed");
        System.clearProperty("jenesis.license.unknown");
        System.clearProperty("jenesis.license.override");
        try {
            assertThatThrownBy(() -> execute("compliance/license"))
                    .rootCause()
                    .hasMessage("Unknown selector: license");
        } finally {
            restore("jenesis.license.allowed", allowed);
            restore("jenesis.license.unknown", unknown);
            restore("jenesis.license.override", override);
        }
    }

    @Test
    public void license_step_is_wired_when_a_license_policy_is_configured() throws Exception {
        String allowed = System.getProperty("jenesis.license.allowed");
        String unknown = System.getProperty("jenesis.license.unknown");
        String override = System.getProperty("jenesis.license.override");
        System.setProperty("jenesis.license.allowed", "Apache");
        System.clearProperty("jenesis.license.unknown");
        System.clearProperty("jenesis.license.override");
        try {
            assertThat(execute("compliance/license")).containsKey("compliance/license");
        } finally {
            restore("jenesis.license.allowed", allowed);
            restore("jenesis.license.unknown", unknown);
            restore("jenesis.license.override", override);
        }
    }

    private SequencedMap<String, Path> execute(String selector) throws IOException {
        Path build = Files.createDirectories(root.resolve("build"));
        Path input = Files.createDirectories(root.resolve("input"));
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
        executor.addSource("input", input);
        executor.addModule("compliance", new ComplianceModule(), "input");
        return executor.execute(Runnable::run, selector).toCompletableFuture().join();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
