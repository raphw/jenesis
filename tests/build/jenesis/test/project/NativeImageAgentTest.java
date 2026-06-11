package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.project.NativeImageAgent;

import static org.assertj.core.api.Assertions.assertThat;

public class NativeImageAgentTest {

    @Test
    public void declares_no_coordinates_as_the_agent_ships_with_graalvm() {
        assertThat(new NativeImageAgent().coordinates()).isEmpty();
    }

    @Test
    public void emits_a_tracing_agent_writing_into_the_native_image_directory(@TempDir Path output) {
        assertThat(new NativeImageAgent().commands(new LinkedHashMap<>(), output))
                .containsExactly("-agentlib:native-image-agent=config-output-dir="
                        + output.resolve("native-image").toAbsolutePath());
    }

    @Test
    public void is_named_after_the_config_directory_it_produces() {
        assertThat(new NativeImageAgent().name()).isEqualTo("native-image");
    }
}
