package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.maven.MavenPomTranslator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenPomTranslatorTest {

    @Test
    public void extracts_root_level_coordinate() {
        String result = new MavenPomTranslator(stub(Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                    <version>1.2.3</version>
                </project>"""))).apply("module", "foo.bar");

        assertThat(result).isEqualTo("org.example/example-core/1.2.3");
    }

    @Test
    public void inherits_groupId_and_version_from_parent_when_absent() {
        String result = new MavenPomTranslator(stub(Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>org.example</groupId>
                        <artifactId>example-parent</artifactId>
                        <version>1.2.3</version>
                    </parent>
                    <artifactId>example-core</artifactId>
                </project>"""))).apply("module", "foo.bar");

        assertThat(result).isEqualTo("org.example/example-core/1.2.3");
    }

    @Test
    public void direct_groupId_and_version_override_parent() {
        String result = new MavenPomTranslator(stub(Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <parent>
                        <groupId>org.parent</groupId>
                        <artifactId>example-parent</artifactId>
                        <version>9.9</version>
                    </parent>
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                    <version>1.2.3</version>
                </project>"""))).apply("module", "foo.bar");

        assertThat(result).isEqualTo("org.example/example-core/1.2.3");
    }

    @Test
    public void forwards_versioned_coordinate_to_repository() {
        Map<String, String> queried = new LinkedHashMap<>();
        new MavenPomTranslator((_, coordinate) -> {
            queried.put(coordinate, "");
            return Optional.of(() -> new ByteArrayInputStream("""
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                        <groupId>g</groupId>
                        <artifactId>a</artifactId>
                        <version>v</version>
                    </project>""".getBytes(StandardCharsets.UTF_8)));
        }).apply("module", "foo.bar/1.0");

        assertThat(queried).containsOnlyKeys("foo.bar/1.0:pom");
    }

    @Test
    public void throws_when_pom_is_missing() {
        assertThatThrownBy(() -> new MavenPomTranslator(stub(Map.of())).apply("module", "foo.bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No POM found for foo.bar");
    }

    @Test
    public void throws_when_groupId_cannot_be_resolved() {
        assertThatThrownBy(() -> new MavenPomTranslator(stub(Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <artifactId>example-core</artifactId>
                    <version>1.2.3</version>
                </project>"""))).apply("module", "foo.bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing groupId");
    }

    @Test
    public void throws_when_version_cannot_be_resolved() {
        assertThatThrownBy(() -> new MavenPomTranslator(stub(Map.of("foo.bar:pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>example-core</artifactId>
                </project>"""))).apply("module", "foo.bar"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing version");
    }

    private static Repository stub(Map<String, String> pomBodies) {
        return (_, coordinate) -> {
            String body = pomBodies.get(coordinate);
            if (body == null) {
                return Optional.empty();
            }
            return Optional.of(() -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        };
    }
}
