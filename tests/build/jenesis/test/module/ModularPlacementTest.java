package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.module.ModularPlacement;
import build.jenesis.step.Relocate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ModularPlacementTest {

    @TempDir
    private Path root;

    private final ModularPlacement layout = new ModularPlacement();

    private static SequencedProperties properties(String... pairs) {
        SequencedProperties properties = new SequencedProperties();
        for (int i = 0; i < pairs.length; i += 2) {
            properties.setProperty(pairs[i], pairs[i + 1]);
        }
        return properties;
    }

    @Test
    public void maps_classes_jar_to_module_named_jar() throws IOException {
        assertThat(layout.apply(Path.of("module-sources/classes.jar"),
                properties("module", "build.jenesis"),
                properties()))
                .contains(Path.of("build.jenesis/build.jenesis.jar"));
    }

    @Test
    public void maps_sources_jar_to_module_named_sources_jar() throws IOException {
        assertThat(layout.apply(Path.of("module-sources/sources.jar"),
                properties("module", "build.jenesis"),
                properties()))
                .contains(Path.of("build.jenesis/build.jenesis-sources.jar"));
    }

    @Test
    public void maps_javadoc_jar_to_module_named_javadoc_jar() throws IOException {
        assertThat(layout.apply(Path.of("module-sources/javadoc.jar"),
                properties("module", "build.jenesis"),
                properties()))
                .contains(Path.of("build.jenesis/build.jenesis-javadoc.jar"));
    }

    @Test
    public void returns_empty_for_pom_xml() throws IOException {
        assertThat(layout.apply(Path.of("build.jenesis/pom.xml"), properties(), properties())).isEmpty();
    }

    @Test
    public void returns_empty_for_unknown_filenames() throws IOException {
        assertThat(layout.apply(Path.of("build.jenesis/readme.txt"), properties(), properties())).isEmpty();
        assertThat(layout.apply(Path.of("build.jenesis/random.dat"), properties(), properties())).isEmpty();
    }

    @Test
    public void throws_when_module_property_is_missing() {
        assertThatThrownBy(() -> layout.apply(Path.of("module-sources/classes.jar"), properties(), properties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing 'module' property");
    }

    @Test
    public void filters_out_module_properties_itself_so_it_is_not_staged() throws IOException {
        assertThat(layout.apply(Path.of("build.jenesis/module.properties"), properties(), properties())).isEmpty();
    }

    @Test
    public void inserts_version_segment_when_metadata_version_is_set() throws IOException {
        SequencedProperties module = properties("module", "build.jenesis");
        SequencedProperties metadata = properties("version", "1.0.0");
        assertThat(layout.apply(Path.of("module-sources/classes.jar"), module, metadata))
                .contains(Path.of("build.jenesis/1.0.0/build.jenesis.jar"));
        assertThat(layout.apply(Path.of("module-sources/sources.jar"), module, metadata))
                .contains(Path.of("build.jenesis/1.0.0/build.jenesis-sources.jar"));
        assertThat(layout.apply(Path.of("module-sources/javadoc.jar"), module, metadata))
                .contains(Path.of("build.jenesis/1.0.0/build.jenesis-javadoc.jar"));
    }

    @Test
    public void missing_metadata_version_omits_version_segment() throws IOException {
        assertThat(layout.apply(Path.of("module-sources/classes.jar"),
                properties("module", "build.jenesis"),
                properties()))
                .contains(Path.of("build.jenesis/build.jenesis.jar"));
    }

    @Test
    public void default_omits_files_from_test_modules() throws IOException {
        SequencedProperties module = properties("module", "foo.test", "tests", "foo");
        assertThat(layout.apply(Path.of("module-test/classes.jar"), module, properties())).isEmpty();
        assertThat(layout.apply(Path.of("module-test/sources.jar"), module, properties())).isEmpty();
        assertThat(layout.apply(Path.of("module-test/javadoc.jar"), module, properties())).isEmpty();
    }

    @Test
    public void include_tests_emits_test_module_files_under_their_module_name() throws IOException {
        ModularPlacement layoutWithTests = new ModularPlacement(true);
        SequencedProperties module = properties("module", "foo.test", "tests", "foo");
        assertThat(layoutWithTests.apply(Path.of("module-test/classes.jar"), module, properties()))
                .contains(Path.of("foo.test/foo.test.jar"));
        assertThat(layoutWithTests.apply(Path.of("module-test/sources.jar"), module, properties()))
                .contains(Path.of("foo.test/foo.test-sources.jar"));
        assertThat(layoutWithTests.apply(Path.of("module-test/javadoc.jar"), module, properties()))
                .contains(Path.of("foo.test/foo.test-javadoc.jar"));
    }

    @Test
    public void layout_is_serializable() throws IOException, ClassNotFoundException {
        byte[] bytes;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(layout);
            bytes = out.toByteArray();
        }
        Object restored;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            restored = ois.readObject();
        }
        assertThat(restored).isInstanceOf(ModularPlacement.class);
        assertThat(((ModularPlacement) restored).apply(Path.of("module-sources/classes.jar"),
                properties("module", "build.jenesis"),
                properties()))
                .contains(Path.of("build.jenesis/build.jenesis.jar"));
    }

    @Test
    public void relocate_emits_module_named_artifacts_under_module_directory() throws IOException {
        Path source = Files.createDirectory(root.resolve("source"));
        Path module = Files.createDirectory(source.resolve("build.jenesis"));
        Files.writeString(module.resolve("classes.jar"), "classes-bytes");
        Files.writeString(module.resolve("sources.jar"), "sources-bytes");
        Files.writeString(module.resolve("javadoc.jar"), "javadoc-bytes");
        Files.writeString(module.resolve("readme.txt"), "ignored");
        Files.writeString(module.resolve(BuildStep.MODULE), "module=build.jenesis\n");

        Path next = Files.createDirectory(root.resolve("next"));
        Path supplement = Files.createDirectory(root.resolve("supplement"));
        Path previous = root.resolve("previous");

        BuildStepResult result = new Relocate(layout).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("build.jenesis/classes.jar"), ChecksumStatus.ADDED,
                                        Path.of("build.jenesis/sources.jar"), ChecksumStatus.ADDED,
                                        Path.of("build.jenesis/javadoc.jar"), ChecksumStatus.ADDED,
                                        Path.of("build.jenesis/readme.txt"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();

        assertThat(result.next()).isTrue();
        assertThat(next.resolve("build.jenesis/build.jenesis.jar")).hasContent("classes-bytes");
        assertThat(next.resolve("build.jenesis/build.jenesis-sources.jar")).hasContent("sources-bytes");
        assertThat(next.resolve("build.jenesis/build.jenesis-javadoc.jar")).hasContent("javadoc-bytes");
        assertThat(next.resolve("build.jenesis/readme.txt")).doesNotExist();
    }

    @Test
    public void relocate_preserves_each_module_as_its_own_directory() throws IOException {
        Path source = Files.createDirectory(root.resolve("source"));
        Path moduleA = Files.createDirectory(source.resolve("com.example.foo"));
        Files.writeString(moduleA.resolve("classes.jar"), "foo-bytes");
        Files.writeString(moduleA.resolve(BuildStep.MODULE), "module=com.example.foo\n");
        Path moduleB = Files.createDirectory(source.resolve("com.example.bar"));
        Files.writeString(moduleB.resolve("classes.jar"), "bar-bytes");
        Files.writeString(moduleB.resolve(BuildStep.MODULE), "module=com.example.bar\n");

        Path next = Files.createDirectory(root.resolve("next"));
        Path supplement = Files.createDirectory(root.resolve("supplement"));
        Path previous = root.resolve("previous");

        new Relocate(layout).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("com.example.foo/classes.jar"), ChecksumStatus.ADDED,
                                        Path.of("com.example.bar/classes.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();

        assertThat(next.resolve("com.example.foo/com.example.foo.jar")).hasContent("foo-bytes");
        assertThat(next.resolve("com.example.bar/com.example.bar.jar")).hasContent("bar-bytes");
    }
}
