package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.CodeNarcModule;

import static org.assertj.core.api.Assertions.assertThat;

public class CodeNarcModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void requires_step_emits_codenarc_with_its_groovy_and_logging_runtime() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("codenarc", new CodeNarcModule(Map.of(), Map.of()), "project");
        executor.execute("codenarc/required");

        Path requiredOutput = root.resolve("codenarc").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly(
                        "codenarc/runtime/maven/org.codenarc/CodeNarc/RELEASE",
                        "codenarc/runtime/maven/org.apache.groovy/groovy/RELEASE",
                        "codenarc/runtime/maven/org.slf4j/slf4j-simple/RELEASE");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }
}
