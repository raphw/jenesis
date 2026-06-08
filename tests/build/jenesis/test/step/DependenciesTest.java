package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Dependencies;

import static org.assertj.core.api.Assertions.assertThat;

public class DependenciesTest {

    @TempDir
    private Path folder;

    private Path jar(String relative) throws IOException {
        Path jar = folder.resolve(relative);
        Files.createDirectories(jar.getParent());
        return Files.writeString(jar, relative);
    }

    @Test
    public void selects_jars_for_the_requested_scope_in_declaration_order() throws IOException {
        jar("libs/a.jar");
        jar("libs/b.jar");
        jar("libs/c.jar");
        SequencedProperties index = new SequencedProperties();
        index.setProperty("main/compile/maven/a", "libs/a.jar");
        index.setProperty("main/runtime/maven/c", "libs/c.jar");
        index.setProperty("main/compile/maven/b", "libs/b.jar");
        index.store(folder.resolve(BuildStep.DEPENDENCIES));
        assertThat(Dependencies.select(folder, "compile"))
                .containsExactly(folder.resolve("libs/a.jar"), folder.resolve("libs/b.jar"));
        assertThat(Dependencies.select(folder, "runtime"))
                .containsExactly(folder.resolve("libs/c.jar"));
    }

    @Test
    public void returns_empty_when_index_is_absent() throws IOException {
        assertThat(Dependencies.select(folder, "compile")).isEmpty();
    }

    @Test
    public void skips_entries_whose_jar_is_missing() throws IOException {
        jar("libs/present.jar");
        SequencedProperties index = new SequencedProperties();
        index.setProperty("main/compile/maven/present", "libs/present.jar");
        index.setProperty("main/compile/maven/absent", "libs/absent.jar");
        index.store(folder.resolve(BuildStep.DEPENDENCIES));
        assertThat(Dependencies.select(folder, "compile"))
                .containsExactly(folder.resolve("libs/present.jar"));
    }

    @Test
    public void matches_a_qualified_scope_exactly() throws IOException {
        jar("libs/processor.jar");
        jar("libs/kotlin-plugin.jar");
        SequencedProperties index = new SequencedProperties();
        index.setProperty("plugin/plugin/maven/processor", "libs/processor.jar");
        index.setProperty("kotlinc/plugin/maven/kotlin-plugin", "libs/kotlin-plugin.jar");
        index.store(folder.resolve(BuildStep.DEPENDENCIES));
        assertThat(Dependencies.select(folder, "plugin", "plugin"))
                .containsExactly(folder.resolve("libs/processor.jar"));
        assertThat(Dependencies.select(folder, "kotlinc", "plugin"))
                .containsExactly(folder.resolve("libs/kotlin-plugin.jar"));
    }
}
