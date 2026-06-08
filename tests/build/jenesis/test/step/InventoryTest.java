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
        module.setProperty("modular", "true");
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
        Path lib = Files.writeString(runtimeDeps.resolve("maven-org.example-lib-1.0.jar"), "library");
        SequencedProperties runtimeIndex = new SequencedProperties();
        runtimeIndex.setProperty("runtime/maven/org.example/lib/1.0", "dependencies/maven-org.example-lib-1.0.jar");
        runtimeIndex.store(runtime.resolve(BuildStep.DEPENDENCIES));

        BuildStepResult result = run(args("manifests", manifests, "produce", produce, "runtime", runtime));

        assertThat(result.next()).isTrue();
        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module-foo.mainClass")).isEqualTo("com.example.Foo");
        assertThat(inventory.getProperty("module-foo.module")).isEqualTo("com.example.foo");
        assertThat(inventory.getProperty("module-foo.version")).isEqualTo("1.2.3");
        assertThat(inventory.getProperty("module-foo.artifacts.0")).isEqualTo(relativize(classes));
        assertThat(inventory.getProperty("module-foo.sources.0")).isEqualTo(relativize(sources));
        assertThat(inventory.getProperty("module-foo.documentation.0")).isEqualTo(relativize(javadoc));
        assertThat(inventory.getProperty("module-foo.pom")).isEqualTo(relativize(pom));
        assertThat(inventory.getProperty("module-foo.runtime.0")).isEqualTo(relativize(classes));
        assertThat(inventory.getProperty("module-foo.runtime.1")).isEqualTo(relativize(lib));
        assertThat(inventory.getProperty("module-foo.dependency.0")).startsWith("maven/org.example/lib/1.0 ");
        assertThat(inventory.getProperty("module-foo.dependency.0.scope")).isEqualTo("runtime");
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
    public void records_package_image_when_present() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path produce = Files.createDirectory(root.resolve("produce"));
        Path packages = produce.resolve("packages");
        Files.createDirectories(packages.resolve("app").resolve("bin"));
        Files.writeString(packages.resolve("app").resolve("bin").resolve("app"), "launcher");

        run(args("manifests", manifests, "produce", produce));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module.package")).isEqualTo(relativize(packages));
    }

    @Test
    public void records_jmod_and_runtime_image_when_present() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path produce = Files.createDirectory(root.resolve("produce"));
        Path jmod = Files.writeString(
                Files.createDirectory(produce.resolve("jmods")).resolve("demo.foo.jmod"), "jmod");
        Path runtime = produce.resolve("runtime");
        Files.createDirectories(runtime.resolve("bin"));
        Files.writeString(runtime.resolve("release"), "JAVA_VERSION=25");

        run(args("manifests", manifests, "produce", produce));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module.jmod.0")).isEqualTo(relativize(jmod));
        assertThat(inventory.getProperty("module.image")).isEqualTo(relativize(runtime));
    }

    @Test
    public void records_test_reports_when_present() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        SequencedProperties module = new SequencedProperties();
        module.setProperty("path", "foo");
        module.store(manifests.resolve(BuildStep.MODULE));
        Path produce = Files.createDirectory(root.resolve("produce"));
        Path reports = Files.createDirectory(produce.resolve("testreport"));
        Path first = Files.writeString(reports.resolve("junit-platform-events-1.xml"), "<events/>");
        Path second = Files.writeString(reports.resolve("junit-platform-events-2.xml"), "<events/>");

        run(args("manifests", manifests, "produce", produce));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module-foo.testreport.0")).isEqualTo(relativize(first));
        assertThat(inventory.getProperty("module-foo.testreport.1")).isEqualTo(relativize(second));
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
        Path libA = Files.writeString(firstDir.resolve("maven-org.example-a-1.0.jar"), "a");
        SequencedProperties firstIndex = new SequencedProperties();
        firstIndex.setProperty("runtime/maven/org.example/a/1.0", "dependencies/maven-org.example-a-1.0.jar");
        firstIndex.store(firstDeps.resolve(BuildStep.DEPENDENCIES));
        Path secondDeps = Files.createDirectory(root.resolve("second"));
        Path secondDir = Files.createDirectory(secondDeps.resolve("dependencies"));
        Path libB = Files.writeString(secondDir.resolve("maven-org.example-b-1.0.jar"), "b");
        SequencedProperties secondIndex = new SequencedProperties();
        secondIndex.setProperty("runtime/maven/org.example/b/1.0", "dependencies/maven-org.example-b-1.0.jar");
        secondIndex.store(secondDeps.resolve(BuildStep.DEPENDENCIES));

        run(args("manifests", manifests, "first", firstDeps, "second", secondDeps));

        SequencedProperties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("module-foo.runtime.0")).isEqualTo(relativize(libA));
        assertThat(inventory.getProperty("module-foo.runtime.1")).isEqualTo(relativize(libB));
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
