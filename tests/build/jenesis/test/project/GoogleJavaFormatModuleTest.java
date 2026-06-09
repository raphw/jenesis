package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.GoogleJavaFormatModule;

import static org.assertj.core.api.Assertions.assertThat;

public class GoogleJavaFormatModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void requires_step_emits_the_google_java_format_maven_coordinate() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("google-java-format", new GoogleJavaFormatModule(Map.of(), Map.of()), "project");
        executor.execute("google-java-format/required");

        Path requiredOutput = root.resolve("google-java-format").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("google-java-format/runtime/maven/com.google.googlejavaformat/google-java-format/RELEASE");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }
}
