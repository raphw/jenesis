package build.buildbuddy.test.maven;

import build.buildbuddy.maven.MavenUriParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenUriParserTest {

    @Test
    public void can_resolve_module() {
        assertThat(new MavenUriParser().apply(
                "https://host.org/maven2/foo/bar/qux/1/qux-1.jar")).isEqualTo("foo.bar/qux/1");
    }
    @Test
    public void can_resolve_module_with_type() {
        assertThat(new MavenUriParser().apply(
                "https://host.org/maven2/foo/bar/qux/1/qux-1.zip")).isEqualTo("foo.bar/qux/zip/1");
    }

    @Test
    public void can_resolve_module_with_classifier() {
        assertThat(new MavenUriParser().apply(
                "https://host.org/maven2/foo/bar/qux/1/qux-baz-1.jar")).isEqualTo("foo.bar/qux/jar/baz/1");
    }
}