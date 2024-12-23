package build.buildbuddy.maven;

import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenModuleResolverTest {

    @Test
    public void can_resolve_module() {
        assertThat(new MavenModuleResolver(Map.of("host.org", "maven")::get).apply(
                "https://host.org/maven2/foo/bar/qux/1/qux-1.jar")).isEqualTo("maven/foo.bar/qux/1");
    }
    @Test
    public void can_resolve_module_with_type() {
        assertThat(new MavenModuleResolver(Map.of("host.org", "maven")::get).apply(
                "https://host.org/maven2/foo/bar/qux/1/qux-1.zip")).isEqualTo("maven/foo.bar/qux/zip/1");
    }

    @Test
    public void can_resolve_module_with_classifier() {
        assertThat(new MavenModuleResolver(Map.of("host.org", "maven")::get).apply(
                "https://host.org/maven2/foo/bar/qux/1/qux-baz-1.jar")).isEqualTo("maven/foo.bar/qux/jar/baz/1");
    }
}