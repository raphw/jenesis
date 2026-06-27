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

    @Test
    public void scores_a_cvss_v3_vector_when_there_is_no_github_word() {
        assertThat(OsvDownload.severity("{\"severity\":[{\"type\":\"CVSS_V3\",\"score\":\"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H\"}]}"))
                .as("9.8 base score").isEqualTo("CRITICAL");
        assertThat(OsvDownload.severity("{\"severity\":[{\"type\":\"CVSS_V3\",\"score\":\"CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N\"}]}"))
                .as("5.5 base score").isEqualTo("MEDIUM");
    }

    @Test
    public void scores_a_cvss_v2_vector() {
        assertThat(OsvDownload.severity("{\"severity\":[{\"type\":\"CVSS_V2\",\"score\":\"AV:N/AC:L/Au:N/C:C/I:C/A:C\"}]}"))
                .as("10.0 base score").isEqualTo("CRITICAL");
    }

    @Test
    public void prefers_the_github_word_over_a_cvss_vector() {
        assertThat(OsvDownload.severity("{\"database_specific\":{\"severity\":\"MODERATE\"},\"severity\":[{\"type\":\"CVSS_V3\",\"score\":\"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H\"}]}"))
                .isEqualTo("MEDIUM");
    }

    @Test
    public void leaves_a_cvss_v4_only_advisory_unscored() {
        assertThat(OsvDownload.severity("{\"severity\":[{\"type\":\"CVSS_V4\",\"score\":\"CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N\"}]}"))
                .isEmpty();
    }
}
