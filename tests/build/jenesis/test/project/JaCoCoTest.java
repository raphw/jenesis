package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.project.JaCoCo;

import static org.assertj.core.api.Assertions.assertThat;

public class JaCoCoTest {

    @Test
    public void coordinates_declare_the_runtime_agent() {
        SequencedMap<String, String> coordinates = new JaCoCo().coordinates();
        assertThat(coordinates).hasSize(1);
        assertThat(coordinates).containsEntry("maven/org.jacoco/org.jacoco.agent/jar/runtime", "RELEASE");
    }

    @Test
    public void emits_a_javaagent_for_the_resolved_agent(@TempDir Path output) {
        Path agent = output.resolve("jacocoagent.jar");
        SequencedMap<String, Path> resolved = new LinkedHashMap<>();
        resolved.put("maven/org.jacoco/org.jacoco.agent/jar/runtime", agent);

        assertThat(new JaCoCo().commands(resolved, output)).containsExactly("-javaagent:"
                + agent.toAbsolutePath()
                + "=destfile="
                + output.resolve("jacoco.exec").toAbsolutePath()
                + ",output=file,append=false");
    }

    @Test
    public void emits_no_command_when_the_agent_is_unresolved(@TempDir Path output) {
        assertThat(new JaCoCo().commands(new LinkedHashMap<>(), output)).isEmpty();
    }
}
