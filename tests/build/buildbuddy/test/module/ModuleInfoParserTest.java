package build.buildbuddy.test.module;

import build.buildbuddy.ArtifactDescription;
import build.buildbuddy.module.ModuleInfoParser;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleInfoParserTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path folder;

    @Before
    public void setUp() throws Exception {
        folder = temporaryFolder.newFolder("sources").toPath();
    }

    @Test
    public void can_parse_module_info() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                module foo {
                  requires bar;
                  opens qux;
                }
                """);
        assertThat(new ModuleInfoParser().parse(folder)).contains(
                new ArtifactDescription("foo", List.of("bar")));
    }
}
