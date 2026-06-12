package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.module.PinModuleInfo;
import build.jenesis.step.Inventory;

import static org.assertj.core.api.Assertions.assertThat;

public class PinModuleInfoTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, input;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        input = Files.createDirectory(root.resolve("input"));
    }

    private SequencedProperties loadInventory() throws IOException {
        Path file = input.resolve(Inventory.INVENTORY);
        return Files.isRegularFile(file) ? SequencedProperties.ofFiles(file) : new SequencedProperties();
    }

    private static int count(SequencedProperties properties, String prefix) {
        return (int) properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith(prefix))
                .filter(name -> name.indexOf('.', prefix.length()) < 0)
                .count();
    }

    private void writeResolved(Map<String, String> entries) throws IOException {
        writeResolved("compile", entries);
    }

    private void writeResolved(String scope, Map<String, String> entries) throws IOException {
        writeResolved(scope.equals("compile") || scope.equals("runtime") ? "main" : scope, scope, entries);
    }

    private void writeResolved(String group, String scope, Map<String, String> entries) throws IOException {
        SequencedProperties properties = loadInventory();
        int index = count(properties, "module.dependency.");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String value = entry.getValue();
            int space = value.indexOf(' ');
            String version = space < 0 ? value : value.substring(0, space);
            String checksum = space < 0 ? "" : value.substring(space + 1).trim();
            String coordinate = entry.getKey() + "/" + version;
            String jar = "resolved/" + coordinate.replace('/', '-') + ".jar";
            properties.setProperty("module.dependency." + index,
                    coordinate + " " + jar + (checksum.isEmpty() ? "" : " " + checksum));
            properties.setProperty("module.dependency." + index + ".scope", scope);
            properties.setProperty("module.dependency." + index + ".group", group);
            index++;
        }
        properties.store(input.resolve(Inventory.INVENTORY));
    }

    private void writeIdentity(Map<String, String> entries) throws IOException {
        SequencedProperties properties = loadInventory();
        int index = count(properties, "module.identity.");
        for (String coordinate : entries.keySet()) {
            properties.setProperty("module.identity." + index++, coordinate);
        }
        properties.store(input.resolve(Inventory.INVENTORY));
    }

    private String run(Path moduleInfo) throws IOException {
        new PinModuleInfo("module", "", moduleInfo, new HashDigestFunction("SHA-256")).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                                input,
                                Map.of(Path.of(Inventory.INVENTORY), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture()
                .join();
        return Files.readString(moduleInfo);
    }

    @Test
    public void renders_dependencies_under_their_resolved_group() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                }
                """);
        writeResolved("tool", "runtime", Map.of("maven/org.example/lib", "1.0 SHA-256/cafebabe"));
        String result = run(file);
        assertThat(result).contains("@jenesis.pin tool/maven/org.example/lib 1.0 SHA-256/cafebabe");
        assertThat(result).doesNotContain("main/maven/org.example/lib");
    }

    @Test
    public void renders_main_group_maven_dependencies_as_bare_coordinates() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                }
                """);
        writeResolved(Map.of("maven/org.slf4j/slf4j-api", "2.0.16 SHA-256/cafebabe"));
        String result = run(file);
        assertThat(result).contains("@jenesis.pin org.slf4j/slf4j-api 2.0.16 SHA-256/cafebabe");
        assertThat(result).doesNotContain("main/maven/org.slf4j/slf4j-api");
    }

    @Test
    public void keeps_long_form_for_maven_coordinates_with_a_classifier() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                }
                """);
        writeResolved(Map.of("maven/org.example/lib/tests", "1.0 SHA-256/cafebabe"));
        String result = run(file);
        assertThat(result).contains("@jenesis.pin main/maven/org.example/lib/tests 1.0 SHA-256/cafebabe");
    }

    @Test
    public void writes_qualified_dependencies_as_jenesis_pin_tags() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                    requires bar;
                }
                """);
        writeResolved("kotlin", Map.of("maven/org.jetbrains/something", "1.2.3"));
        writeResolved("scala", Map.of("module/some.module", "3.5.2"));
        String result = run(file);
        assertThat(result).contains("@jenesis.pin kotlin/maven/org.jetbrains/something 1.2.3");
        assertThat(result).contains("@jenesis.pin scala/module/some.module 3.5.2");
    }

    @Test
    public void inserts_javadoc_when_absent() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        writeResolved(Map.of("module/bar", "1.2.3 SHA-256/cafebabe"));
        String result = run(file);
        assertThat(result).contains("/**");
        assertThat(result).contains("@jenesis.pin bar 1.2.3 SHA-256/cafebabe");
        assertThat(result).contains("module foo {");
    }

    @Test
    public void replaces_existing_requires_block() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                /**
                 * Foo module.
                 *
                 * @jenesis.release 25
                 * @jenesis.pin bar 0.9
                 * @jenesis.pin baz 1.0
                 */
                module foo {
                  requires bar;
                  requires baz;
                }
                """);
        writeResolved(Map.of(
                "module/bar", "1.2.3 SHA-256/cafebabe",
                "module/baz", "2.0 SHA-256/deadbeef",
                "module/transitive", "3.0 SHA-256/feedface"));
        String result = run(file);
        assertThat(result).contains("@jenesis.release 25");
        assertThat(result).contains("Foo module.");
        assertThat(result).contains("@jenesis.pin bar 1.2.3 SHA-256/cafebabe");
        assertThat(result).contains("@jenesis.pin baz 2.0 SHA-256/deadbeef");
        assertThat(result).contains("@jenesis.pin transitive 3.0 SHA-256/feedface");
        assertThat(result).doesNotContain("@jenesis.pin bar 0.9");
        assertThat(result).doesNotContain("@jenesis.pin baz 1.0");
    }

    @Test
    public void omits_hash_when_not_supplied() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        writeResolved(Map.of("module/bar", "1.2.3"));
        String result = run(file);
        assertThat(result).contains("@jenesis.pin bar 1.2.3\n");
        assertThat(result).doesNotContain("SHA-256");
    }

    @Test
    public void preserves_other_javadoc_block_tags() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                /**
                 * Test module.
                 *
                 * @jenesis.test foo
                 * @jenesis.release 25
                 */
                open module foo.test {
                  requires foo;
                }
                """);
        writeResolved("runtime", Map.of("module/junit", "5.11.3 SHA-256/cafebabe"));
        String result = run(file);
        assertThat(result).contains("@jenesis.test foo");
        assertThat(result).contains("@jenesis.release 25");
        assertThat(result).contains("@jenesis.pin junit 5.11.3 SHA-256/cafebabe");
        assertInsideJavadoc(result, "@jenesis.pin junit 5.11.3 SHA-256/cafebabe");
    }

    @Test
    public void inserts_inside_existing_javadoc_when_no_requires_present() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                /**
                 * Existing description.
                 *
                 * @jenesis.release 25
                 */
                module foo {
                  requires bar;
                }
                """);
        writeResolved(Map.of("module/bar", "1.0 SHA-256/cafebabe"));
        String result = run(file);
        assertInsideJavadoc(result, "@jenesis.pin bar 1.0 SHA-256/cafebabe");
        assertThat(result.indexOf("@jenesis.release 25"))
                .isLessThan(result.indexOf("@jenesis.pin bar"));
        assertThat(result.indexOf("@jenesis.pin bar"))
                .isLessThan(result.indexOf("*/"));
    }

    @Test
    public void inserts_inside_existing_javadoc_with_only_other_tags() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                /**
                 * @jenesis.release 25
                 * @jenesis.test build.foo
                 */
                open module foo.test {
                  requires foo;
                }
                """);
        writeResolved(Map.of(
                "module/a", "1.0",
                "module/b", "2.0"));
        String result = run(file);
        assertInsideJavadoc(result, "@jenesis.pin a 1.0");
        assertInsideJavadoc(result, "@jenesis.pin b 2.0");
    }

    private static void assertInsideJavadoc(String content, String needle) {
        int needleIdx = content.indexOf(needle);
        assertThat(needleIdx).as("needle '%s' is present", needle).isNotNegative();
        int openingIdx = content.lastIndexOf("/**", needleIdx);
        int closingIdx = content.indexOf("*/", needleIdx);
        assertThat(openingIdx).as("'%s' is preceded by /**", needle).isNotNegative();
        assertThat(closingIdx).as("'%s' is followed by */", needle).isGreaterThan(needleIdx);
    }

    @Test
    public void emits_pins_for_each_repository() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                }
                """);
        writeResolved(Map.of(
                "maven/org.example/dep", "1.0",
                "module/picked", "2.0"));
        String result = run(file);
        assertThat(result).contains("@jenesis.pin picked 2.0");
        assertThat(result).contains("@jenesis.pin org.example/dep 1.0");
    }

    @Test
    public void second_run_with_same_input_is_a_noop() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                }
                """);
        writeResolved(Map.of("module/bar", "1.0 SHA-256/cafebabe"));
        String afterFirst = run(file);
        String afterSecond = run(file);
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    public void recomputes_checksum_when_jar_is_present() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        Path artifacts = Files.createDirectory(input.resolve("resolved"));
        Path jar = artifacts.resolve("module-bar-1.2.3.jar");
        byte[] payload = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, payload);
        writeResolved(Map.of("module/bar", "1.2.3 SHA-256/stale"));
        String result = run(file);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        String expected = HexFormat.of().formatHex(digest.digest(payload));
        assertThat(result).contains("@jenesis.pin bar 1.2.3 SHA-256/" + expected);
        assertThat(result).doesNotContain("SHA-256/stale");
    }

    @Test
    public void skips_internal_coordinates_from_identity() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        writeResolved(new LinkedHashMap<>(Map.of(
                "module/internal", "1.0",
                "module/external", "2.0")));
        writeIdentity(Map.of("module/internal/1.0", ""));
        String result = run(file);
        assertThat(result).doesNotContain("@jenesis.pin internal");
        assertInsideJavadoc(result, "@jenesis.pin external 2.0");
    }

    @Test
    public void computes_qualified_checksum_from_jar() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        Path artifacts = Files.createDirectory(input.resolve("resolved"));
        Path jar = artifacts.resolve("maven-org.jetbrains-something-1.2.3.jar");
        byte[] payload = "qualified-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, payload);
        writeResolved("kotlin", Map.of("maven/org.jetbrains/something", "1.2.3"));
        String result = run(file);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        String expected = HexFormat.of().formatHex(digest.digest(payload));
        assertThat(result).contains("@jenesis.pin kotlin/maven/org.jetbrains/something 1.2.3 SHA-256/" + expected);
    }
}
