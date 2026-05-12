package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.module.ModuleInfo;
import build.jenesis.module.ModuleInfoParser;

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
                new ModuleInfo("foo",
                        new LinkedHashSet<>(List.of("bar")),
                        new LinkedHashSet<>(List.of("bar"))));
    }

    @Test
    public void separates_static_requires_from_runtime() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                module foo {
                  requires bar;
                  requires static qux;
                  requires static transitive baz;
                }
                """);
        assertThat(new ModuleInfoParser().identify(folder.resolve("module-info.java"))).isEqualTo(
                new ModuleInfo("foo",
                        new LinkedHashSet<>(List.of("bar", "qux", "baz")),
                        new LinkedHashSet<>(List.of("bar"))));
    }

    @Test
    public void no_javadoc_yields_empty_versions() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                module foo {
                  requires bar;
                }
                """);
        assertThat(new ModuleInfoParser().identify(folder.resolve("module-info.java")).versions()).isEmpty();
    }

    @Test
    public void single_requires_tag_is_extracted() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @requires bar 1.2.3
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions()).containsExactly(Map.entry("bar", "1.2.3"));
    }

    @Test
    public void multiple_requires_tags_preserve_order() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @requires bar 1.0
                 * @requires qux 2.0
                 * @requires baz 3.0
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions()).containsExactly(
                Map.entry("bar", "1.0"),
                Map.entry("qux", "2.0"),
                Map.entry("baz", "3.0"));
    }

    @Test
    public void pin_for_non_declared_module_is_extracted() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @requires transitive.pin 9.9.9
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.requires()).containsExactly("bar");
        assertThat(info.versions()).containsExactly(Map.entry("transitive.pin", "9.9.9"));
    }

    @Test
    public void malformed_requires_tag_is_skipped() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @requires bar
                 * @requires
                 * @requires qux 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions()).containsExactly(Map.entry("qux", "1.0"));
    }

    @Test
    public void java_and_jdk_pins_are_ignored() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @requires java.base 21
                 * @requires jdk.compiler 21
                 * @requires bar 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions()).containsExactly(Map.entry("bar", "1.0"));
    }

    @Test
    public void other_javadoc_tags_are_ignored() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * General module description.
                 *
                 * @author someone
                 * @since 1.0
                 * @requires bar 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions()).containsExactly(Map.entry("bar", "1.0"));
    }

    @Test
    public void release_tag_is_extracted() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @release 25
                 * @requires bar 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.release()).isEqualTo("25");
        assertThat(info.versions()).containsExactly(Map.entry("bar", "1.0"));
    }

    @Test
    public void empty_release_tag_is_ignored() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @release
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.release()).isNull();
    }

    @Test
    public void no_release_tag_yields_null_release() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @requires bar 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.release()).isNull();
    }

    @Test
    public void open_module_with_javadoc_pins() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @requires bar 1.0
                 */
                open module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.coordinate()).isEqualTo("foo");
        assertThat(info.versions()).containsExactly(Map.entry("bar", "1.0"));
    }
}
