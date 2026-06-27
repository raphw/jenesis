package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.step.OsvDownload;

import static org.assertj.core.api.Assertions.assertThat;

public class OsvDownloadTest {

    @Test
    public void parses_query_batch_results_positionally() {
        String response = "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-jfh8-c2jp-5v3q\",\"modified\":\"2025-10-22T19:37:02Z\"},"
                + "{\"id\":\"GHSA-7rjr-3q55-vv33\"}]},{}]}";
        List<List<String>> identifiers = OsvDownload.identifiers(response);
        assertThat(identifiers).hasSize(2);
        assertThat(identifiers.get(0)).containsExactly("GHSA-jfh8-c2jp-5v3q", "GHSA-7rjr-3q55-vv33");
        assertThat(identifiers.get(1)).isEmpty();
    }

    @Test
    public void maps_the_github_severity_word() {
        assertThat(OsvDownload.severity("{\"database_specific\":{\"severity\":\"MODERATE\",\"cwe_ids\":[\"CWE-116\"]}}"))
                .isEqualTo("MEDIUM");
        assertThat(OsvDownload.severity("{\"database_specific\":{\"severity\":\"CRITICAL\"}}"))
                .isEqualTo("CRITICAL");
    }

    @Test
    public void returns_empty_severity_when_absent_or_unparseable() {
        assertThat(OsvDownload.severity("{\"severity\":[{\"type\":\"CVSS_V3\",\"score\":\"CVSS:3.1/AV:N\"}]}")).isEmpty();
        assertThat(OsvDownload.severity("not json")).isEmpty();
    }
}
