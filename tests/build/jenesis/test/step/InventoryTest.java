package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Inventory;

import static org.assertj.core.api.Assertions.assertThat;

public class InventoryTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement;

    @BeforeEach
    public void setUp() throws IOException {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
    }

    @Test
    public void writes_all_fields_when_present() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "foo");
        module.setProperty("main", "com.example.Foo");
        module.setProperty("module", "com.example.foo");
        module.store(manifests.resolve(BuildStep.MODULE));
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("version", "1.2.3");
        metadata.store(manifests.resolve(BuildStep.METADATA));
        Path produce = Files.createDirectory(root.resolve("produce"));
        Path produceArtifacts = Files.createDirectory(produce.resolve("artifacts"));
        Path classes = Files.writeString(produceArtifacts.resolve("classes.jar"), "main");
        Path sources = Files.writeString(
                Files.createDirectory(produce.resolve("sources")).resolve("sources.jar"), "src");
        Path javadoc = Files.writeString(
                Files.createDirectory(produce.resolve("documentation")).resolve("javadoc.jar"), "doc");
        Path pom = Files.writeString(produce.resolve("pom.xml"), "<project/>");
        Path runtime = Files.createDirectory(root.resolve("runtime"));
        Path runtimeDeps = Files.createDirectory(runtime.resolve("dependencies"));
        Path lib = Files.writeString(runtimeDeps.resolve("lib.jar"), "library");

        BuildStepResult result = run(args("manifests", manifests, "produce", produce, "runtime", runtime));

        assertThat(result.next()).isTrue();
        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module-foo.mainClass")).isEqualTo("com.example.Foo");
        assertThat(inventory.getProperty("module-foo.module")).isEqualTo("com.example.foo");
        assertThat(inventory.getProperty("module-foo.version")).isEqualTo("1.2.3");
        assertThat(inventory.getProperty("module-foo.artifacts")).isEqualTo(relativize(classes));
        assertThat(inventory.getProperty("module-foo.sources")).isEqualTo(relativize(sources));
        assertThat(inventory.getProperty("module-foo.documentation")).isEqualTo(relativize(javadoc));
        assertThat(inventory.getProperty("module-foo.pom")).isEqualTo(relativize(pom));
        assertThat(inventory.getProperty("module-foo.runtime").split(",")).containsExactly(
                relativize(classes),
                relativize(lib));
    }

    @Test
    public void omits_main_class_when_absent() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "foo");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path produce = Files.createDirectory(root.resolve("produce"));
        Path produceArtifacts = Files.createDirectory(produce.resolve("artifacts"));
        Files.writeString(produceArtifacts.resolve("classes.jar"), "main");

        run(args("manifests", manifests, "produce", produce));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.stringPropertyNames())
                .doesNotContain("module-foo.mainClass", "module-foo.module", "module-foo.version", "module-foo.tests");
    }

    @Test
    public void omits_module_when_absent() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "foo");
        module.setProperty("main", "com.example.Foo");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path produce = Files.createDirectory(root.resolve("produce"));
        Path produceArtifacts = Files.createDirectory(produce.resolve("artifacts"));
        Files.writeString(produceArtifacts.resolve("classes.jar"), "main");

        run(args("manifests", manifests, "produce", produce));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module-foo.mainClass")).isEqualTo("com.example.Foo");
        assertThat(inventory.stringPropertyNames()).doesNotContain("module-foo.module");
    }

    @Test
    public void uses_bare_module_prefix_for_root_module() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "");
        module.setProperty("main", "com.example.Root");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path produce = Files.createDirectory(root.resolve("produce"));
        Path produceArtifacts = Files.createDirectory(produce.resolve("artifacts"));
        Files.writeString(produceArtifacts.resolve("classes.jar"), "main");

        run(args("manifests", manifests, "produce", produce));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module.mainClass")).isEqualTo("com.example.Root");
        assertThat(inventory.stringPropertyNames()).noneMatch(name -> name.startsWith("module-"));
    }

    @Test
    public void records_tests_marker_for_test_module() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "foo-test");
        module.setProperty("test", "foo");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path produce = Files.createDirectory(root.resolve("produce"));
        Path produceArtifacts = Files.createDirectory(produce.resolve("artifacts"));
        Files.writeString(produceArtifacts.resolve("classes.jar"), "test");

        run(args("manifests", manifests, "produce", produce));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module-foo-test.test")).isEqualTo("foo");
    }

    @Test
    public void records_pom_path_when_present() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "foo");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path pomFolder = Files.createDirectory(root.resolve("pom"));
        Path pom = Files.writeString(pomFolder.resolve("pom.xml"), "<project/>");

        run(args("manifests", manifests, "pom", pomFolder));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module-foo.pom")).isEqualTo(relativize(pom));
    }

    @Test
    public void combines_dependency_jars_from_multiple_dirs() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "foo");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path firstDeps = Files.createDirectory(root.resolve("first"));
        Path firstDir = Files.createDirectory(firstDeps.resolve("dependencies"));
        Path libA = Files.writeString(firstDir.resolve("a.jar"), "a");
        Path secondDeps = Files.createDirectory(root.resolve("second"));
        Path secondDir = Files.createDirectory(secondDeps.resolve("dependencies"));
        Path libB = Files.writeString(secondDir.resolve("b.jar"), "b");

        run(args("manifests", manifests, "first", firstDeps, "second", secondDeps));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module-foo.runtime").split(",")).containsExactly(
                relativize(libA),
                relativize(libB));
    }

    @Test
    public void skips_writing_when_no_data() throws IOException {
        Path empty = Files.createDirectory(root.resolve("empty"));
        BuildStepResult result = run(args("empty", empty));
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Inventory.INVENTORY)).doesNotExist();
    }

    private String relativize(Path file) {
        return next.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private BuildStepResult run(SequencedMap<String, Path> argumentFolders) throws IOException {
        SequencedMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : argumentFolders.entrySet()) {
            arguments.put(entry.getKey(), new BuildStepArgument(entry.getValue(), Map.of()));
        }
        return new Inventory().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
    }

    private static SequencedMap<String, Path> args(Object... pairs) {
        SequencedMap<String, Path> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], (Path) pairs[index + 1]);
        }
        return map;
    }

    private static SequencedProperties read(Path file) throws IOException {
        return SequencedProperties.ofFiles(file);
    }
}
