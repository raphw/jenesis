package build.jenesis.test.module;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.RepositoryItem;
import build.jenesis.module.JenesisRawGitRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JenesisRawGitRepositoryTest {

    @TempDir
    private Path data;

    @TempDir
    private Path maven;

    @Test
    public void resolves_latest_named_module_from_modules_tsv() throws IOException {
        writeTsv("widget", "modules.tsv",
                "2.0\tcom.example\twidget-core\t2.0",
                "1.0\tcom.example\twidget-core\t1.0");
        writeArtifact("com.example", "widget-core", "2.0", "jar", "v2");

        assertThat(content(named().fetch(Runnable::run, "widget"))).isEqualTo("v2");
    }

    @Test
    public void maps_explicit_module_version_to_its_maven_version() throws IOException {
        // The module-info version (1.0) differs from the Maven coordinate version (1.0.5): the
        // download must use the Maven version from the row's last column, not the requested one.
        writeTsv("widget", "modules.tsv",
                "2.0\tcom.example\twidget-core\t2.0",
                "1.0\tcom.example\twidget-core\t1.0.5");
        writeArtifact("com.example", "widget-core", "1.0.5", "jar", "v105");

        assertThat(content(named().fetch(Runnable::run, "widget/1.0"))).isEqualTo("v105");
    }

    @Test
    public void resolves_through_artifacts_tsv_when_not_requiring_named_modules() throws IOException {
        // artifacts.tsv columns are version, type, groupId, artifactId.
        writeTsv("widget", "artifacts.tsv",
                "2.0\tnamed\tcom.example\twidget-core",
                "1.0\tnamed\tcom.example\twidget-core");
        writeArtifact("com.example", "widget-core", "1.0", "jar", "a1");

        assertThat(content(artifact().fetch(Runnable::run, "widget/1.0"))).isEqualTo("a1");
    }

    @Test
    public void assumes_newest_coordinate_for_a_version_absent_from_the_tsv() throws IOException {
        // 9.9 is not in the TSV: best effort assumes it exists on Maven Central under the newest
        // coordinate (com.example:widget-core), with the requested version verbatim.
        writeTsv("widget", "modules.tsv", "1.0\tcom.example\twidget-core\t1.0");
        writeArtifact("com.example", "widget-core", "9.9", "jar", "guessed");

        assertThat(content(named().fetch(Runnable::run, "widget/9.9"))).isEqualTo("guessed");
    }

    @Test
    public void returns_empty_when_a_best_effort_guess_is_not_on_maven_central() throws IOException {
        writeTsv("widget", "modules.tsv", "1.0\tcom.example\twidget-core\t1.0");

        assertThat(named().fetch(Runnable::run, "widget/9.9")).isEmpty();
    }

    @Test
    public void returns_empty_when_the_module_is_unknown() throws IOException {
        assertThat(named().fetch(Runnable::run, "widget")).isEmpty();
    }

    @Test
    public void returns_empty_when_the_resolved_artifact_is_missing() throws IOException {
        writeTsv("widget", "modules.tsv", "1.0\tcom.example\twidget-core\t1.0");

        assertThat(named().fetch(Runnable::run, "widget")).isEmpty();
    }

    @Test
    public void uses_the_explicit_type_for_the_artifact_filename() throws IOException {
        writeTsv("widget", "modules.tsv", "1.0\tcom.example\twidget-core\t1.0");
        writeArtifact("com.example", "widget-core", "1.0", "pom", "pom-bytes");

        assertThat(content(named().fetch(Runnable::run, "widget:pom"))).isEqualTo("pom-bytes");
    }

    @Test
    public void falls_back_to_jar_when_no_jmod_is_published() throws IOException {
        writeTsv("widget", "modules.tsv", "1.0\tcom.example\twidget-core\t1.0");
        writeArtifact("com.example", "widget-core", "1.0", "jar", "classes");

        assertThat(content(named().fetch(Runnable::run, "widget:jmod"))).isEqualTo("classes");
    }

    @Test
    public void resolves_a_dotted_module_name_through_its_directory_path() throws IOException {
        writeTsv("com.example.widget", "modules.tsv", "1.0\tcom.example\twidget\t1.0");
        writeArtifact("com.example", "widget", "1.0", "jar", "dotted");

        assertThat(content(named().fetch(Runnable::run, "com.example.widget"))).isEqualTo("dotted");
    }

    @Test
    public void normalises_bases_supplied_without_a_trailing_slash() throws IOException {
        writeTsv("widget", "modules.tsv", "1.0\tcom.example\twidget-core\t1.0");
        writeArtifact("com.example", "widget-core", "1.0", "jar", "v1");
        URI dataNoSlash = URI.create(data.toUri().toString().replaceAll("/$", ""));
        URI mavenNoSlash = URI.create(maven.toUri().toString().replaceAll("/$", ""));

        JenesisRawGitRepository repository = new JenesisRawGitRepository(true, dataNoSlash, mavenNoSlash);

        assertThat(content(repository.fetch(Runnable::run, "widget"))).isEqualTo("v1");
    }

    @Test
    public void resolves_classified_module_through_classifier_scoped_tsv() throws IOException {
        writeTsv("widget", "modules-windows-x86_64.tsv", "1.0\tcom.example\twidget-core\t1.0");
        writeArtifact("com.example", "widget-core", "1.0", "windows-x86_64", "jar", "native");

        assertThat(content(named().fetch(Runnable::run, "widget-windows-x86_64/1.0"))).isEqualTo("native");
    }

    @Test
    public void does_not_read_unclassified_tsv_for_classified_request() throws IOException {
        writeTsv("widget", "modules.tsv", "1.0\tcom.example\twidget-core\t1.0");
        writeArtifact("com.example", "widget-core", "1.0", "jar", "v1");

        assertThat(named().fetch(Runnable::run, "widget-win/1.0")).isEmpty();
    }

    @Test
    public void rejects_a_module_name_with_path_traversal() {
        assertThatThrownBy(() -> named().fetch(Runnable::run, "widget/../../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    public void rejects_a_version_with_an_unsafe_character() {
        assertThatThrownBy(() -> named().fetch(Runnable::run, "widget/1.0%2f.."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JenesisRawGitRepository named() {
        return new JenesisRawGitRepository(true, data.toUri(), maven.toUri());
    }

    private JenesisRawGitRepository artifact() {
        return new JenesisRawGitRepository(false, data.toUri(), maven.toUri());
    }

    private void writeTsv(String moduleName, String fileName, String... rows) throws IOException {
        Path dir = Files.createDirectories(data.resolve(moduleName.replace('.', '/')));
        Files.writeString(dir.resolve(fileName), String.join("\n", rows) + "\n");
    }

    private void writeArtifact(String groupId, String artifactId, String version, String type, String content) throws IOException {
        Path dir = Files.createDirectories(maven
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version));
        Files.writeString(dir.resolve(artifactId + "-" + version + "." + type), content);
    }

    private void writeArtifact(String groupId, String artifactId, String version, String classifier, String type, String content) throws IOException {
        Path dir = Files.createDirectories(maven
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version));
        Files.writeString(dir.resolve(artifactId + "-" + version + "-" + classifier + "." + type), content);
    }

    private static String content(Optional<RepositoryItem> item) throws IOException {
        assertThat(item).isPresent();
        try (InputStream stream = item.orElseThrow().toInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
