package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.module.JenesisModuleRepositoryExport;

import static org.assertj.core.api.Assertions.assertThat;

public class JenesisModuleRepositoryExportTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, source, target;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        source = Files.createDirectory(root.resolve("source"));
        target = root.resolve("target");
    }

    @Test
    public void exports_unversioned_module_at_module_root() throws IOException {
        stage("build.jenesis", null, "build.jenesis.jar", "classes");

        BuildStepResult result = run();

        assertThat(result.next()).isTrue();
        assertThat(target.resolve("build.jenesis/build.jenesis.jar")).hasContent("classes");
    }

    @Test
    public void exports_versioned_module_under_version_directory_and_at_root() throws IOException {
        stage("build.jenesis", "1.0.0", "build.jenesis.jar", "classes");

        run();

        assertThat(target.resolve("build.jenesis/1.0.0/build.jenesis.jar")).hasContent("classes");
        assertThat(target.resolve("build.jenesis/build.jenesis.jar")).hasContent("classes");
    }

    @Test
    public void exports_sources_and_javadoc_jars_alongside_main() throws IOException {
        stage("build.jenesis", "1.0.0", "build.jenesis.jar", "classes");
        stage("build.jenesis", "1.0.0", "build.jenesis-sources.jar", "sources");
        stage("build.jenesis", "1.0.0", "build.jenesis-javadoc.jar", "javadoc");

        run();

        assertThat(target.resolve("build.jenesis/1.0.0/build.jenesis.jar")).hasContent("classes");
        assertThat(target.resolve("build.jenesis/1.0.0/build.jenesis-sources.jar")).hasContent("sources");
        assertThat(target.resolve("build.jenesis/1.0.0/build.jenesis-javadoc.jar")).hasContent("javadoc");
        assertThat(target.resolve("build.jenesis/build.jenesis.jar")).hasContent("classes");
        assertThat(target.resolve("build.jenesis/build.jenesis-sources.jar")).hasContent("sources");
        assertThat(target.resolve("build.jenesis/build.jenesis-javadoc.jar")).hasContent("javadoc");
    }

    @Test
    public void exports_multiple_modules_into_separate_directories() throws IOException {
        stage("com.example.foo", null, "com.example.foo.jar", "foo-bytes");
        stage("com.example.bar", "2.1", "com.example.bar.jar", "bar-bytes");

        run();

        assertThat(target.resolve("com.example.foo/com.example.foo.jar")).hasContent("foo-bytes");
        assertThat(target.resolve("com.example.bar/2.1/com.example.bar.jar")).hasContent("bar-bytes");
        assertThat(target.resolve("com.example.bar/com.example.bar.jar")).hasContent("bar-bytes");
    }

    @Test
    public void versioned_export_replaces_stale_root_mirror() throws IOException {
        Files.createDirectories(target.resolve("build.jenesis"));
        Files.writeString(target.resolve("build.jenesis/build.jenesis.jar"), "older");
        Files.writeString(target.resolve("build.jenesis/build.jenesis-javadoc.jar"), "older-javadoc");
        stage("build.jenesis", "2.0.0", "build.jenesis.jar", "newer");

        run();

        assertThat(target.resolve("build.jenesis/2.0.0/build.jenesis.jar")).hasContent("newer");
        assertThat(target.resolve("build.jenesis/build.jenesis.jar")).hasContent("newer");
        assertThat(target.resolve("build.jenesis/build.jenesis-javadoc.jar")).doesNotExist();
    }

    @Test
    public void overwrites_pre_existing_destination() throws IOException {
        Files.createDirectories(target.resolve("build.jenesis"));
        Files.writeString(target.resolve("build.jenesis/build.jenesis.jar"), "stale");
        stage("build.jenesis", null, "build.jenesis.jar", "fresh");

        run();

        assertThat(target.resolve("build.jenesis/build.jenesis.jar")).hasContent("fresh");
    }

    @Test
    public void deletes_stale_files_in_target_version_directory() throws IOException {
        Files.createDirectories(target.resolve("build.jenesis/1.0.0"));
        Files.writeString(target.resolve("build.jenesis/1.0.0/build.jenesis.jar"), "stale");
        Files.writeString(target.resolve("build.jenesis/1.0.0/build.jenesis-javadoc.jar"), "stale-javadoc");
        stage("build.jenesis", "1.0.0", "build.jenesis.jar", "fresh");

        run();

        assertThat(target.resolve("build.jenesis/1.0.0/build.jenesis.jar")).hasContent("fresh");
        assertThat(target.resolve("build.jenesis/1.0.0/build.jenesis-javadoc.jar")).doesNotExist();
    }

    @Test
    public void does_not_remove_unrelated_version_directories() throws IOException {
        Files.createDirectories(target.resolve("build.jenesis/0.9"));
        Files.writeString(target.resolve("build.jenesis/0.9/build.jenesis.jar"), "older");
        stage("build.jenesis", "1.0.0", "build.jenesis.jar", "fresh");

        run();

        assertThat(target.resolve("build.jenesis/1.0.0/build.jenesis.jar")).hasContent("fresh");
        assertThat(target.resolve("build.jenesis/0.9/build.jenesis.jar")).hasContent("older");
        assertThat(target.resolve("build.jenesis/build.jenesis.jar")).hasContent("fresh");
    }

    @Test
    public void does_not_create_target_when_nothing_to_export() throws IOException {
        BuildStepResult result = run();

        assertThat(result.next()).isTrue();
        assertThat(target).doesNotExist();
    }

    private void stage(String moduleName, String version, String fileName, String content) throws IOException {
        Path folder = source.resolve(moduleName);
        if (version != null) {
            folder = folder.resolve(version);
        }
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(fileName), content);
    }

    private BuildStepResult run() throws IOException {
        return new JenesisModuleRepositoryExport(target).apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("."), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture()
                .join();
    }
}
