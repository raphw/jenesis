package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.module.ModularStaging;
import build.jenesis.step.Inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ModularStagingTest {

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
    public void stages_module_jars_under_module_directory() throws IOException {
        Path inv = inventory("jenesis", "build.jenesis", null, null,
                "classes.jar", "sources.jar", "javadoc.jar");
        writeArtifact(inv, "classes.jar", "classes-bytes");
        writeArtifact(inv, "sources.jar", "sources-bytes");
        writeArtifact(inv, "javadoc.jar", "javadoc-bytes");

        BuildStepResult result = run(false, inv);

        assertThat(result.next()).isTrue();
        assertThat(next.resolve("build.jenesis/build.jenesis.jar")).hasContent("classes-bytes");
        assertThat(next.resolve("build.jenesis/build.jenesis-sources.jar")).hasContent("sources-bytes");
        assertThat(next.resolve("build.jenesis/build.jenesis-javadoc.jar")).hasContent("javadoc-bytes");
    }

    @Test
    public void stages_jmod_alongside_module_jar() throws IOException {
        Path folder = Files.createDirectory(source.resolve("foo"));
        SequencedProperties inventory = new SequencedProperties();
        inventory.setProperty("module-foo.module", "demo.foo");
        inventory.setProperty("module-foo.artifacts.0", "artifacts/classes.jar");
        inventory.setProperty("module-foo.jmod.0", "jmods/demo.foo.jmod");
        inventory.store(folder.resolve(Inventory.INVENTORY));
        Files.writeString(Files.createDirectory(folder.resolve("artifacts")).resolve("classes.jar"), "jar");
        Files.writeString(Files.createDirectory(folder.resolve("jmods")).resolve("demo.foo.jmod"), "jmod");

        run(false, folder);

        assertThat(next.resolve("demo.foo/demo.foo.jar")).hasContent("jar");
        assertThat(next.resolve("demo.foo/demo.foo.jmod")).hasContent("jmod");
    }

    @Test
    public void stages_bom_as_module_properties() throws IOException {
        Path folder = Files.createDirectory(source.resolve("foo"));
        SequencedProperties inventory = new SequencedProperties();
        inventory.setProperty("module-foo.module", "demo.foo");
        inventory.setProperty("module-foo.version", "1.0.0");
        inventory.setProperty("module-foo.artifacts.0", "artifacts/classes.jar");
        inventory.setProperty("module-foo.bomfile.0", "bom/bom-demo.foo.properties");
        inventory.store(folder.resolve(Inventory.INVENTORY));
        Files.writeString(Files.createDirectory(folder.resolve("artifacts")).resolve("classes.jar"), "jar");
        Files.writeString(Files.createDirectory(folder.resolve("bom")).resolve("bom-demo.foo.properties"), "a = 1.0\n");

        run(false, folder);

        assertThat(next.resolve("demo.foo/1.0.0/demo.foo.jar")).hasContent("jar");
        assertThat(next.resolve("demo.foo/1.0.0/demo.foo.properties")).hasContent("a = 1.0\n");
    }

    @Test
    public void inserts_version_segment_when_inventory_version_is_set() throws IOException {
        Path inv = inventory("jenesis", "build.jenesis", null, "1.0.0",
                "classes.jar", "sources.jar", "javadoc.jar");
        writeArtifact(inv, "classes.jar", "c");
        writeArtifact(inv, "sources.jar", "s");
        writeArtifact(inv, "javadoc.jar", "j");

        run(false, inv);

        assertThat(next.resolve("build.jenesis/1.0.0/build.jenesis.jar")).hasContent("c");
        assertThat(next.resolve("build.jenesis/1.0.0/build.jenesis-sources.jar")).hasContent("s");
        assertThat(next.resolve("build.jenesis/1.0.0/build.jenesis-javadoc.jar")).hasContent("j");
    }

    @Test
    public void preserves_each_module_as_its_own_directory() throws IOException {
        Path foo = inventory("foo", "com.example.foo", null, null, "classes.jar");
        writeArtifact(foo, "classes.jar", "foo-bytes");
        Path bar = inventory("bar", "com.example.bar", null, null, "classes.jar");
        writeArtifact(bar, "classes.jar", "bar-bytes");

        run(false, foo, bar);

        assertThat(next.resolve("com.example.foo/com.example.foo.jar")).hasContent("foo-bytes");
        assertThat(next.resolve("com.example.bar/com.example.bar.jar")).hasContent("bar-bytes");
    }

    @Test
    public void default_omits_test_modules() throws IOException {
        Path main = inventory("foo", "foo", null, null, "classes.jar");
        writeArtifact(main, "classes.jar", "main");
        Path test = inventory("foo-test", "foo.test", "foo", null,
                "classes.jar", "sources.jar", "javadoc.jar");
        writeArtifact(test, "classes.jar", "test-classes");
        writeArtifact(test, "sources.jar", "test-sources");
        writeArtifact(test, "javadoc.jar", "test-javadoc");

        run(false, main, test);

        assertThat(next.resolve("foo/foo.jar")).hasContent("main");
        assertThat(next.resolve("foo.test")).doesNotExist();
    }

    @Test
    public void include_tests_emits_test_module_files_under_their_module_name() throws IOException {
        Path test = inventory("foo-test", "foo.test", "foo", null,
                "classes.jar", "sources.jar", "javadoc.jar");
        writeArtifact(test, "classes.jar", "test-classes");
        writeArtifact(test, "sources.jar", "test-sources");
        writeArtifact(test, "javadoc.jar", "test-javadoc");

        run(true, test);

        assertThat(next.resolve("foo.test/foo.test.jar")).hasContent("test-classes");
        assertThat(next.resolve("foo.test/foo.test-sources.jar")).hasContent("test-sources");
        assertThat(next.resolve("foo.test/foo.test-javadoc.jar")).hasContent("test-javadoc");
    }

    @Test
    public void arguments_without_inventory_are_skipped() throws IOException {
        Path stray = Files.createDirectory(source.resolve("stray"));
        writeArtifact(stray, "classes.jar", "stray");

        BuildStepResult result = run(false, stray);

        assertThat(result.next()).isTrue();
        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    public void inventory_without_module_property_is_skipped() throws IOException {
        Path folder = Files.createDirectory(source.resolve("foo"));
        SequencedProperties props = new SequencedProperties();
        props.setProperty("module.package", "packages");
        props.store(folder.resolve(Inventory.INVENTORY));
        Files.createDirectories(folder.resolve("packages").resolve("app"));

        BuildStepResult result = run(false, folder);

        assertThat(result.next()).isTrue();
        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    public void only_existing_artifacts_are_linked() throws IOException {
        Path inv = inventory("foo", "foo", null, null, "classes.jar", "sources.jar", "javadoc.jar");
        writeArtifact(inv, "classes.jar", "c");

        run(false, inv);

        assertThat(next.resolve("foo/foo.jar")).hasContent("c");
        assertThat(next.resolve("foo/foo-sources.jar")).doesNotExist();
        assertThat(next.resolve("foo/foo-javadoc.jar")).doesNotExist();
    }

    private Path inventory(String path,
                           String moduleName,
                           String testsOf,
                           String version,
                           String... artifactFiles) throws IOException {
        Path folder = Files.createDirectory(source.resolve(path));
        SequencedProperties inventory = new SequencedProperties();
        String prefix = "module-" + path;
        inventory.setProperty(prefix + ".module", moduleName);
        if (testsOf != null) {
            inventory.setProperty(prefix + ".test", testsOf);
        }
        if (version != null) {
            inventory.setProperty(prefix + ".version", version);
        }
        for (String artifactFile : artifactFiles) {
            switch (artifactFile) {
                case "classes.jar" -> inventory.setProperty(prefix + ".artifacts.0", "artifacts/" + artifactFile);
                case "sources.jar" -> inventory.setProperty(prefix + ".sources.0", "sources/" + artifactFile);
                case "javadoc.jar" -> inventory.setProperty(prefix + ".documentation.0", "documentation/" + artifactFile);
                default -> throw new IllegalArgumentException("Unknown artifact file: " + artifactFile);
            }
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
            Map<Path, Checksum> checksums = new LinkedHashMap<>();
            try (Stream<Path> stream = Files.list(folder)) {
                stream.forEach(file -> checksums.put(
                        Path.of(file.getFileName().toString()),
                        Checksum.of(ChecksumStatus.ADDED)));
            }
            arguments.put(folder.getFileName().toString(), new BuildStepArgument(folder, checksums));
        }
        return new ModularStaging(includeTests).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
    }
}
