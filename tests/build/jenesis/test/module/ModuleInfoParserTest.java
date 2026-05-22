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
                 * @jenesis.pin bar 1.2.3
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions()).containsExactly(Map.entry("bar", "1.2.3"));
    }

    @Test
    public void requires_tag_carries_optional_checksum_after_version() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar 1.2.3 SHA256/cafebabe
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions()).containsExactly(Map.entry("bar", "1.2.3 SHA256/cafebabe"));
    }

    @Test
    public void multiple_requires_tags_preserve_order() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar 1.0
                 * @jenesis.pin qux 2.0
                 * @jenesis.pin baz 3.0
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
                 * @jenesis.pin transitive.pin 9.9.9
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
                 * @jenesis.pin bar
                 * @jenesis.pin
                 * @jenesis.pin qux 1.0
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
                 * @jenesis.pin java.base 21
                 * @jenesis.pin jdk.compiler 21
                 * @jenesis.pin bar 1.0
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
                 * @jenesis.pin bar 1.0
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
                 * @jenesis.release 25
                 * @jenesis.pin bar 1.0
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
                 * @jenesis.release
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
                 * @jenesis.pin bar 1.0
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
                 * @jenesis.pin bar 1.0
                 */
                open module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.coordinate()).isEqualTo("foo");
        assertThat(info.versions()).containsExactly(Map.entry("bar", "1.0"));
    }

    @Test
    public void extracts_first_sentence_as_name_and_remainder_as_description() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * Foo Library.
                 *
                 * A small library that does foo things.
                 *
                 * @jenesis.release 25
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.name()).isEqualTo("Foo Library");
        assertThat(info.description()).isEqualTo("A small library that does foo things.");
        assertThat(info.release()).isEqualTo("25");
    }

    @Test
    public void single_sentence_javadoc_extracts_only_name() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * Just a summary.
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.name()).isEqualTo("Just a summary");
        assertThat(info.description()).isNull();
    }

    @Test
    public void block_tag_only_javadoc_yields_null_name_and_description() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.release 25
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.name()).isNull();
        assertThat(info.description()).isNull();
    }

    @Test
    public void bare_tests_tag_marks_module_with_empty_main_name() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.test
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.testOf()).isEmpty();
    }

    @Test
    public void tests_tag_with_argument_marks_main_module() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.test build.jenesis
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.testOf()).isEqualTo("build.jenesis");
    }

    @Test
    public void absent_tests_tag_leaves_module_as_non_test() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.release 25
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.testOf()).isNull();
    }

    @Test
    public void main_tag_captures_main_class() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.main build.jenesis.Project
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.main()).isEqualTo("build.jenesis.Project");
    }

    @Test
    public void absent_main_tag_leaves_main_class_unset() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.release 25
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.main()).isNull();
    }
}
