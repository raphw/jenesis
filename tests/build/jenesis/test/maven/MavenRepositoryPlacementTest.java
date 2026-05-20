package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenRepositoryPlacement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenRepositoryPlacementTest {

    @TempDir
    private Path root;
    private final MavenRepositoryPlacement placement = new MavenRepositoryPlacement();

    private static SequencedProperties properties(String... pairs) {
        SequencedProperties properties = new SequencedProperties();
        for (int index = 0; index < pairs.length; index += 2) {
            properties.setProperty(pairs[index], pairs[index + 1]);
        }
        return properties;
    }

    private Path moduleWithPom() throws IOException {
        Path module = Files.createDirectory(root.resolve("module-x"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        return module;
    }

    private SequencedProperties coordinates() {
        return properties("project", "com.example", "artifact", "foo", "version", "1.2.3");
    }

    @Test
    public void routes_classes_jar_under_groupId_artifactId_version() throws IOException {
        Path module = moduleWithPom();
        assertThat(placement.apply(module.resolve("classes.jar"), properties(), coordinates()))
                .contains(Path.of("com/example/foo/1.2.3/foo-1.2.3.jar"));
    }

    @Test
    public void routes_sources_jar_with_sources_classifier() throws IOException {
        Path module = moduleWithPom();
        assertThat(placement.apply(module.resolve("sources.jar"), properties(), coordinates()))
                .contains(Path.of("com/example/foo/1.2.3/foo-1.2.3-sources.jar"));
    }

    @Test
    public void routes_javadoc_jar_with_javadoc_classifier() throws IOException {
        Path module = moduleWithPom();
        assertThat(placement.apply(module.resolve("javadoc.jar"), properties(), coordinates()))
                .contains(Path.of("com/example/foo/1.2.3/foo-1.2.3-javadoc.jar"));
    }

    @Test
    public void routes_pom_xml_with_pom_extension() throws IOException {
        Path module = moduleWithPom();
        assertThat(placement.apply(module.resolve("pom.xml"), properties(), coordinates()))
                .contains(Path.of("com/example/foo/1.2.3/foo-1.2.3.pom"));
    }

    @Test
    public void applies_tests_classifier_to_test_module_jars() throws IOException {
        Path module = moduleWithPom();
        SequencedProperties testModule = properties("tests", "foo");
        SequencedProperties coordinates = coordinates();
        assertThat(placement.apply(module.resolve("classes.jar"), testModule, coordinates))
                .contains(Path.of("com/example/foo/1.2.3/foo-1.2.3-tests.jar"));
        assertThat(placement.apply(module.resolve("sources.jar"), testModule, coordinates))
                .contains(Path.of("com/example/foo/1.2.3/foo-1.2.3-tests-sources.jar"));
        assertThat(placement.apply(module.resolve("javadoc.jar"), testModule, coordinates))
                .contains(Path.of("com/example/foo/1.2.3/foo-1.2.3-tests-javadoc.jar"));
    }

    @Test
    public void skips_pom_xml_for_test_modules() throws IOException {
        Path module = moduleWithPom();
        assertThat(placement.apply(module.resolve("pom.xml"), properties("tests", "foo"), coordinates()))
                .isEmpty();
    }

    @Test
    public void returns_empty_for_unknown_filenames() throws IOException {
        Path module = moduleWithPom();
        assertThat(placement.apply(module.resolve("readme.txt"), properties(), coordinates())).isEmpty();
        assertThat(placement.apply(module.resolve("module.properties"), properties(), coordinates())).isEmpty();
    }

    @Test
    public void returns_empty_when_no_sibling_pom_is_present() throws IOException {
        Path module = Files.createDirectory(root.resolve("module-without-pom"));
        Files.writeString(module.resolve("classes.jar"), "ignored");
        assertThat(placement.apply(module.resolve("classes.jar"), properties(), coordinates())).isEmpty();
    }

    @Test
    public void throws_when_project_coordinate_is_missing_from_metadata() throws IOException {
        Path module = moduleWithPom();
        SequencedProperties incomplete = properties("artifact", "foo", "version", "1.2.3");
        assertThatThrownBy(() -> placement.apply(module.resolve("classes.jar"), properties(), incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project=null");
    }

    @Test
    public void throws_when_artifact_coordinate_is_missing_from_metadata() throws IOException {
        Path module = moduleWithPom();
        SequencedProperties incomplete = properties("project", "com.example", "version", "1.2.3");
        assertThatThrownBy(() -> placement.apply(module.resolve("classes.jar"), properties(), incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("artifact=null");
    }

    @Test
    public void throws_when_version_coordinate_is_missing_from_metadata() throws IOException {
        Path module = moduleWithPom();
        SequencedProperties incomplete = properties("project", "com.example", "artifact", "foo");
        assertThatThrownBy(() -> placement.apply(module.resolve("classes.jar"), properties(), incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version=null");
    }
}
