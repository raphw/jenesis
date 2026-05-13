package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.module.ModularLayout;
import build.jenesis.step.Relocate;

import static org.assertj.core.api.Assertions.assertThat;

public class ModularLayoutTest {

    @TempDir
    private Path root;

    private final ModularLayout layout = new ModularLayout();

    @Test
    public void maps_classes_jar_to_module_named_jar() {
        assertThat(layout.apply(Path.of("build.jenesis/classes.jar")))
                .contains(Path.of("build.jenesis/build.jenesis.jar"));
    }

    @Test
    public void maps_sources_jar_to_module_named_sources_jar() {
        assertThat(layout.apply(Path.of("build.jenesis/sources.jar")))
                .contains(Path.of("build.jenesis/build.jenesis-sources.jar"));
    }

    @Test
    public void maps_javadoc_jar_to_module_named_javadoc_jar() {
        assertThat(layout.apply(Path.of("build.jenesis/javadoc.jar")))
                .contains(Path.of("build.jenesis/build.jenesis-javadoc.jar"));
    }

    @Test
    public void returns_empty_for_pom_xml() {
        assertThat(layout.apply(Path.of("build.jenesis/pom.xml"))).isEmpty();
    }

    @Test
    public void returns_empty_for_unknown_filenames() {
        assertThat(layout.apply(Path.of("build.jenesis/readme.txt"))).isEmpty();
        assertThat(layout.apply(Path.of("build.jenesis/random.dat"))).isEmpty();
    }

    @Test
    public void returns_empty_when_file_has_no_parent_directory() {
        assertThat(layout.apply(Path.of("classes.jar"))).isEmpty();
    }

    @Test
    public void uses_immediate_parent_as_module_name() {
        assertThat(layout.apply(Path.of("collect/output/com.example.foo/classes.jar")))
                .contains(Path.of("com.example.foo/com.example.foo.jar"));
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
        assertThat(restored).isInstanceOf(ModularLayout.class);
        assertThat(((ModularLayout) restored).apply(Path.of("build.jenesis/classes.jar")))
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
        Path moduleB = Files.createDirectory(source.resolve("com.example.bar"));
        Files.writeString(moduleB.resolve("classes.jar"), "bar-bytes");

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
