package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
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
        Properties module = new SequencedProperties();
        module.setProperty("path", "foo");
        module.setProperty("main", "com.example.Foo");
        module.setProperty("module", "com.example.foo");
        try (Writer writer = Files.newBufferedWriter(manifests.resolve(BuildStep.MODULE))) {
            module.store(writer, null);
        }
        Path assign = Files.createDirectory(root.resolve("assign"));
        Path artifact = Files.writeString(assign.resolve("classes.jar"), "main");
        Properties identity = new SequencedProperties();
        identity.setProperty("foo/coord", assign.relativize(artifact).toString().replace(File.separatorChar, '/'));
        try (Writer writer = Files.newBufferedWriter(assign.resolve(BuildStep.IDENTITY))) {
            identity.store(writer, null);
        }
        Path runtime = Files.createDirectory(root.resolve("runtime"));
        Path artifactsDir = Files.createDirectory(runtime.resolve("artifacts"));
        Files.writeString(artifactsDir.resolve("lib.jar"), "library");
        BuildStepResult result = run(args("manifests", manifests, "assign", assign, "runtime", runtime));
        assertThat(result.next()).isTrue();
        Properties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory).containsOnlyKeys("foo.runtime", "foo.mainClass", "foo.module");
        assertThat(inventory.getProperty("foo.mainClass")).isEqualTo("com.example.Foo");
        assertThat(inventory.getProperty("foo.module")).isEqualTo("com.example.foo");
        assertThat(inventory.getProperty("foo.runtime").split(",")).containsExactly(
                next.relativize(artifact).toString().replace(File.separatorChar, '/'),
                next.relativize(artifactsDir.resolve("lib.jar")).toString().replace(File.separatorChar, '/'));
    }

    @Test
    public void omits_main_class_when_absent() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        Properties module = new SequencedProperties();
        module.setProperty("path", "foo");
        try (Writer writer = Files.newBufferedWriter(manifests.resolve(BuildStep.MODULE))) {
            module.store(writer, null);
        }
        Path assign = Files.createDirectory(root.resolve("assign"));
        Path artifact = Files.writeString(assign.resolve("classes.jar"), "main");
        Properties identity = new SequencedProperties();
        identity.setProperty("foo/coord", assign.relativize(artifact).toString().replace(File.separatorChar, '/'));
        try (Writer writer = Files.newBufferedWriter(assign.resolve(BuildStep.IDENTITY))) {
            identity.store(writer, null);
        }
        run(args("manifests", manifests, "assign", assign));
        Properties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory).containsOnlyKeys("foo.runtime");
        assertThat(inventory.stringPropertyNames()).doesNotContain("foo.mainClass", "foo.module");
    }

    @Test
    public void omits_module_when_absent() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        Properties module = new SequencedProperties();
        module.setProperty("path", "foo");
        module.setProperty("main", "com.example.Foo");
        try (Writer writer = Files.newBufferedWriter(manifests.resolve(BuildStep.MODULE))) {
            module.store(writer, null);
        }
        Path assign = Files.createDirectory(root.resolve("assign"));
        Path artifact = Files.writeString(assign.resolve("classes.jar"), "main");
        Properties identity = new SequencedProperties();
        identity.setProperty("foo/coord", assign.relativize(artifact).toString().replace(File.separatorChar, '/'));
        try (Writer writer = Files.newBufferedWriter(assign.resolve(BuildStep.IDENTITY))) {
            identity.store(writer, null);
        }
        run(args("manifests", manifests, "assign", assign));
        Properties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory).containsOnlyKeys("foo.runtime", "foo.mainClass");
        assertThat(inventory.stringPropertyNames()).doesNotContain("foo.module");
    }

    @Test
    public void emits_unprefixed_keys_for_root_module() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        Properties module = new SequencedProperties();
        module.setProperty("path", "");
        module.setProperty("main", "com.example.Root");
        try (Writer writer = Files.newBufferedWriter(manifests.resolve(BuildStep.MODULE))) {
            module.store(writer, null);
        }
        Path assign = Files.createDirectory(root.resolve("assign"));
        Path artifact = Files.writeString(assign.resolve("classes.jar"), "main");
        Properties identity = new SequencedProperties();
        identity.setProperty("/coord", assign.relativize(artifact).toString().replace(File.separatorChar, '/'));
        try (Writer writer = Files.newBufferedWriter(assign.resolve(BuildStep.IDENTITY))) {
            identity.store(writer, null);
        }
        run(args("manifests", manifests, "assign", assign));
        Properties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory).containsOnlyKeys("runtime", "mainClass");
        assertThat(inventory.getProperty("mainClass")).isEqualTo("com.example.Root");
    }

    @Test
    public void picks_first_complete_identity_skipping_placeholder() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        Properties module = new SequencedProperties();
        module.setProperty("path", "foo");
        try (Writer writer = Files.newBufferedWriter(manifests.resolve(BuildStep.MODULE))) {
            module.store(writer, null);
        }
        Path placeholder = Files.createDirectory(root.resolve("coordinates"));
        Properties placeholderIdentity = new SequencedProperties();
        placeholderIdentity.setProperty("foo/coord", "");
        try (Writer writer = Files.newBufferedWriter(placeholder.resolve(BuildStep.IDENTITY))) {
            placeholderIdentity.store(writer, null);
        }
        Path assign = Files.createDirectory(root.resolve("assign"));
        Path artifact = Files.writeString(assign.resolve("classes.jar"), "main");
        Properties identity = new SequencedProperties();
        identity.setProperty("foo/coord", assign.relativize(artifact).toString().replace(File.separatorChar, '/'));
        try (Writer writer = Files.newBufferedWriter(assign.resolve(BuildStep.IDENTITY))) {
            identity.store(writer, null);
        }
        run(args("manifests", manifests, "coordinates", placeholder, "assign", assign));
        Properties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("foo.runtime"))
                .isEqualTo(next.relativize(artifact).toString().replace(File.separatorChar, '/'));
    }

    @Test
    public void combines_artifacts_from_multiple_dirs() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        Properties module = new SequencedProperties();
        module.setProperty("path", "foo");
        try (Writer writer = Files.newBufferedWriter(manifests.resolve(BuildStep.MODULE))) {
            module.store(writer, null);
        }
        Path firstDeps = Files.createDirectory(root.resolve("first"));
        Path firstArtifacts = Files.createDirectory(firstDeps.resolve("artifacts"));
        Files.writeString(firstArtifacts.resolve("a.jar"), "a");
        Path secondDeps = Files.createDirectory(root.resolve("second"));
        Path secondArtifacts = Files.createDirectory(secondDeps.resolve("artifacts"));
        Files.writeString(secondArtifacts.resolve("b.jar"), "b");
        run(args("manifests", manifests, "first", firstDeps, "second", secondDeps));
        Properties inventory = read(next.resolve(Inventory.INVENTORY));
        assertThat(inventory.getProperty("foo.runtime").split(",")).containsExactly(
                next.relativize(firstArtifacts.resolve("a.jar")).toString().replace(File.separatorChar, '/'),
                next.relativize(secondArtifacts.resolve("b.jar")).toString().replace(File.separatorChar, '/'));
    }

    @Test
    public void skips_writing_when_no_data() throws IOException {
        Path empty = Files.createDirectory(root.resolve("empty"));
        BuildStepResult result = run(args("empty", empty));
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Inventory.INVENTORY)).doesNotExist();
    }

    @Test
    public void skips_identity_when_main_artifact_missing() throws IOException {
        Path manifests = Files.createDirectory(root.resolve("manifests"));
        Properties module = new SequencedProperties();
        module.setProperty("path", "foo");
        try (Writer writer = Files.newBufferedWriter(manifests.resolve(BuildStep.MODULE))) {
            module.store(writer, null);
        }
        Path assign = Files.createDirectory(root.resolve("assign"));
        Properties identity = new SequencedProperties();
        identity.setProperty("foo/coord", "missing.jar");
        try (Writer writer = Files.newBufferedWriter(assign.resolve(BuildStep.IDENTITY))) {
            identity.store(writer, null);
        }
        run(args("manifests", manifests, "assign", assign));
        assertThat(next.resolve(Inventory.INVENTORY)).doesNotExist();
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

    private static Properties read(Path file) throws IOException {
        Properties properties = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        return properties;
    }
}
