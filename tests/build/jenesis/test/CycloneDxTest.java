package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.CycloneDx;
import build.jenesis.License;

import static org.assertj.core.api.Assertions.assertThat;

public class CycloneDxTest {

    private final CycloneDx emitter = new CycloneDx();

    private static final CycloneDx.Component PROJECT = new CycloneDx.Component(
            "build.jenesis/demo/1.0.0", "build.jenesis", "demo", "1.0.0", "pkg:maven/build.jenesis/demo@1.0.0", null,
            List.of(new License("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt")));

    private static final List<CycloneDx.Component> COMPONENTS = List.of(
            new CycloneDx.Component("org.foo/bar/1.2.3", "org.foo", "bar", "1.2.3", "pkg:maven/org.foo/bar@1.2.3", "abc123",
                    List.of(new License("The Apache Software License, Version 2.0", "https://apache.org/"))),
            new CycloneDx.Component("org.baz/qux/4.5", "org.baz", "qux", "4.5", "pkg:maven/org.baz/qux@4.5", "def456",
                    List.of(new License("Some Custom License", "https://example.com/license"))));

    private static final List<CycloneDx.Dependency> DEPENDENCIES = List.of(
            new CycloneDx.Dependency("build.jenesis/demo/1.0.0", List.of("org.foo/bar/1.2.3")),
            new CycloneDx.Dependency("org.foo/bar/1.2.3", List.of("org.baz/qux/4.5")),
            new CycloneDx.Dependency("org.baz/qux/4.5", List.of()));

    @Test
    public void emits_cyclonedx_json() {
        String json = emitter.emit(CycloneDx.Format.JSON, PROJECT, COMPONENTS, DEPENDENCIES);
        assertThat(json)
                .contains("\"bomFormat\": \"CycloneDX\"")
                .contains("\"specVersion\": \"1.6\"")
                .contains("\"purl\": \"pkg:maven/org.foo/bar@1.2.3\"")
                .contains("\"alg\": \"SHA-256\", \"content\": \"abc123\"")
                .as("a recognized license name normalizes to its SPDX id")
                .contains("\"id\": \"Apache-2.0\"")
                .as("an unrecognized license falls back to name and url")
                .contains("\"name\": \"Some Custom License\"")
                .contains("\"url\": \"https://example.com/license\"");
        assertThat(json)
                .as("components carry a bom-ref and the dependency graph references them")
                .contains("\"bom-ref\": \"org.foo/bar/1.2.3\"")
                .contains("{ \"ref\": \"build.jenesis/demo/1.0.0\", \"dependsOn\": [\"org.foo/bar/1.2.3\"] }")
                .contains("{ \"ref\": \"org.foo/bar/1.2.3\", \"dependsOn\": [\"org.baz/qux/4.5\"] }");
        assertThat(json).doesNotContain("serialNumber").doesNotContain("timestamp");
    }

    @Test
    public void emits_cyclonedx_xml() {
        String xml = emitter.emit(CycloneDx.Format.XML, PROJECT, COMPONENTS, DEPENDENCIES);
        assertThat(xml)
                .contains("<bom")
                .contains("cyclonedx.org/schema/bom/1.6")
                .contains("<purl>pkg:maven/org.foo/bar@1.2.3</purl>")
                .contains("<id>Apache-2.0</id>")
                .contains("<name>Some Custom License</name>")
                .contains("bom-ref=\"org.foo/bar/1.2.3\"")
                .contains("<dependency ref=\"org.foo/bar/1.2.3\">");
    }

    @Test
    public void is_deterministic_and_order_independent() {
        String first = emitter.emit(CycloneDx.Format.JSON, PROJECT, COMPONENTS, DEPENDENCIES);
        String reversed = emitter.emit(CycloneDx.Format.JSON, PROJECT,
                List.of(COMPONENTS.get(1), COMPONENTS.get(0)),
                List.of(DEPENDENCIES.get(2), DEPENDENCIES.get(1), DEPENDENCIES.get(0)));
        assertThat(reversed)
                .as("components and dependencies are sorted, so input order does not change the output")
                .isEqualTo(first);
    }
}
