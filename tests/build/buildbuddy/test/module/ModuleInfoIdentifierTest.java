package build.buildbuddy.test.module;

import build.buildbuddy.Identification;
import build.buildbuddy.module.ModuleInfoIdentifier;
import build.buildbuddy.step.Bind;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleInfoIdentifierTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path root;

    @Before
    public void setUp() throws Exception {
        root = temporaryFolder.newFolder("root").toPath();
    }

    @Test
    public void can_identify_module_info() throws IOException {
        Files.writeString(Files.createDirectory(root.resolve(Bind.SOURCES)).resolve("module-info.java"), """
                module foo {
                  requires bar;
                  opens qux;
                  exports baz;
                }
                """);
        assertThat(new ModuleInfoIdentifier().identify(root)).contains(
                new Identification("foo", new LinkedHashMap<>(Map.of("bar", ""))));
    }
}
