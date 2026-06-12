package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.HashDigestFunction;
import build.jenesis.Platform;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.PinPom;
import build.jenesis.step.Inventory;

import static org.assertj.core.api.Assertions.assertThat;

public class PinPomTest {

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
        String group = scope.equals("compile") || scope.equals("runtime") ? "main" : scope;
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

    private String run(Path pomFile) throws IOException {
        return run(pomFile, Platform.tokens("linux,x86_64"));
    }

    private String run(Path pomFile, SequencedSet<String> platform) throws IOException {
        new PinPom("maven", "", List.of(pomFile), new HashDigestFunction("SHA-256"))
                .platform(platform)
                .apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                                input,
                                Map.of(Path.of(Inventory.INVENTORY), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture()
                .join();
        return Files.readString(pomFile);
    }

    @Test
    public void updates_matched_guarded_comment_line_and_preserves_others() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <!--jenesis.pin
                    kotlin/maven/org.jetbrains/something 1.0.0 SHA-256/aaa [windows]
                    kotlin/maven/org.jetbrains/something 1.0.0 SHA-256/bbb
                    plugin/maven/org.example/other 0.9
                    -->
                </project>
                """);
        writeResolved("kotlin", Map.of("maven/org.jetbrains/something", "1.2.3 SHA-256/fresh"));
        writeResolved("plugin", Map.of("maven/org.example/other", "4.5.6 SHA-256/cafebabe"));
        String result = run(pom, Platform.tokens("windows,x86_64"));
        assertThat(result).contains("kotlin/maven/org.jetbrains/something 1.2.3 SHA-256/fresh [windows]");
        assertThat(result).contains("kotlin/maven/org.jetbrains/something 1.0.0 SHA-256/bbb");
        assertThat(result).doesNotContain("SHA-256/aaa");
        assertThat(result).contains("plugin/maven/org.example/other 4.5.6 SHA-256/cafebabe");
        assertThat(result).doesNotContain("org.example/other 0.9");
    }

    @Test
    public void updates_matched_fallback_comment_line_and_preserves_guard() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <!--jenesis.pin
                    kotlin/maven/org.jetbrains/something 1.0.0 SHA-256/aaa [windows]
                    kotlin/maven/org.jetbrains/something 1.0.0 SHA-256/bbb
                    -->
                </project>
                """);
        writeResolved("kotlin", Map.of("maven/org.jetbrains/something", "1.2.3 SHA-256/fresh"));
        String result = run(pom, Platform.tokens("linux,x86_64"));
        assertThat(result).contains("kotlin/maven/org.jetbrains/something 1.0.0 SHA-256/aaa [windows]");
        assertThat(result).contains("kotlin/maven/org.jetbrains/something 1.2.3 SHA-256/fresh");
        assertThat(result).doesNotContain("SHA-256/bbb");
    }

    @Test
    public void guarded_main_pin_stays_in_block_and_out_of_dependency_management() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <!--jenesis.pin
                    main/maven/io.smallrye.reactive/mutiny-zero 1.0.0 SHA-256/aaa [windows]
                    main/maven/io.smallrye.reactive/mutiny-zero 0.9.0 SHA-256/bbb
                    -->
                </project>
                """);
        writeResolved(Map.of("maven/io.smallrye.reactive/mutiny-zero", "1.1.1 SHA-256/fresh"));
        String result = run(pom, Platform.tokens("linux,x86_64"));
        assertThat(result).contains("main/maven/io.smallrye.reactive/mutiny-zero 1.0.0 SHA-256/aaa [windows]");
        assertThat(result).contains("main/maven/io.smallrye.reactive/mutiny-zero 1.1.1 SHA-256/fresh");
        assertThat(result).doesNotContain("SHA-256/bbb");
        assertThat(result).doesNotContain("<dependencyManagement>");
    }

    @Test
    public void preserves_guarded_comment_key_without_local_resolution() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <!--jenesis.pin
                    kotlin/maven/org.jetbrains/something 1.0.0 [windows]
                    -->
                </project>
                """);
        writeResolved("plugin", Map.of("maven/org.example/other", "4.5.6"));
        String result = run(pom, Platform.tokens("linux,x86_64"));
        assertThat(result).contains("kotlin/maven/org.jetbrains/something 1.0.0 [windows]");
        assertThat(result).contains("plugin/maven/org.example/other 4.5.6");
    }

    @Test
    public void writes_qualified_dependencies_to_a_comment_block() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        writeResolved("kotlin", Map.of("maven/org.jetbrains/something", "1.2.3"));
        writeResolved("plugin", Map.of("maven/org.example/other", "4.5.6"));
        String result = run(pom);
        assertThat(result).contains("<!--jenesis.pin");
        assertThat(result).contains("kotlin/maven/org.jetbrains/something 1.2.3");
        assertThat(result).contains("plugin/maven/org.example/other 4.5.6");
        assertThat(result).doesNotContain("<dependencyManagement>");
    }

    @Test
    public void encodes_double_hyphen_in_a_comment_block() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        writeResolved("kotlin", Map.of("maven/org.jetbrains/something", "1.2--3"));
        String result = run(pom);
        assertThat(result).contains("kotlin/maven/org.jetbrains/something 1.2&#45;&#45;3");
        int blockStart = result.indexOf("<!--jenesis.pin");
        int blockEnd = result.indexOf("-->", blockStart);
        assertThat(result.substring(blockStart + "<!--".length(), blockEnd)).doesNotContain("--");
    }

    @Test
    public void inserts_dependency_management_when_absent() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>direct</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        writeResolved(Map.of(
                "maven/org.example/transitive", "2.0 SHA-256/cafebabe"));
        String result = run(pom);
        assertThat(result).contains("<dependencyManagement>");
        assertThat(result).contains("<artifactId>transitive</artifactId>");
        assertThat(result).contains("<version>2.0</version>");
        assertThat(result).contains("<!--Checksum/SHA-256/cafebabe-->");
        assertThat(result).contains("<dependencies>\n        <dependency>\n            <groupId>org.example</groupId>\n            <artifactId>direct</artifactId>");
        int dmIndex = result.indexOf("<dependencyManagement>");
        int depsIndex = result.indexOf("<dependencies>", dmIndex + "<dependencyManagement>".length());
        int directDepsIndex = result.indexOf("<dependencies>", depsIndex + 1);
        assertThat(directDepsIndex).isGreaterThan(depsIndex);
    }

    @Test
    public void replaces_existing_dependency_management() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>stale</groupId>
                                <artifactId>old</artifactId>
                                <version>0.1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        writeResolved(Map.of(
                "maven/org.example/fresh", "3.0 SHA-256/deadbeef"));
        String result = run(pom);
        assertThat(result).doesNotContain("stale");
        assertThat(result).doesNotContain("<artifactId>old</artifactId>");
        assertThat(result).contains("<artifactId>fresh</artifactId>");
        assertThat(result).contains("<version>3.0</version>");
        assertThat(result).contains("<!--Checksum/SHA-256/deadbeef-->");
    }

    @Test
    public void omits_checksum_comment_when_version_has_no_hash() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        writeResolved(Map.of("maven/org.example/no-hash", "1.5"));
        String result = run(pom);
        assertThat(result).contains("<artifactId>no-hash</artifactId>");
        assertThat(result).contains("<version>1.5</version>");
        assertThat(result).doesNotContain("<!--Checksum");
    }

    @Test
    public void emits_type_and_classifier_when_present() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        writeResolved(Map.of(
                "maven/org.example/sources/jar/sources", "4.0 SHA-256/cafebabe",
                "maven/org.example/zipped/zip", "5.0"));
        String result = run(pom);
        assertThat(result).contains("<type>zip</type>");
        assertThat(result).contains("<classifier>sources</classifier>");
        assertThat(result).doesNotContain("<type>jar</type>");
    }

    @Test
    public void ignores_entries_with_other_prefix() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        writeResolved(Map.of(
                "module/org.example.module", "1.0",
                "maven/org.example/picked", "2.0"));
        String result = run(pom);
        assertThat(result).contains("<artifactId>picked</artifactId>");
        assertThat(result).doesNotContain("<artifactId>org.example.module</artifactId>");
        assertThat(result).contains("main/module/org.example.module 1.0");
    }

    @Test
    public void strips_checksum_comments_from_direct_dependencies() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>direct</artifactId>
                            <version>1.0</version>
                            <!--Checksum/SHA-256/stale-->
                        </dependency>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>another</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        writeResolved(Map.of("maven/org.example/direct", "1.0 SHA-256/cafebabe"));
        String result = run(pom);
        assertThat(result).contains("<artifactId>direct</artifactId>");
        assertThat(result).doesNotContain("Checksum/SHA-256/stale");
        long directDepChecksums = result.lines()
                .filter(line -> line.contains("Checksum/"))
                .filter(line -> !line.contains("cafebabe"))
                .count();
        assertThat(directDepChecksums).isZero();
        assertThat(result).contains("<!--Checksum/SHA-256/cafebabe-->");
    }

    @Test
    public void preserves_checksum_comments_inside_dependency_management() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        writeResolved(Map.of(
                "maven/org.example/with-hash", "1.0 SHA-256/cafebabe",
                "maven/org.example/without-hash", "2.0"));
        String result = run(pom);
        assertThat(result).contains("<!--Checksum/SHA-256/cafebabe-->");
    }

    @Test
    public void second_run_with_same_input_is_a_noop() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        writeResolved(Map.of("maven/org.example/dep", "1.0 SHA-256/cafebabe"));
        String afterFirst = run(pom);
        String afterSecond = run(pom);
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    public void recomputes_checksum_when_jar_is_present() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        Path resolved = Files.createDirectory(input.resolve("resolved"));
        Path jar = resolved.resolve("maven-org.example-dep-1.0.jar");
        byte[] payload = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, payload);
        writeResolved(Map.of("maven/org.example/dep", "1.0 SHA-256/stale"));
        String result = run(pom);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        String expected = HexFormat.of().formatHex(digest.digest(payload));
        assertThat(result).contains("<!--Checksum/SHA-256/" + expected + "-->");
        assertThat(result).doesNotContain("SHA-256/stale");
    }

    @Test
    public void computes_qualified_checksum_from_jar() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        Path resolved = Files.createDirectory(input.resolve("resolved"));
        Path jar = resolved.resolve("maven-org.jetbrains-something-1.2.3.jar");
        byte[] payload = "qualified-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, payload);
        writeResolved("kotlin", Map.of("maven/org.jetbrains/something", "1.2.3"));
        String result = run(pom);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        String expected = HexFormat.of().formatHex(digest.digest(payload));
        assertThat(result).contains("kotlin/maven/org.jetbrains/something 1.2.3 SHA-256/" + expected);
    }

    @Test
    public void skips_internal_coordinates_from_identity() throws IOException {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        writeResolved(new LinkedHashMap<>(Map.of(
                "maven/com.example/internal", "0-SNAPSHOT",
                "maven/com.example/external", "1.2.3 SHA-256/cafebabe",
                "maven/com.example/managed", "9.9")));
        writeIdentity(Map.of("maven/com.example/internal/0-SNAPSHOT", ""));
        String result = run(pom);
        assertThat(result).doesNotContain("<artifactId>internal</artifactId>");
        assertThat(result).contains("<artifactId>external</artifactId>");
        assertThat(result).contains("<artifactId>managed</artifactId>");
    }
}
