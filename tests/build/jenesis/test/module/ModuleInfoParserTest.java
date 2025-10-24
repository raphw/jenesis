package build.jenesis.test.module;

import build.jenesis.module.ModuleInfo;
import build.jenesis.module.ModuleInfoParser;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleInfoParserTest {

    @TempDir
    private Path folder;

    @Test
    public void can_identify_module_info() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                module foo {
                  requires bar;
                  opens qux;
                  exports baz;
                }
                """);
        assertThat(new ModuleInfoParser().identify(folder.resolve("module-info.java"))).isEqualTo(
                new ModuleInfo("foo", new LinkedHashSet<>(List.of("bar"))));
    }
}
