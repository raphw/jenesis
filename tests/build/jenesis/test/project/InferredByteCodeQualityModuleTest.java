package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.InferredByteCodeQualityModule;

import static org.assertj.core.api.Assertions.assertThat;

public class InferredByteCodeQualityModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void wires_spotbugs_when_its_filter_file_is_present() throws IOException {
        Files.writeString(project.resolve("spotbugs-exclude.xml"), "<FindBugsFilter/>");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("quality", new InferredByteCodeQualityModule(project, Map.of(), Map.of()), "project");
        executor.execute("quality/spotbugs/tool/required");

        Path requiredOutput = root.resolve("quality").resolve("spotbugs").resolve("tool").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("spotbugs/runtime/maven/com.github.spotbugs/spotbugs/RELEASE");
    }

    @Test
    public void skips_spotbugs_when_no_filter_file_is_present() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("quality", new InferredByteCodeQualityModule(project, Map.of(), Map.of()), "project");
        executor.execute();

        assertThat(root.resolve("quality").resolve("spotbugs"))
                .as("SpotBugs is not wired when neither spotbugs-exclude.xml nor spotbugs.xml is present")
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
