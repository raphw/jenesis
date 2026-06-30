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
import build.jenesis.project.InferredTestObservationModule;
import build.jenesis.project.ObservabilityEngine;

import static org.assertj.core.api.Assertions.assertThat;

public class InferredTestObservationModuleTest {

    @TempDir
    private Path root, project;

    @Test
    public void wires_jacoco_when_a_jacoco_properties_file_is_present() throws IOException {
        Files.writeString(project.resolve("jacoco.properties"), "");
        List<ObservabilityEngine> observed = new ArrayList<>();
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("observed", new InferredTestObservationModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of(), null, engines -> {
            observed.addAll(engines);
            return (_, _) -> {};
        }), "project");
        executor.execute("observed/jacoco/required");

        assertThat(observed).extracting(ObservabilityEngine::name).containsExactly("jacoco");
        Path requiredOutput = root.resolve("observed").resolve("jacoco").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("jacoco/runtime/maven/org.jacoco/org.jacoco.cli/RELEASE");
    }

    @Test
    public void does_not_wire_an_engine_without_a_config_file() throws IOException {
        List<ObservabilityEngine> observed = new ArrayList<>();
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("observed", new InferredTestObservationModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of(), null, engines -> {
            observed.addAll(engines);
            return (_, _) -> {};
        }), "project");
        executor.execute();

        assertThat(observed).isEmpty();
        assertThat(root.resolve("observed").resolve("jacoco"))
                .as("no observation engine is wired without its config file")
                .doesNotExist();
    }

    @Test
    public void the_observe_override_switches_off_jacoco() throws IOException {
        Files.writeString(project.resolve("jacoco.properties"), "");
        List<ObservabilityEngine> observed = new ArrayList<>();
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("observed", new InferredTestObservationModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of(), null, engines -> {
            observed.addAll(engines);
            return (_, _) -> {};
        }).jacoco(false), "project");
        executor.execute();

        assertThat(observed).isEmpty();
        assertThat(root.resolve("observed").resolve("jacoco")).doesNotExist();
    }

    @Test
    public void wires_mutate_when_a_pitest_config_is_present() throws IOException {
        Files.writeString(project.resolve("pitest.properties"), "targetClasses=sample.*\ntargetTests=sample.*\n");
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("observed",
                new InferredTestObservationModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of(), null, _ -> (_, _) -> {}),
                "project");
        executor.execute("observed/mutate/required");

        Path requiredOutput = root.resolve("observed").resolve("mutate").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactlyInAnyOrder(
                        "pitest/runtime/maven/org.pitest/pitest-command-line/RELEASE",
                        "pitest/runtime/maven/org.pitest/pitest-junit5-plugin/RELEASE");
    }

    @Test
    public void does_not_wire_mutate_without_a_pitest_config() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("observed",
                new InferredTestObservationModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of(), null, _ -> (_, _) -> {}),
                "project");
        executor.execute();

        assertThat(root.resolve("observed").resolve("mutate"))
                .as("mutation testing is not wired without pitest.properties")
                .doesNotExist();
    }

    @Test
    public void the_mutate_override_switches_off_pitest() throws IOException {
        Files.writeString(project.resolve("pitest.properties"), "targetClasses=sample.*\n");
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule("observed",
                new InferredTestObservationModule(new LinkedHashSet<>(List.of(project)), Map.of(), Map.of(), null, _ -> (_, _) -> {}).pitest(false),
                "project");
        executor.execute();

        assertThat(root.resolve("observed").resolve("mutate")).doesNotExist();
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }
}
