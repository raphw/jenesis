package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.ChecksumStatus;
import build.jenesis.HashDigestFunction;
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

    private void writeResolved(Map<String, String> entries) throws IOException {
        SequencedProperties properties = new SequencedProperties();
        entries.forEach((coordinate, value) -> {
            int space = value.indexOf(' ');
            String version = space < 0 ? value : value.substring(0, space);
            String checksum = space < 0 ? "" : value.substring(space + 1).trim();
            properties.setProperty(coordinate + "/" + version, checksum);
        });
        properties.store(input.resolve(BuildStep.REQUIRES));
    }

    private void writeRequires(Map<String, String> entries) throws IOException {
        SequencedProperties properties = new SequencedProperties();
        entries.forEach(properties::setProperty);
        properties.store(input.resolve(BuildStep.REQUIRES));
    }

    private void writeIdentity(Map<String, String> entries) throws IOException {
        SequencedProperties properties = new SequencedProperties();
        entries.forEach(properties::setProperty);
        properties.store(input.resolve(BuildStep.IDENTITY));
    }

    private Path writeAutomaticJar(Path artifacts, String filename, String moduleName) throws IOException {
        Path jar = artifacts.resolve(filename);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Automatic-Module-Name", moduleName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // Manifest is implicit; no further entries needed for an automatic module.
        }
        return jar;
    }

    private Path writePlainJar(Path artifacts, String filename) throws IOException {
        Path jar = artifacts.resolve(filename);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("sample/Type.class"));
            out.write(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
        }
        return jar;
    }

    private String run(Path moduleInfo) throws IOException {
        new PinModuleInfo("module", moduleInfo, new HashDigestFunction("SHA-256")).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                                input,
                                Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        return Files.readString(moduleInfo);
    }

    private String runFromJars(Path moduleInfo) throws IOException {
        new PinModuleInfo("module", moduleInfo, true, new HashDigestFunction("SHA-256")).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                                input,
                                Map.of(Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        return Files.readString(moduleInfo);
    }

    @Test
    public void writes_qualified_dependencies_as_jenesis_pin_tags() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                    requires bar;
                }
                """);
        writeResolved(Map.of(
                "maven@kotlin/org.jetbrains/something", "1.2.3",
                "module@scala/some.module", "3.5.2"));
        String result = run(file);
        assertThat(result).contains("@jenesis.pin maven@kotlin/org.jetbrains/something 1.2.3");
        assertThat(result).contains("@jenesis.pin @scala/some.module 3.5.2");
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
        writeResolved(Map.of("module/junit", "5.11.3 SHA-256/cafebabe"));
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
    public void ignores_entries_with_other_prefix() throws IOException {
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
        assertThat(result).doesNotContain("org.example");
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
    public void from_jars_pins_module_names_with_coordinate_versions() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.DEPENDENCIES));
        writeAutomaticJar(artifacts, "maven-com.example-bar-1.2.3.jar", "com.example.bar");
        writeAutomaticJar(artifacts, "module-baz-2.0.0.jar", "com.example.baz");
        writeRequires(new LinkedHashMap<>(Map.of(
                "maven/com.example/bar/1.2.3", "",
                "module/baz/2.0.0", "")));
        String result = runFromJars(file);
        assertInsideJavadoc(result, "@jenesis.pin com.example.bar 1.2.3 SHA-256/");
        assertInsideJavadoc(result, "@jenesis.pin com.example.baz 2.0.0 SHA-256/");
    }

    @Test
    public void from_jars_skips_versionless_coordinates() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.DEPENDENCIES));
        writeAutomaticJar(artifacts, "module-build.jenesis.jar", "build.jenesis");
        writeAutomaticJar(artifacts, "module-other-1.0.0.jar", "other.module");
        writeRequires(new LinkedHashMap<>(Map.of(
                "module/build.jenesis", "",
                "module/other/1.0.0", "")));
        String result = runFromJars(file);
        assertThat(result).doesNotContain("@jenesis.pin build.jenesis");
        assertInsideJavadoc(result, "@jenesis.pin other.module 1.0.0");
    }

    @Test
    public void recomputes_checksum_when_jar_is_present() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.DEPENDENCIES));
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
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.DEPENDENCIES));
        Path jar = artifacts.resolve("maven@kotlin-org.jetbrains-something-1.2.3.jar");
        byte[] payload = "qualified-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, payload);
        writeResolved(Map.of("maven@kotlin/org.jetbrains/something", "1.2.3"));
        String result = run(file);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        String expected = HexFormat.of().formatHex(digest.digest(payload));
        assertThat(result).contains("@jenesis.pin maven@kotlin/org.jetbrains/something 1.2.3 SHA-256/" + expected);
    }

    @Test
    public void from_jars_pins_non_modular_dependency_by_maven_coordinate() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.DEPENDENCIES));
        writePlainJar(artifacts, "maven-org.jetbrains-annotations-13.0.jar");
        writeRequires(new LinkedHashMap<>(Map.of(
                "maven/org.jetbrains/annotations/13.0", "")));
        String result = runFromJars(file);
        assertInsideJavadoc(result, "@jenesis.pin maven/org.jetbrains/annotations 13.0 SHA-256/");
        assertThat(result).doesNotContain("@jenesis.pin maven.org.jetbrains.annotations");
    }

    @Test
    public void from_jars_pins_qualified_dependencies_and_skips_them_as_plain() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.DEPENDENCIES));
        writeAutomaticJar(artifacts, "maven@kotlin-org.jetbrains-compiler-1.2.3.jar", "org.jetbrains.compiler");
        writeRequires(new LinkedHashMap<>(Map.of(
                "maven@kotlin/org.jetbrains/compiler/1.2.3", "")));
        String result = runFromJars(file);
        assertInsideJavadoc(result, "@jenesis.pin maven@kotlin/org.jetbrains/compiler 1.2.3 SHA-256/");
        assertThat(result).doesNotContain("@jenesis.pin org.jetbrains.compiler");
    }

    @Test
    public void from_jars_skips_internal_coordinates_from_identity() throws IOException {
        Path file = root.resolve("module-info.java");
        Files.writeString(file, """
                module foo {
                  requires bar;
                }
                """);
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.DEPENDENCIES));
        writeAutomaticJar(artifacts, "module-internal-1.0.0.jar", "internal.module");
        writeAutomaticJar(artifacts, "module-external-2.0.0.jar", "external.module");
        writeRequires(new LinkedHashMap<>(Map.of(
                "module/internal/1.0.0", "",
                "module/external/2.0.0", "")));
        writeIdentity(Map.of("module/internal/1.0.0", ""));
        String result = runFromJars(file);
        assertThat(result).doesNotContain("@jenesis.pin internal.module");
        assertInsideJavadoc(result, "@jenesis.pin external.module 2.0.0");
    }
}
