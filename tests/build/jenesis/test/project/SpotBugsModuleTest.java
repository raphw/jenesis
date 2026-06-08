package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.SpotBugsModule;

import static org.assertj.core.api.Assertions.assertThat;

public class SpotBugsModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void requires_step_emits_the_spotbugs_maven_coordinate() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("spotbugs", new SpotBugsModule(Map.of(), Map.of()), "project");
        executor.execute("spotbugs/required");

        Path requiredOutput = root.resolve("spotbugs").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("spotbugs/runtime/maven/com.github.spotbugs/spotbugs/RELEASE");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }
}
