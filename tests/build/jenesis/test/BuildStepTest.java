package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildStepTest {

    @Test
    public void recognises_the_meta_inf_versions_overlay() {
        assertThat(BuildStep.underMetaInfVersions(Path.of("META-INF/versions/25/sample/Platform.class"))).isTrue();
        assertThat(BuildStep.underMetaInfVersions(Path.of("META-INF/versions/25/overlay.json"))).isTrue();
        assertThat(BuildStep.underMetaInfVersions(Path.of("META-INF/versions"))).isTrue();
    }

    @Test
    public void other_meta_inf_content_is_not_the_versions_overlay() {
        assertThat(BuildStep.underMetaInfVersions(Path.of("META-INF/native-image/app/reachability-metadata.json"))).isFalse();
        assertThat(BuildStep.underMetaInfVersions(Path.of("META-INF/services/java.sql.Driver"))).isFalse();
        assertThat(BuildStep.underMetaInfVersions(Path.of("META-INF/MANIFEST.MF"))).isFalse();
        assertThat(BuildStep.underMetaInfVersions(Path.of("sample/Sample.class"))).isFalse();
        assertThat(BuildStep.underMetaInfVersions(Path.of("versions/META-INF/overlay.json"))).isFalse();
    }
}
