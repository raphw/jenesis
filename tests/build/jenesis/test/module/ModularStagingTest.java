package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.module.ModularStaging;

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
        Path module = Files.createDirectory(source.resolve("module-build.jenesis"));
        writeModule(module, "build.jenesis", null, null);
        Files.writeString(module.resolve("classes.jar"), "classes-bytes");
        Files.writeString(module.resolve("sources.jar"), "sources-bytes");
        Files.writeString(module.resolve("javadoc.jar"), "javadoc-bytes");

        BuildStepResult result = run(source, "module-build.jenesis");

        assertThat(result.next()).isTrue();
        assertThat(next.resolve("build.jenesis/build.jenesis.jar")).hasContent("classes-bytes");
        assertThat(next.resolve("build.jenesis/build.jenesis-sources.jar")).hasContent("sources-bytes");
        assertThat(next.resolve("build.jenesis/build.jenesis-javadoc.jar")).hasContent("javadoc-bytes");
    }

    @Test
    public void inserts_version_segment_when_metadata_version_is_set() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-build.jenesis"));
        writeModule(module, "build.jenesis", null, "1.0.0");
        Files.writeString(module.resolve("classes.jar"), "c");
        Files.writeString(module.resolve("sources.jar"), "s");
        Files.writeString(module.resolve("javadoc.jar"), "j");

        run(source, "module-build.jenesis");

        assertThat(next.resolve("build.jenesis/1.0.0/build.jenesis.jar")).hasContent("c");
        assertThat(next.resolve("build.jenesis/1.0.0/build.jenesis-sources.jar")).hasContent("s");
        assertThat(next.resolve("build.jenesis/1.0.0/build.jenesis-javadoc.jar")).hasContent("j");
    }

    @Test
    public void preserves_each_module_as_its_own_directory() throws IOException {
        Path moduleA = Files.createDirectory(source.resolve("module-com.example.foo"));
        writeModule(moduleA, "com.example.foo", null, null);
        Files.writeString(moduleA.resolve("classes.jar"), "foo-bytes");

        Path moduleB = Files.createDirectory(source.resolve("module-com.example.bar"));
        writeModule(moduleB, "com.example.bar", null, null);
        Files.writeString(moduleB.resolve("classes.jar"), "bar-bytes");

        run(source, "module-com.example.foo", "module-com.example.bar");

        assertThat(next.resolve("com.example.foo/com.example.foo.jar")).hasContent("foo-bytes");
        assertThat(next.resolve("com.example.bar/com.example.bar.jar")).hasContent("bar-bytes");
    }

    @Test
    public void default_omits_test_modules() throws IOException {
        Path main = Files.createDirectory(source.resolve("module-foo"));
        writeModule(main, "foo", null, null);
        Files.writeString(main.resolve("classes.jar"), "main");

        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeModule(test, "foo.test", "foo", null);
        Files.writeString(test.resolve("classes.jar"), "test-classes");
        Files.writeString(test.resolve("sources.jar"), "test-sources");
        Files.writeString(test.resolve("javadoc.jar"), "test-javadoc");

        run(source, "module-foo", "module-foo-test");

        assertThat(next.resolve("foo/foo.jar")).hasContent("main");
        assertThat(next.resolve("foo.test")).doesNotExist();
    }

    @Test
    public void include_tests_emits_test_module_files_under_their_module_name() throws IOException {
        Path test = Files.createDirectory(source.resolve("module-foo-test"));
        writeModule(test, "foo.test", "foo", null);
        Files.writeString(test.resolve("classes.jar"), "test-classes");
        Files.writeString(test.resolve("sources.jar"), "test-sources");
        Files.writeString(test.resolve("javadoc.jar"), "test-javadoc");

        run(true, source, "module-foo-test");

        assertThat(next.resolve("foo.test/foo.test.jar")).hasContent("test-classes");
        assertThat(next.resolve("foo.test/foo.test-sources.jar")).hasContent("test-sources");
        assertThat(next.resolve("foo.test/foo.test-javadoc.jar")).hasContent("test-javadoc");
    }

    @Test
    public void modules_without_module_properties_are_skipped() throws IOException {
        Path stray = Files.createDirectory(source.resolve("module-stray"));
        Files.writeString(stray.resolve("classes.jar"), "stray");

        BuildStepResult result = run(source, "module-stray");

        assertThat(result.next()).isTrue();
        try (Stream<Path> stream = Files.walk(next)) {
            assertThat(stream.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    public void module_property_missing_throws() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-foo"));
        Files.writeString(module.resolve(BuildStep.MODULE), "");
        Files.writeString(module.resolve("classes.jar"), "c");

        assertThatThrownBy(() -> run(source, "module-foo"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing 'module' property")
                .hasMessageContaining("module-foo");
    }

    @Test
    public void only_existing_artifacts_are_linked() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-foo"));
        writeModule(module, "foo", null, null);
        Files.writeString(module.resolve("classes.jar"), "c");

        run(source, "module-foo");

        assertThat(next.resolve("foo/foo.jar")).hasContent("c");
        assertThat(next.resolve("foo/foo-sources.jar")).doesNotExist();
        assertThat(next.resolve("foo/foo-javadoc.jar")).doesNotExist();
    }

    private BuildStepResult run(Path folder, String... moduleDirs) throws IOException {
        return run(false, folder, moduleDirs);
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
        return new ModularStaging(includeTests).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(folder, checksums))))
                .toCompletableFuture()
                .join();
    }

    private static void writeModule(Path moduleDir,
                                    String moduleName,
                                    String tests,
                                    String version) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("module=").append(moduleName).append('\n');
        if (tests != null) {
            builder.append("tests=").append(tests).append('\n');
        }
        Files.writeString(moduleDir.resolve(BuildStep.MODULE), builder.toString());
        if (version != null) {
            Files.writeString(moduleDir.resolve(BuildStep.METADATA), "version=" + version + "\n");
        }
    }
}
