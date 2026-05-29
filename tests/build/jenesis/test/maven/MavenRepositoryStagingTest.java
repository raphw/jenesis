package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenRepositoryStaging;
import build.jenesis.step.Inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenRepositoryStagingTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, source;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        source = Files.createDirectory(root.resolve("source"));
    }

    @Test
    public void stages_main_module_jars_and_pom_at_canonical_path() throws IOException {
        Path inv = mainInventory("foo", "com.example", "foo", "1.2.3",
                "classes.jar", "sources.jar", "javadoc.jar");
        writeArtifact(inv, "classes.jar", "classes-bytes");
        writeArtifact(inv, "sources.jar", "sources-bytes");
        writeArtifact(inv, "javadoc.jar", "javadoc-bytes");

        BuildStepResult result = run(true, inv);

        assertThat(result.next()).isTrue();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).hasContent("classes-bytes");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-sources.jar")).hasContent("sources-bytes");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-javadoc.jar")).hasContent("javadoc-bytes");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom")).exists();
    }

    @Test
    public void only_existing_artifacts_are_linked() throws IOException {
        Path inv = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(inv, "classes.jar", "c");

        run(true, inv);

        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).hasContent("c");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-sources.jar")).doesNotExist();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-javadoc.jar")).doesNotExist();
    }

    @Test
    public void main_pom_is_unchanged_when_no_test_variants_exist() throws IOException {
        Path inv = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(inv, "classes.jar", "c");

        run(true, inv);

        String staged = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        assertThat(staged).doesNotContain("<dependency>");
        assertThat(staged).doesNotContain("<scope>test</scope>");
    }

    @Test
    public void merges_test_variant_dependencies_into_main_pom_with_scope_test() throws IOException {
        Path main = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "foo",
                List.of(
                        new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3"),
                        new Dep("org.assertj", "assertj-core", "3.27.0")),
                "classes.jar");
        writeArtifact(test, "classes.jar", "test");

        run(true, main, test);

        String pom = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        assertThat(pom).contains("<artifactId>junit-jupiter</artifactId>");
        assertThat(pom).contains("<artifactId>assertj-core</artifactId>");
        long testScopes = pom.lines().filter(line -> line.trim().equals("<scope>test</scope>")).count();
        assertThat(testScopes).isEqualTo(2);
    }

    @Test
    public void self_referencing_test_dependency_is_excluded_from_merge() throws IOException {
        Path main = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "foo",
                List.of(
                        new Dep("com.example", "foo", "1.2.3"),
                        new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3")),
                "classes.jar");
        writeArtifact(test, "classes.jar", "test");

        run(true, main, test);

        String pom = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        long fooDeps = pom.lines().filter(line -> line.trim().equals("<artifactId>foo</artifactId>")).count();
        assertThat(fooDeps).isEqualTo(1);
        assertThat(pom).contains("<artifactId>junit-jupiter</artifactId>");
    }

    @Test
    public void test_variant_jars_are_routed_to_main_coordinate_with_test_classifier() throws IOException {
        Path main = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "foo",
                List.of(),
                "classes.jar", "sources.jar", "javadoc.jar");
        writeArtifact(test, "classes.jar", "test-classes");
        writeArtifact(test, "sources.jar", "test-sources");
        writeArtifact(test, "javadoc.jar", "test-javadoc");

        run(true, main, test);

        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests.jar")).hasContent("test-classes");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests-sources.jar")).hasContent("test-sources");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests-javadoc.jar")).hasContent("test-javadoc");
    }

    @Test
    public void test_variant_does_not_emit_a_separate_pom() throws IOException {
        Path main = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "foo",
                List.of(), "classes.jar");
        writeArtifact(test, "classes.jar", "test");

        run(true, main, test);

        try (Stream<Path> stream = Files.walk(next)) {
            List<String> poms = stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".pom"))
                    .toList();
            assertThat(poms).containsExactly("foo-1.2.3.pom");
        }
    }

    @Test
    public void test_variants_are_routed_to_their_declared_main() throws IOException {
        Path mainA = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(mainA, "classes.jar", "foo-main");
        Path mainB = mainInventory("bar", "com.example", "bar", "1.2.3", "classes.jar");
        writeArtifact(mainB, "classes.jar", "bar-main");
        Path testA = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "foo",
                List.of(new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3")), "classes.jar");
        writeArtifact(testA, "classes.jar", "foo-test");
        Path testB = testInventory("bar-test", "com.example", "bar.test", "1.2.3", "bar",
                List.of(new Dep("org.assertj", "assertj-core", "3.27.0")), "classes.jar");
        writeArtifact(testB, "classes.jar", "bar-test");

        run(true, mainA, mainB, testA, testB);

        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests.jar")).hasContent("foo-test");
        assertThat(next.resolve("com/example/bar/1.2.3/bar-1.2.3-tests.jar")).hasContent("bar-test");
        String fooPom = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        assertThat(fooPom).contains("<artifactId>junit-jupiter</artifactId>");
        assertThat(fooPom).doesNotContain("<artifactId>assertj-core</artifactId>");
        String barPom = Files.readString(next.resolve("com/example/bar/1.2.3/bar-1.2.3.pom"));
        assertThat(barPom).contains("<artifactId>assertj-core</artifactId>");
        assertThat(barPom).doesNotContain("<artifactId>junit-jupiter</artifactId>");
    }

    @Test
    public void two_tests_for_the_same_main_fail_loudly() throws IOException {
        Path main = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path testA = testInventory("foo-test-a", "com.example", "foo.test.a", "1.2.3", "foo",
                List.of(), "classes.jar");
        writeArtifact(testA, "classes.jar", "test-a");
        Path testB = testInventory("foo-test-b", "com.example", "foo.test.b", "1.2.3", "foo",
                List.of(), "classes.jar");
        writeArtifact(testB, "classes.jar", "test-b");

        assertThatThrownBy(() -> run(true, main, testA, testB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple test modules name main 'foo'")
                .hasMessageContaining("'-tests' classifier")
                .hasMessageContaining("module-foo-test-a")
                .hasMessageContaining("module-foo-test-b");
    }

    @Test
    public void test_referencing_unknown_main_fails_loudly() throws IOException {
        Path main = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "typo",
                List.of(), "classes.jar");
        writeArtifact(test, "classes.jar", "test");

        assertThatThrownBy(() -> run(true, main, test))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test module 'module-foo-test'")
                .hasMessageContaining("references unknown main 'typo'")
                .hasMessageContaining("[foo]");
    }

    @Test
    public void bare_test_with_no_main_present_fails_loudly() throws IOException {
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "",
                List.of(), "classes.jar");
        writeArtifact(test, "classes.jar", "test");

        assertThatThrownBy(() -> run(true, test))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test module 'module-foo-test'")
                .hasMessageContaining("does not name the main module it tests")
                .hasMessageContaining("no main module is present");
    }

    @Test
    public void bare_test_with_multiple_mains_fails_loudly() throws IOException {
        Path mainA = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(mainA, "classes.jar", "foo-main");
        Path mainB = mainInventory("bar", "com.example", "bar", "1.2.3", "classes.jar");
        writeArtifact(mainB, "classes.jar", "bar-main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "",
                List.of(), "classes.jar");
        writeArtifact(test, "classes.jar", "test");

        assertThatThrownBy(() -> run(true, mainA, mainB, test))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test module 'module-foo-test'")
                .hasMessageContaining("does not name the main module it tests")
                .hasMessageContaining("multiple main modules are present")
                .hasMessageContaining("foo")
                .hasMessageContaining("bar");
    }

    @Test
    public void duplicate_main_artifact_ids_fail_loudly() throws IOException {
        Path mainA = mainInventory("foo-a", "com.example.a", "foo", "1.2.3", "classes.jar");
        writeArtifact(mainA, "classes.jar", "foo-a");
        Path mainB = mainInventory("foo-b", "com.example.b", "foo", "1.2.3", "classes.jar");
        writeArtifact(mainB, "classes.jar", "foo-b");

        assertThatThrownBy(() -> run(true, mainA, mainB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate main artifactId 'foo'")
                .hasMessageContaining("module-foo-a")
                .hasMessageContaining("com.example.a:foo:1.2.3")
                .hasMessageContaining("module-foo-b")
                .hasMessageContaining("com.example.b:foo:1.2.3");
    }

    @Test
    public void test_dependency_already_declared_in_main_pom_is_not_re_added_with_test_scope() throws IOException {
        Path main = mainInventoryWithDeps("foo", "com.example", "foo", "1.2.3",
                List.of(new Dep("org.slf4j", "slf4j-api", "2.0.9")), "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "foo",
                List.of(
                        new Dep("org.slf4j", "slf4j-api", "2.0.9"),
                        new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3")),
                "classes.jar");
        writeArtifact(test, "classes.jar", "test");

        run(true, main, test);

        String pom = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        long slf4jOccurrences = pom.lines().filter(line -> line.trim().equals("<artifactId>slf4j-api</artifactId>")).count();
        assertThat(slf4jOccurrences).isEqualTo(1);
        assertThat(pom).contains("<artifactId>junit-jupiter</artifactId>");
        long testScopes = pom.lines().filter(line -> line.trim().equals("<scope>test</scope>")).count();
        assertThat(testScopes).isEqualTo(1);
    }

    @Test
    public void default_does_not_stage_test_artifacts() throws IOException {
        Path main = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "foo",
                List.of(), "classes.jar", "sources.jar", "javadoc.jar");
        writeArtifact(test, "classes.jar", "test-classes");
        writeArtifact(test, "sources.jar", "test-sources");
        writeArtifact(test, "javadoc.jar", "test-javadoc");

        run(false, main, test);

        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).exists();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests.jar")).doesNotExist();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests-sources.jar")).doesNotExist();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests-javadoc.jar")).doesNotExist();
    }

    @Test
    public void default_does_not_merge_test_dependencies_into_main_pom() throws IOException {
        Path main = mainInventory("foo", "com.example", "foo", "1.2.3", "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = testInventory("foo-test", "com.example", "foo.test", "1.2.3", "foo",
                List.of(new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3")), "classes.jar");
        writeArtifact(test, "classes.jar", "test");

        run(false, main, test);

        String pom = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        assertThat(pom).doesNotContain("junit-jupiter");
        assertThat(pom).doesNotContain("<scope>test</scope>");
    }

    @Test
    public void arguments_without_inventory_are_skipped() throws IOException {
        Path stray = Files.createDirectory(source.resolve("stray"));
        writeArtifact(stray, "classes.jar", "stray");

        BuildStepResult result = run(true, stray);

        assertThat(result.next()).isTrue();
        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private Path mainInventory(String name,
                               String groupId,
                               String artifactId,
                               String version,
                               String... artifactFiles) throws IOException {
        return writeInventory(name, groupId, artifactId, version, null, List.of(), artifactFiles);
    }

    private Path mainInventoryWithDeps(String name,
                                       String groupId,
                                       String artifactId,
                                       String version,
                                       List<Dep> deps,
                                       String... artifactFiles) throws IOException {
        return writeInventory(name, groupId, artifactId, version, null, deps, artifactFiles);
    }

    private Path testInventory(String name,
                               String groupId,
                               String artifactId,
                               String version,
                               String testsOf,
                               List<Dep> deps,
                               String... artifactFiles) throws IOException {
        return writeInventory(name, groupId, artifactId, version, testsOf, deps, artifactFiles);
    }

    private Path writeInventory(String name,
                                String groupId,
                                String artifactId,
                                String version,
                                String testsOf,
                                List<Dep> deps,
                                String... artifactFiles) throws IOException {
        Path folder = Files.createDirectory(source.resolve(name));
        Files.writeString(folder.resolve("pom.xml"), buildPom(groupId, artifactId, version, deps));
        SequencedProperties inventory = new SequencedProperties();
        String prefix = "module-" + name;
        inventory.setProperty(prefix + ".pom.path", "pom.xml");
        for (String artifactFile : artifactFiles) {
            switch (artifactFile) {
                case "classes.jar" -> inventory.setProperty(prefix + ".artifacts.0.path", "artifacts/" + artifactFile);
                case "sources.jar" -> inventory.setProperty(prefix + ".sources.0.path", "sources/" + artifactFile);
                case "javadoc.jar" -> inventory.setProperty(prefix + ".documentation.0.path", "documentation/" + artifactFile);
                default -> throw new IllegalArgumentException("Unknown artifact file: " + artifactFile);
            }
        }
        if (testsOf != null) {
            inventory.setProperty(prefix + ".test", testsOf);
        }
        inventory.store(folder.resolve(Inventory.INVENTORY));
        return folder;
    }

    private static Path writeArtifact(Path folder, String filename, String content) throws IOException {
        String subdir = switch (filename) {
            case "classes.jar" -> "artifacts";
            case "sources.jar" -> "sources";
            case "javadoc.jar" -> "documentation";
            default -> throw new IllegalArgumentException("Unknown artifact: " + filename);
        };
        Path dir = folder.resolve(subdir);
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
        return Files.writeString(dir.resolve(filename), content);
    }

    private BuildStepResult run(boolean includeTests, Path... inventoryFolders) throws IOException {
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        for (Path folder : inventoryFolders) {
            Map<Path, ChecksumStatus> checksums = new LinkedHashMap<>();
            try (Stream<Path> stream = Files.list(folder)) {
                stream.forEach(file -> checksums.put(
                        Path.of(file.getFileName().toString()),
                        ChecksumStatus.ADDED));
            }
            arguments.put(folder.getFileName().toString(), new BuildStepArgument(folder, checksums));
        }
        return new MavenRepositoryStaging(includeTests).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
    }

    private static String buildPom(String groupId, String artifactId, String version, List<Dep> deps) {
        StringBuilder builder = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                """.formatted(groupId, artifactId, version));
        if (!deps.isEmpty()) {
            builder.append("    <dependencies>\n");
            for (Dep dep : deps) {
                builder.append("        <dependency>\n");
                builder.append("            <groupId>").append(dep.groupId()).append("</groupId>\n");
                builder.append("            <artifactId>").append(dep.artifactId()).append("</artifactId>\n");
                builder.append("            <version>").append(dep.version()).append("</version>\n");
                builder.append("        </dependency>\n");
            }
            builder.append("    </dependencies>\n");
        }
        builder.append("</project>\n");
        return builder.toString();
    }

    private record Dep(String groupId, String artifactId, String version) {
    }
}
