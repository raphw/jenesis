package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.project.PiTestModule;

import static org.assertj.core.api.Assertions.assertThat;

public class PiTestModuleTest {

    @Test
    public void discovers_pitest_only_when_a_config_file_is_present(@TempDir Path directory) throws IOException {
        assertThat(PiTestModule.configurationFile(directory)).isNull();
        Path config = Files.writeString(directory.resolve("pitest.properties"), "targetClasses=calc.*\n");
        assertThat(PiTestModule.configurationFile(directory)).isEqualTo(config);
    }
}
