package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.ScalafmtFormatModule;

import static org.assertj.core.api.Assertions.assertThat;

public class ScalafmtFormatModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void requires_step_emits_the_scalafmt_maven_coordinate() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("scalafmt-format", new ScalafmtFormatModule(Map.of(), Map.of()), "project");
        executor.execute("scalafmt-format/required");

        Path requiredOutput = root.resolve("scalafmt-format").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("scalafmt-format/runtime/maven/org.scalameta/scalafmt-cli_2.13/RELEASE");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }
}
