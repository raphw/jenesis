package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Dependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(Dependencies.select(folder, "main", "compile"))
                .containsExactly(folder.resolve("libs/a.jar"), folder.resolve("libs/b.jar"));
        assertThat(Dependencies.select(folder, "main", "runtime"))
                .containsExactly(folder.resolve("libs/c.jar"));
    }

    @Test
    public void returns_empty_when_index_is_absent() throws IOException {
        assertThat(Dependencies.select(folder, "main", "compile")).isEmpty();
    }

    @Test
    public void skips_entries_whose_jar_is_missing() throws IOException {
        jar("libs/present.jar");
        SequencedProperties index = new SequencedProperties();
        index.setProperty("main/compile/maven/present", "libs/present.jar");
        index.setProperty("main/compile/maven/absent", "libs/absent.jar");
        index.store(folder.resolve(BuildStep.DEPENDENCIES));
        assertThat(Dependencies.select(folder, "main", "compile"))
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

    @Test
    public void bom_entries_expand_by_slash_count() {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("acme.core", "2.1.0 SHA-256/aaa");
        properties.setProperty("org.slf4j/slf4j-api", "2.0.17");
        properties.setProperty("maven/com.acme/acme-native/jar", "1.0.0 SHA-256/bbb");
        properties.setProperty("org.example/native-lib", ":linux-x86_64:1.2.3");
        assertThat(Dependencies.bomEntries(properties, "main")).containsExactly(
                Map.entry("main/module/acme.core", "2.1.0 SHA-256/aaa"),
                Map.entry("main/maven/org.slf4j/slf4j-api", "2.0.17"),
                Map.entry("main/maven/com.acme/acme-native/jar", "1.0.0 SHA-256/bbb"),
                Map.entry("main/maven/org.example/native-lib", ":linux-x86_64:1.2.3"));
    }

    @Test
    public void bom_entries_prefix_the_declared_group() {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("acme.core", "2.1.0");
        assertThat(Dependencies.bomEntries(properties, "kotlinc"))
                .containsExactly(Map.entry("kotlinc/module/acme.core", "2.1.0"));
    }

    @Test
    public void bom_entries_reject_platform_guards() {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("acme.core", "2.1.0 [linux]");
        assertThatThrownBy(() -> Dependencies.bomEntries(properties, "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform guards are not supported in BOM files");
    }

    @Test
    public void bom_entries_reject_malformed_keys() {
        SequencedProperties trailing = new SequencedProperties();
        trailing.setProperty("org.slf4j/", "2.0.17");
        assertThatThrownBy(() -> Dependencies.bomEntries(trailing, "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org.slf4j/");
        SequencedProperties empty = new SequencedProperties();
        empty.setProperty("maven//coordinate", "1.0");
        assertThatThrownBy(() -> Dependencies.bomEntries(empty, "main"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maven//coordinate");
    }
}
