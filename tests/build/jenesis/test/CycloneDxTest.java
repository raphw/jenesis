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
            List.of(new License("Apache-2.0", "permissive", "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt")));

    private static final List<CycloneDx.Component> COMPONENTS = List.of(
            new CycloneDx.Component("org.foo/bar/1.2.3", "org.foo", "bar", "1.2.3", "pkg:maven/org.foo/bar@1.2.3", "abc123",
                    List.of(new License("Apache-2.0", "permissive", "The Apache Software License, Version 2.0", "https://apache.org/"))),
            new CycloneDx.Component("org.baz/qux/4.5", "org.baz", "qux", "4.5", "pkg:maven/org.baz/qux@4.5", "def456",
                    List.of(new License(null, null, "Some Custom License", "https://example.com/license"))));

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
                .as("a license carrying a known SPDX identifier is emitted as an id")
                .contains("\"id\": \"Apache-2.0\"")
                .as("a license with no recognized identifier falls back to name and url")
                .contains("\"name\": \"Some Custom License\"")
                .contains("\"url\": \"https://example.com/license\"");
        assertThat(json)
                .as("components carry a bom-ref and the dependency graph references them")
                .contains("\"bom-ref\": \"org.foo/bar/1.2.3\"")
                .contains("{ \"ref\": \"build.jenesis/demo/1.0.0\", \"dependsOn\": [\"org.foo/bar/1.2.3\"] }")
                .contains("{ \"ref\": \"org.foo/bar/1.2.3\", \"dependsOn\": [\"org.baz/qux/4.5\"] }");
        assertThat(json)
                .as("a deterministic serial number is derived from the document content, with no wall-clock timestamp")
                .contains("\"serialNumber\": \"urn:uuid:")
                .doesNotContain("timestamp");
        assertThat(json)
                .as("the 1.6 tools form is a components object, not the deprecated tool array")
                .contains("{ \"type\": \"application\", \"name\": \"Jenesis\" }")
                .doesNotContain("\"tools\": [");
    }

    @Test
    public void emits_cyclonedx_xml() {
        String xml = emitter.emit(CycloneDx.Format.XML, PROJECT, COMPONENTS, DEPENDENCIES);
        assertThat(xml)
                .contains("<bom")
                .contains("cyclonedx.org/schema/bom/1.6")
                .contains("serialNumber=\"urn:uuid:")
                .contains("<purl>pkg:maven/org.foo/bar@1.2.3</purl>")
                .contains("<id>Apache-2.0</id>")
                .contains("<name>Some Custom License</name>")
                .contains("bom-ref=\"org.foo/bar/1.2.3\"")
                .contains("<dependency ref=\"org.foo/bar/1.2.3\">");
    }

    @Test
    public void unknown_identifier_is_a_name_unless_added_through_the_wither() {
        CycloneDx.Component custom = new CycloneDx.Component(
                "org.foo/bar/1", "org.foo", "bar", "1", "pkg:maven/org.foo/bar@1", null,
                List.of(new License("Custom-1.0", "permissive", "Custom License 1.0", "https://example.com/custom")));
        assertThat(emitter.emit(CycloneDx.Format.JSON, null, List.of(custom), List.of()))
                .as("an identifier outside the known set is emitted as a name, not an id")
                .contains("\"name\": \"Custom-1.0\"")
                .doesNotContain("\"id\": \"Custom-1.0\"");
        assertThat(emitter.identifiers(Set.of("Custom-1.0")).emit(CycloneDx.Format.JSON, null, List.of(custom), List.of()))
                .as("the wither declares the identifier known, so it is emitted as an id")
                .contains("\"id\": \"Custom-1.0\"");
    }

    @Test
    public void omits_version_and_metadata_component_when_absent() {
        CycloneDx.Component versionless = new CycloneDx.Component(
                "org.foo/bar", "org.foo", "bar", null, "pkg:maven/org.foo/bar", null, List.of());
        String json = emitter.emit(CycloneDx.Format.JSON, null, List.of(versionless), List.of());
        assertThat(json)
                .as("a valid bom is emitted even without a known subject component")
                .contains("\"bomFormat\": \"CycloneDX\"")
                .contains("\"name\": \"Jenesis\"")
                .contains("\"name\": \"bar\"")
                .as("the metadata carries no component when the subject is unknown")
                .doesNotContain("\"component\":")
                .as("no version is fabricated when one is not set")
                .doesNotContain("\"version\": \"");
        String xml = emitter.emit(CycloneDx.Format.XML, null, List.of(versionless), List.of());
        assertThat(xml)
                .contains("<name>bar</name>")
                .doesNotContain("<version>");
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

    @Test
    public void emits_subject_description_authors_and_external_references() {
        CycloneDx.Component subject = new CycloneDx.Component(
                "build.jenesis/demo/1.0.0", "build.jenesis", "demo", "1.0.0", "pkg:maven/build.jenesis/demo@1.0.0", null,
                List.of(),
                "A demo project",
                List.of(new CycloneDx.Author("Rafael Winterhalter", "rafael.wth@gmail.com")),
                List.of(new CycloneDx.ExternalReference("website", "https://example.com/demo")));

        String json = emitter.emit(CycloneDx.Format.JSON, subject, List.of(), List.of());
        assertThat(json)
                .contains("\"description\": \"A demo project\"")
                .contains("\"authors\": [")
                .contains("\"name\": \"Rafael Winterhalter\", \"email\": \"rafael.wth@gmail.com\"")
                .contains("\"externalReferences\": [")
                .contains("{ \"type\": \"website\", \"url\": \"https://example.com/demo\" }");

        String xml = emitter.emit(CycloneDx.Format.XML, subject, List.of(), List.of());
        assertThat(xml)
                .contains("<description>A demo project</description>")
                .contains("<author>")
                .contains("<email>rafael.wth@gmail.com</email>")
                .contains("<reference type=\"website\">")
                .contains("<url>https://example.com/demo</url>");
    }
}
