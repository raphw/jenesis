package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.module.PinModuleInfo;

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

    private void writeVersions(Map<String, String> entries) throws IOException {
        Properties properties = new SequencedProperties();
        entries.forEach(properties::setProperty);
        try (Writer writer = Files.newBufferedWriter(input.resolve(BuildStep.VERSIONS))) {
            properties.store(writer, null);
        }
    }

    private String run(Path moduleInfo) throws IOException {
        new PinModuleInfo("module", moduleInfo).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                                input,
                                Map.of(Path.of(BuildStep.VERSIONS), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        return Files.readString(moduleInfo);
    }

    @Test
    public void inserts_javadoc_when_absent() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        writeVersions(Map.of("module/bar", "1.2.3 SHA-256/cafebabe"));
        String result = run(file);
        assertThat(result).contains("/**");
        assertThat(result).contains("@requires bar 1.2.3 SHA-256/cafebabe");
        assertThat(result).contains("module foo {");
    }

    @Test
    public void replaces_existing_requires_block() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                /**
                 * Foo module.
                 *
                 * @release 25
                 * @requires bar 0.9
                 * @requires baz 1.0
                 */
                module foo {
                  requires bar;
                  requires baz;
                }
                """);
        writeVersions(Map.of(
                "module/bar", "1.2.3 SHA-256/cafebabe",
                "module/baz", "2.0 SHA-256/deadbeef",
                "module/transitive", "3.0 SHA-256/feedface"));
        String result = run(file);
        assertThat(result).contains("@release 25");
        assertThat(result).contains("Foo module.");
        assertThat(result).contains("@requires bar 1.2.3 SHA-256/cafebabe");
        assertThat(result).contains("@requires baz 2.0 SHA-256/deadbeef");
        assertThat(result).contains("@requires transitive 3.0 SHA-256/feedface");
        assertThat(result).doesNotContain("@requires bar 0.9");
        assertThat(result).doesNotContain("@requires baz 1.0");
    }

    @Test
    public void omits_hash_when_not_supplied() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        writeVersions(Map.of("module/bar", "1.2.3"));
        String result = run(file);
        assertThat(result).contains("@requires bar 1.2.3\n");
        assertThat(result).doesNotContain("SHA-256");
    }

    @Test
    public void preserves_other_javadoc_block_tags() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                /**
                 * Test module.
                 *
                 * @tests foo
                 * @release 25
                 */
                open module foo.test {
                  requires foo;
                }
                """);
        writeVersions(Map.of("module/junit", "5.11.3 SHA-256/cafebabe"));
        String result = run(file);
        assertThat(result).contains("@tests foo");
        assertThat(result).contains("@release 25");
        assertThat(result).contains("@requires junit 5.11.3 SHA-256/cafebabe");
        assertInsideJavadoc(result, "@requires junit 5.11.3 SHA-256/cafebabe");
    }

    @Test
    public void inserts_inside_existing_javadoc_when_no_requires_present() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                /**
                 * Existing description.
                 *
                 * @release 25
                 */
                module foo {
                  requires bar;
                }
                """);
        writeVersions(Map.of("module/bar", "1.0 SHA-256/cafebabe"));
        String result = run(file);
        assertInsideJavadoc(result, "@requires bar 1.0 SHA-256/cafebabe");
        assertThat(result.indexOf("@release 25"))
                .isLessThan(result.indexOf("@requires bar"));
        assertThat(result.indexOf("@requires bar"))
                .isLessThan(result.indexOf("*/"));
    }

    @Test
    public void inserts_inside_existing_javadoc_with_only_other_tags() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                /**
                 * @release 25
                 * @tests build.foo
                 */
                open module foo.test {
                  requires foo;
                }
                """);
        writeVersions(Map.of(
                "module/a", "1.0",
                "module/b", "2.0"));
        String result = run(file);
        assertInsideJavadoc(result, "@requires a 1.0");
        assertInsideJavadoc(result, "@requires b 2.0");
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
    public void ignores_entries_with_other_prefix() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                }
                """);
        writeVersions(Map.of(
                "maven/org.example/dep", "1.0",
                "module/picked", "2.0"));
        String result = run(file);
        assertThat(result).contains("@requires picked 2.0");
        assertThat(result).doesNotContain("org.example");
    }

    @Test
    public void second_run_with_same_input_is_a_noop() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                }
                """);
        writeVersions(Map.of("module/bar", "1.0 SHA-256/cafebabe"));
        String afterFirst = run(file);
        String afterSecond = run(file);
        assertThat(afterSecond).isEqualTo(afterFirst);
    }
}
