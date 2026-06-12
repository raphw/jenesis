package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.RepositoryItem;
import build.jenesis.module.JenesisModuleRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JenesisModuleRepositoryTest {

    @TempDir
    private Path root;

    @Test
    public void fetches_unversioned_module_from_root_module_directory() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Files.writeString(moduleDir.resolve("build.jenesis.jar"), "classes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri()).fetch(Runnable::run, "build.jenesis");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("classes");
        }
    }

    @Test
    public void fetches_versioned_module_from_version_subdirectory() throws IOException {
        Path versionDir = Files.createDirectories(root.resolve("build.jenesis/1.0.0"));
        Files.writeString(versionDir.resolve("build.jenesis.jar"), "v1-classes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis/1.0.0");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("v1-classes");
        }
    }

    @Test
    public void exposes_underlying_path_for_file_uri() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Path jar = Files.writeString(moduleDir.resolve("build.jenesis.jar"), "classes");

        RepositoryItem item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis")
                .orElseThrow();

        assertThat(item.file()).hasValue(jar);
    }

    @Test
    public void returns_empty_when_unversioned_module_is_missing() throws IOException {
        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri()).fetch(Runnable::run, "build.jenesis");

        assertThat(item).isEmpty();
    }

    @Test
    public void returns_empty_when_versioned_module_is_missing() throws IOException {
        Files.createDirectories(root.resolve("build.jenesis"));

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis/1.0.0");

        assertThat(item).isEmpty();
    }

    @Test
    public void normalises_uri_without_trailing_slash() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Files.writeString(moduleDir.resolve("build.jenesis.jar"), "classes");
        URI rootWithoutSlash = URI.create(root.toUri().toString().replaceAll("/$", ""));

        Optional<RepositoryItem> item = new JenesisModuleRepository(rootWithoutSlash).fetch(Runnable::run, "build.jenesis");

        assertThat(item).isPresent();
    }

    @Test
    public void does_not_confuse_module_with_a_prefix_relationship() throws IOException {
        Files.createDirectories(root.resolve("build.jenesis"));
        Path otherDir = Files.createDirectories(root.resolve("build.jenesis.extras"));
        Files.writeString(otherDir.resolve("build.jenesis.extras.jar"), "extras");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri()).fetch(Runnable::run, "build.jenesis");

        assertThat(item).isEmpty();
    }

    @Test
    public void fetches_unversioned_artifact_with_explicit_type() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Files.writeString(moduleDir.resolve("build.jenesis.pom"), "pom-bytes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis:pom");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("pom-bytes");
        }
    }

    @Test
    public void fetches_versioned_artifact_with_explicit_type() throws IOException {
        Path versionDir = Files.createDirectories(root.resolve("build.jenesis/1.0.0"));
        Files.writeString(versionDir.resolve("build.jenesis.pom"), "v1-pom-bytes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis/1.0.0:pom");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("v1-pom-bytes");
        }
    }

    @Test
    public void does_not_serve_jar_when_pom_is_requested() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Files.writeString(moduleDir.resolve("build.jenesis.jar"), "classes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis:pom");

        assertThat(item).isEmpty();
    }

    @Test
    public void fetches_jmod_with_explicit_type() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Files.writeString(moduleDir.resolve("build.jenesis.jmod"), "jmod-bytes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis:jmod");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("jmod-bytes");
        }
    }

    @Test
    public void fetches_classified_module_from_version_subdirectory() throws IOException {
        Path versionDir = Files.createDirectories(root.resolve("build.jenesis/1.0.0"));
        Files.writeString(versionDir.resolve("build.jenesis-windows-x86_64.jar"), "native-classes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis-windows-x86_64/1.0.0");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("native-classes");
        }
    }

    @Test
    public void fetches_unversioned_classified_module() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Files.writeString(moduleDir.resolve("build.jenesis-win.jar"), "native-classes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis-win");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("native-classes");
        }
    }

    @Test
    public void classified_fetch_honours_explicit_type() throws IOException {
        Path versionDir = Files.createDirectories(root.resolve("build.jenesis/1.0.0"));
        Files.writeString(versionDir.resolve("build.jenesis-win.pom"), "classified-pom");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis-win/1.0.0:pom");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("classified-pom");
        }
    }

    @Test
    public void does_not_serve_unclassified_jar_for_classified_request() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Files.writeString(moduleDir.resolve("build.jenesis.jar"), "classes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis-win");

        assertThat(item).isEmpty();
    }

    @Test
    public void rejects_blank_classifier() {
        assertThatThrownBy(() -> new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis-"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classifier");
    }

    @Test
    public void rejects_version_segment_containing_path_traversal() throws IOException {
        Path outside = Files.writeString(root.resolve("secret.jar"), "secret");

        assertThatThrownBy(() -> new JenesisModuleRepository(root.resolve("module").toUri())
                .fetch(Runnable::run, "build.jenesis/../../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");

        assertThat(Files.exists(outside)).isTrue();
    }

    @Test
    public void rejects_version_with_unsafe_character() {
        assertThatThrownBy(() -> new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis/..%2f..%2fsecret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void rejects_module_name_with_path_separator() {
        assertThatThrownBy(() -> new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build\\jenesis"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void jmod_request_falls_back_to_jar_when_no_jmod_is_published() throws IOException {
        Path moduleDir = Files.createDirectories(root.resolve("build.jenesis"));
        Files.writeString(moduleDir.resolve("build.jenesis.jar"), "classes");

        Optional<RepositoryItem> item = new JenesisModuleRepository(root.toUri())
                .fetch(Runnable::run, "build.jenesis:jmod");

        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("classes");
        }
    }
}
