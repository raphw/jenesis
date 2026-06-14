package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.InferredSourceCodeQualityModule;

import static org.assertj.core.api.Assertions.assertThat;

public class InferredSourceCodeQualityModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void wires_checkstyle_when_its_config_file_is_present() throws IOException {
        Files.writeString(project.resolve("checkstyle.xml"), "<module name=\"Checker\"/>");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("quality", new InferredSourceCodeQualityModule(project, Map.of(), Map.of()), "project");
        executor.execute("quality/checkstyle/tool/required");

        Path requiredOutput = root.resolve("quality").resolve("checkstyle").resolve("tool").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("checkstyle/runtime/maven/com.puppycrawl.tools/checkstyle/RELEASE");
    }

    @Test
    public void skips_checkstyle_when_its_config_file_is_absent() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("quality", new InferredSourceCodeQualityModule(project, Map.of(), Map.of()), "project");
        executor.execute();

        assertThat(root.resolve("quality").resolve("checkstyle"))
                .as("Checkstyle is not wired when checkstyle.xml is absent from the project")
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
