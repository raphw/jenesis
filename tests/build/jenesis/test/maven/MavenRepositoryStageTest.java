package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.maven.MavenRepositoryStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenRepositoryStageTest {

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
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "classes-bytes");
        Files.writeString(main.resolve("sources.jar"), "sources-bytes");
        Files.writeString(main.resolve("javadoc.jar"), "javadoc-bytes");

        BuildStepResult result = run(source, "module-foo");
        assertThat(result.next()).isTrue();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).hasContent("classes-bytes");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-sources.jar")).hasContent("sources-bytes");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-javadoc.jar")).hasContent("javadoc-bytes");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom")).exists();
    }

    @Test
    public void main_pom_is_unchanged_when_no_test_variants_exist() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "x");

        run(source, "module-foo");

        String staged = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        assertThat(staged).doesNotContain("<dependency>");
        assertThat(staged).doesNotContain("<scope>test</scope>");
    }

    @Test
    public void merges_test_variant_dependencies_into_main_pom_with_scope_test() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "main");

        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(test, "foo",
                "com.example", "foo.test", "1.2.3",
                List.of(
                        new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3"),
                        new Dep("org.assertj", "assertj-core", "3.27.0")));
        Files.writeString(test.resolve("classes.jar"), "test");

        run(source, "module-foo", "module-foo-test");

        String pom = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        assertThat(pom).contains("<artifactId>junit-jupiter</artifactId>");
        assertThat(pom).contains("<artifactId>assertj-core</artifactId>");
        long testScopes = pom.lines().filter(line -> line.trim().equals("<scope>test</scope>")).count();
        assertThat(testScopes).isEqualTo(2);
    }

    @Test
    public void self_referencing_test_dependency_is_excluded_from_merge() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "main");

        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(test, "foo",
                "com.example", "foo.test", "1.2.3",
                List.of(
                        new Dep("com.example", "foo", "1.2.3"),
                        new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3")));
        Files.writeString(test.resolve("classes.jar"), "test");

        run(source, "module-foo", "module-foo-test");

        String pom = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        long fooDeps = pom.lines().filter(line -> line.trim().equals("<artifactId>foo</artifactId>")).count();
        assertThat(fooDeps).isEqualTo(1);
        assertThat(pom).contains("<artifactId>junit-jupiter</artifactId>");
    }

    @Test
    public void test_variant_jars_are_routed_to_main_coordinate_with_test_classifier() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "main");

        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(test, "foo", "com.example", "foo.test", "1.2.3", List.of());
        Files.writeString(test.resolve("classes.jar"), "test-classes");
        Files.writeString(test.resolve("sources.jar"), "test-sources");
        Files.writeString(test.resolve("javadoc.jar"), "test-javadoc");

        run(source, "module-foo", "module-foo-test");

        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests.jar")).hasContent("test-classes");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests-sources.jar")).hasContent("test-sources");
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests-javadoc.jar")).hasContent("test-javadoc");
    }

    @Test
    public void test_variant_does_not_emit_a_separate_pom() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "main");

        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(test, "foo", "com.example", "foo.test", "1.2.3", List.of());
        Files.writeString(test.resolve("classes.jar"), "test");

        run(source, "module-foo", "module-foo-test");

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
        Path mainA = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(mainA, "com.example", "foo", "1.2.3");
        Files.writeString(mainA.resolve("classes.jar"), "foo-main");

        Path mainB = Files.createDirectory(source.resolve("module-bar"));
        writeMainModule(mainB, "com.example", "bar", "1.2.3");
        Files.writeString(mainB.resolve("classes.jar"), "bar-main");

        Path testA = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(testA, "foo", "com.example", "foo.test", "1.2.3",
                List.of(new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3")));
        Files.writeString(testA.resolve("classes.jar"), "foo-test");

        Path testB = Files.createDirectory(source.resolve("module-bar-test"));
        writeTestModule(testB, "bar", "com.example", "bar.test", "1.2.3",
                List.of(new Dep("org.assertj", "assertj-core", "3.27.0")));
        Files.writeString(testB.resolve("classes.jar"), "bar-test");

        run(source, "module-foo", "module-bar", "module-foo-test", "module-bar-test");

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
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "main");

        Path testA = Files.createDirectory(source.resolve("module-foo-test-a"));
        writeTestModule(testA, "foo", "com.example", "foo.test.a", "1.2.3", List.of());
        Files.writeString(testA.resolve("classes.jar"), "test-a");

        Path testB = Files.createDirectory(source.resolve("module-foo-test-b"));
        writeTestModule(testB, "foo", "com.example", "foo.test.b", "1.2.3", List.of());
        Files.writeString(testB.resolve("classes.jar"), "test-b");

        assertThatThrownBy(
                        () -> run(source, "module-foo", "module-foo-test-a", "module-foo-test-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple test modules declare main 'foo'")
                .hasMessageContaining("'-tests' classifier")
                .hasMessageContaining("module-foo-test-a")
                .hasMessageContaining("module-foo-test-b");
    }

    @Test
    public void test_referencing_unknown_parent_fails_loudly() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "main");

        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(test, "typo", "com.example", "foo.test", "1.2.3", List.of());
        Files.writeString(test.resolve("classes.jar"), "test");

        assertThatThrownBy(
                        () -> run(source, "module-foo", "module-foo-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test module 'module-foo-test'")
                .hasMessageContaining("references unknown main 'typo'")
                .hasMessageContaining("[foo]");
    }

    @Test
    public void bare_test_with_no_main_present_fails_loudly() throws IOException {
        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(test, "", "com.example", "foo.test", "1.2.3", List.of());
        Files.writeString(test.resolve("classes.jar"), "test");

        assertThatThrownBy(
                        () -> run(source, "module-foo-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test module 'module-foo-test'")
                .hasMessageContaining("declares no parent")
                .hasMessageContaining("no main module is present");
    }

    @Test
    public void default_does_not_stage_test_artifacts() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "main");

        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(test, "foo", "com.example", "foo.test", "1.2.3", List.of());
        Files.writeString(test.resolve("classes.jar"), "test-classes");
        Files.writeString(test.resolve("sources.jar"), "test-sources");
        Files.writeString(test.resolve("javadoc.jar"), "test-javadoc");

        run(false, source, "module-foo", "module-foo-test");

        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).exists();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests.jar")).doesNotExist();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests-sources.jar")).doesNotExist();
        assertThat(next.resolve("com/example/foo/1.2.3/foo-1.2.3-tests-javadoc.jar")).doesNotExist();
    }

    @Test
    public void default_does_not_merge_test_dependencies_into_main_pom() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeMainModule(main, "com.example", "foo", "1.2.3");
        Files.writeString(main.resolve("classes.jar"), "main");

        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeTestModule(test, "foo",
                "com.example", "foo.test", "1.2.3",
                List.of(new Dep("org.junit.jupiter", "junit-jupiter", "5.11.3")));
        Files.writeString(test.resolve("classes.jar"), "test");

        run(false, source, "module-foo", "module-foo-test");

        String pom = Files.readString(next.resolve("com/example/foo/1.2.3/foo-1.2.3.pom"));
        assertThat(pom).doesNotContain("junit-jupiter");
        assertThat(pom).doesNotContain("<scope>test</scope>");
    }

    @Test
    public void modules_without_metadata_are_skipped() throws IOException {
        Path stray = Files.createDirectory(source.resolve("module-stray"));
        Files.writeString(stray.resolve("classes.jar"), "stray");

        BuildStepResult result = run(source, "module-stray");
        assertThat(result.next()).isTrue();
        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private BuildStepResult run(Path folder, String... moduleDirs) throws IOException {
        return run(true, folder, moduleDirs);
    }

    private BuildStepResult run(boolean includeTests, Path folder, String... moduleDirs) throws IOException {
        Map<Path, ChecksumStatus> checksums = new LinkedHashMap<>();
        for (String moduleDir : moduleDirs) {
            try (Stream<Path> stream = Files.list(folder.resolve(moduleDir))) {
                stream.forEach(file -> checksums.put(
                        Path.of(moduleDir, file.getFileName().toString()),
                        ChecksumStatus.ADDED));
            }
        }
        return new MavenRepositoryStage(includeTests).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(folder, checksums))))
                .toCompletableFuture()
                .join();
    }

    private static void writeMainModule(Path moduleDir,
                                        String groupId,
                                        String artifactId,
                                        String version) throws IOException {
        Files.writeString(moduleDir.resolve("metadata.properties"), "");
        Files.writeString(moduleDir.resolve("pom.xml"), buildPom(groupId, artifactId, version, List.of()));
    }

    private static void writeTestModule(Path moduleDir,
                                        String parentArtifactId,
                                        String groupId,
                                        String artifactId,
                                        String version,
                                        List<Dep> deps) throws IOException {
        Files.writeString(moduleDir.resolve("metadata.properties"),
                "project.test=" + parentArtifactId + "\n");
        Files.writeString(moduleDir.resolve("pom.xml"), buildPom(groupId, artifactId, version, deps));
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
