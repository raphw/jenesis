package build.buildbuddy.test.module;

import build.buildbuddy.module.ModuleInfo;
import build.buildbuddy.module.ModuleInfoParser;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleInfoParserTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path folder;

    @Before
    public void setUp() throws Exception {
        folder = temporaryFolder.newFolder("folder").toPath();
    }

    @Test
    public void can_identify_module_info() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                module foo {
                  requires bar;
                  opens qux;
                  exports baz;
                }
                """);
        assertThat(new ModuleInfoParser().identify(folder)).isEqualTo(
                new ModuleInfo("foo", new LinkedHashSet<>(List.of("bar"))));
    }
}
