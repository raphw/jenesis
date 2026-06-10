package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.project.InferredTestObservationModule;
import build.jenesis.project.ObservabilityEngine;
import build.jenesis.project.Observation;

import static org.assertj.core.api.Assertions.assertThat;

public class InferredTestObservationModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void wires_jacoco_and_passes_its_engine_when_selected() throws IOException {
        List<ObservabilityEngine> observed = new ArrayList<>();
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("observed", new InferredTestObservationModule(Observation.JACOCO, Map.of(), Map.of(), null, engines -> {
            observed.addAll(engines);
            return (BuildExecutorModule) (module, inherited) -> {};
        }), "project");
        executor.execute("observed/jacoco/required");

        assertThat(observed).extracting(ObservabilityEngine::name).containsExactly("jacoco");
        Path requiredOutput = root.resolve("observed").resolve("jacoco").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("jacoco/runtime/maven/org.jacoco/org.jacoco.cli/RELEASE");
    }

    @Test
    public void wires_only_the_test_module_when_no_engine_is_selected() throws IOException {
        List<ObservabilityEngine> observed = new ArrayList<>();
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("observed", new InferredTestObservationModule(null, Map.of(), Map.of(), null, engines -> {
            observed.addAll(engines);
            return (BuildExecutorModule) (module, inherited) -> {};
        }), "project");
        executor.execute();

        assertThat(observed).isEmpty();
        assertThat(root.resolve("observed").resolve("jacoco"))
                .as("no observation engine is wired unless it is selected")
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
