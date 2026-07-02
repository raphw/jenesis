package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.module.ModuleInfo;
import build.jenesis.module.ModuleInfoParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ModuleInfoParserTest {

    @TempDir
    private Path folder;

    @Test
    public void jenesis_pin_stores_group_first_tokens_verbatim() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.pin org.junit.jupiter 5.11.3
                 * @jenesis.pin kotlin/module/some.module 1.2.3 SHA256/ABCD
                 * @jenesis.pin plugin:scala/maven/org.scala-lang/scala3-library_3 3.5.2
                 */
                module foo {
                    requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions())
                .containsEntry("main/module/org.junit.jupiter", "5.11.3")
                .containsEntry("kotlin/module/some.module", "1.2.3 SHA256/ABCD")
                .containsEntry("plugin:scala/maven/org.scala-lang/scala3-library_3", "3.5.2");
    }

    @Test
    public void jenesis_pin_keeps_group_first_token_verbatim() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.pin main/maven/org.jetbrains/annotations 13.0 SHA256/cafebabe
                 */
                module foo {
                    requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions())
                .containsEntry("main/maven/org.jetbrains/annotations", "13.0 SHA256/cafebabe");
    }

    @Test
    public void jenesis_pin_expands_maven_coordinate_shortcut() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.pin org.slf4j/slf4j-api 2.0.16 SHA256/cafebabe
                 */
                module foo {
                    requires org.slf4j;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions())
                .containsEntry("main/maven/org.slf4j/slf4j-api", "2.0.16 SHA256/cafebabe");
    }

    @Test
    public void jenesis_pin_tolerates_surrounding_whitespace() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 *   @jenesis.pin   main/maven/org.jetbrains/annotations   13.0   SHA256/cafebabe  \s
                 */
                module foo {
                    requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions())
                .containsEntry("main/maven/org.jetbrains/annotations", "13.0 SHA256/cafebabe");
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
    public void jenesis_plugin_normalizes_unqualified_tokens() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.plugin maven/com.example/proc
                 * @jenesis.plugin bar
                 */
                module foo {
                    requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.plugins()).containsExactly(
                Map.entry("maven/com.example/proc", "plugin"),
                Map.entry("module/bar", "plugin"));
    }

    @Test
    public void jenesis_plugin_compiler_word_names_the_group() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.plugin kotlinc module/some.processor
                 * @jenesis.plugin scalac maven/com.example/plugin
                 */
                module foo {
                    requires bar;
                }
                """);
        assertThat(new ModuleInfoParser().identify(folder.resolve("module-info.java")).plugins()).containsExactly(
                Map.entry("module/some.processor", "kotlinc"),
                Map.entry("maven/com.example/plugin", "scalac"));
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
        assertThat(info.versions()).containsExactly(Map.entry("main/module/bar", "1.2.3"));
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
        assertThat(info.versions()).containsExactly(Map.entry("main/module/bar", "1.2.3 SHA256/cafebabe"));
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
                Map.entry("main/module/bar", "1.0"),
                Map.entry("main/module/qux", "2.0"),
                Map.entry("main/module/baz", "3.0"));
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
        assertThat(info.versions()).containsExactly(Map.entry("main/module/transitive.pin", "9.9.9"));
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
        assertThat(info.versions()).containsExactly(Map.entry("main/module/qux", "1.0"));
    }

    @Test
    public void bare_module_pin_is_the_shortcut_for_main_module() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.pin bar 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.versions()).containsExactly(Map.entry("main/module/bar", "1.0"));
    }

    @Test
    public void group_with_repository_but_no_coordinate_pin_is_rejected() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.pin main/maven/ 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        assertThatThrownBy(() -> new ModuleInfoParser().identify(folder.resolve("module-info.java")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("main/maven/")
                .hasMessageContaining("<module>, <groupId>/<artifactId>, or <group>/<repository>/<coordinate>");
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
        assertThat(info.versions()).containsExactly(Map.entry("main/module/bar", "1.0"));
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
        assertThat(info.versions()).containsExactly(Map.entry("main/module/bar", "1.0"));
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
        assertThat(info.versions()).containsExactly(Map.entry("main/module/bar", "1.0"));
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
        assertThat(info.versions()).containsExactly(Map.entry("main/module/bar", "1.0"));
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

    @Test
    public void bom_tag_supports_floating_versioned_and_hashed_declarations() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom acme.platform
                 * @jenesis.bom other.platform 2.1.0
                 * @jenesis.bom third.platform 3.0.0 SHA256/cafebabe
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.boms()).containsExactly(
                Map.entry("main/module/acme.platform", ""),
                Map.entry("main/module/other.platform", "2.1.0"),
                Map.entry("main/module/third.platform", "3.0.0 SHA256/cafebabe"));
    }

    @Test
    public void bom_tag_keeps_qualified_token_verbatim() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom kotlinc/module/acme.platform 2.1.0 SHA256/cafebabe
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.boms()).containsExactly(
                Map.entry("kotlinc/module/acme.platform", "2.1.0 SHA256/cafebabe"));
    }

    @Test
    public void bom_tag_guard_forms_variants() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom acme.platform 2.1.0
                 * @jenesis.bom acme.platform 1.9.0 SHA256/cafebabe [legacy]
                 * @jenesis.bom guarded.platform [legacy]
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.boms()).containsExactly(Map.entry("main/module/acme.platform", "2.1.0"));
        assertThat(info.bomVariants()).containsOnlyKeys("main/module/acme.platform", "main/module/guarded.platform");
        assertThat(info.bomVariants().get("main/module/acme.platform"))
                .containsExactly(Map.entry("legacy", "1.9.0 SHA256/cafebabe"));
        assertThat(info.bomVariants().get("main/module/guarded.platform"))
                .containsExactly(Map.entry("legacy", ""));
    }

    @Test
    public void bom_tag_resolves_local_files() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom bom-team.properties
                 * @jenesis.bom kotlinc/bom-other.properties
                 */
                module foo {
                  requires bar;
                }
                """);
        ModuleInfo info = new ModuleInfoParser().identify(folder.resolve("module-info.java"));
        assertThat(info.boms()).containsExactly(
                Map.entry("main/bom-team.properties", ""),
                Map.entry("kotlinc/bom-other.properties", ""));
    }

    @Test
    public void bom_tag_rejects_version_on_local_file() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom bom-team.properties 1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        assertThatThrownBy(() -> new ModuleInfoParser().identify(folder.resolve("module-info.java")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a local BOM takes no version or checksum");
    }

    @Test
    public void bom_tag_rejects_nested_local_token() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom kotlinc/module/bom-team.properties
                 */
                module foo {
                  requires bar;
                }
                """);
        assertThatThrownBy(() -> new ModuleInfoParser().identify(folder.resolve("module-info.java")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected [<group>/]bom-<name>.properties");
    }

    @Test
    public void bom_tag_rejects_maven_tokens() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom com.acme/acme-bom 2.1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        assertThatThrownBy(() -> new ModuleInfoParser().identify(folder.resolve("module-info.java")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOM artifacts are fetched from the module repository");
    }

    @Test
    public void bom_tag_rejects_qualified_non_module_repository() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom kotlinc/maven/com.acme/acme-bom 2.1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        assertThatThrownBy(() -> new ModuleInfoParser().identify(folder.resolve("module-info.java")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOM artifacts are fetched from the module repository");
    }

    @Test
    public void bom_tag_rejects_classifier_version() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom acme.platform :linux:2.1.0
                 */
                module foo {
                  requires bar;
                }
                """);
        assertThatThrownBy(() -> new ModuleInfoParser().identify(folder.resolve("module-info.java")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a BOM cannot carry a classifier");
    }

    @Test
    public void bom_tag_rejects_malformed_checksum() throws IOException {
        Files.writeString(folder.resolve("module-info.java"), """
                /**
                 * @jenesis.bom acme.platform 2.1.0 cafebabe
                 */
                module foo {
                  requires bar;
                }
                """);
        assertThatThrownBy(() -> new ModuleInfoParser().identify(folder.resolve("module-info.java")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected <algorithm>/<hash>");
    }
}
