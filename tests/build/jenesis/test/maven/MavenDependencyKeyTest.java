package build.jenesis.test.maven;

import module org.junit.jupiter.api;
import build.jenesis.maven.MavenDependencyKey;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenDependencyKeyTest {

    @Test
    public void normalizes_empty_classifier_to_absent() {
        // netty-parent resolves <classifier>${tcnative.classifier}</classifier> to "" when the
        // os-maven-plugin extension is absent; "" must behave like no classifier.
        MavenDependencyKey key = new MavenDependencyKey("io.netty", "netty-tcnative-boringssl-static", "jar", "");
        assertThat(key.classifier()).isNull();
        assertThat(key.coordinate(null, "2.0.78.Final"))
                .isEqualTo("io.netty/netty-tcnative-boringssl-static/2.0.78.Final");
    }

    @Test
    public void normalizes_empty_type_to_absent() {
        MavenDependencyKey key = new MavenDependencyKey("foo.bar", "qux", "", null);
        assertThat(key.type()).isNull();
        assertThat(key.coordinate(null, "1")).isEqualTo("foo.bar/qux/1");
    }

    @Test
    public void empty_and_null_classifier_keys_are_equal() {
        assertThat(new MavenDependencyKey("foo.bar", "qux", "jar", ""))
                .isEqualTo(new MavenDependencyKey("foo.bar", "qux", "jar", null));
    }

    @Test
    public void parsing_a_coordinate_with_an_empty_classifier_round_trips_without_double_slash() {
        MavenDependencyKey.Versioned parsed = MavenDependencyKey.parse(
                "io.netty/netty-tcnative-boringssl-static/jar//2.0.78.Final");
        assertThat(parsed.version()).isEqualTo("2.0.78.Final");
        assertThat(parsed.key().coordinate(null, parsed.version()))
                .isEqualTo("io.netty/netty-tcnative-boringssl-static/2.0.78.Final");
    }

    @Test
    public void preserves_a_real_classifier() {
        MavenDependencyKey key = new MavenDependencyKey("foo.bar", "qux", "jar", "linux-x86_64");
        assertThat(key.coordinate(null, "1")).isEqualTo("foo.bar/qux/jar/linux-x86_64/1");
    }
}
