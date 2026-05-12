package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.maven.MavenDefaultVersionNegotiator;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenDefaultVersionNegotiatorTest {

    static Stream<Arguments> versionPairs() {
        return Stream.of(
                pair("1", "1", 0),
                pair("1", "2", -1),
                pair("1.0", "1", 0),
                pair("1.0.0", "1", 0),
                pair("1.0.0", "1.0", 0),
                pair("1.0.1", "1.0", 1),
                pair("1.10", "1.2", 1),
                pair("1.10", "1.9", 1),
                pair("2.0", "1.99", 1),
                pair("1.0-alpha", "1.0", -1),
                pair("1.0-alpha-1", "1.0-alpha-2", -1),
                pair("1.0-alpha", "1.0-beta", -1),
                pair("1.0-beta", "1.0-milestone", -1),
                pair("1.0-milestone", "1.0-rc", -1),
                pair("1.0-rc", "1.0-cr", 0),
                pair("1.0-rc", "1.0-snapshot", -1),
                pair("1.0-snapshot", "1.0", -1),
                pair("1.0", "1.0-ga", 0),
                pair("1.0", "1.0-final", 0),
                pair("1.0", "1.0-release", 0),
                pair("1.0-ga", "1.0-final", 0),
                pair("1.0-ga", "1.0-release", 0),
                pair("1.0", "1.0-sp", -1),
                pair("1.0-sp", "1.0-sp1", -1),
                pair("1.0-sp", "1.0-sp-1", -1),
                pair("1.0-sp1", "1.0-sp2", -1),
                pair("1.0-foo", "1.0", 1),
                pair("1.0-foo", "1.0-bar", 1),
                pair("1.0-foo", "1.0-foo1", -1),
                pair("1.0-SNAPSHOT", "1.0-snapshot", 0),
                pair("1.0-RC", "1.0-rc", 0),
                pair("1.0-Alpha", "1.0-alpha", 0),
                pair("1.0a", "1.0-a", 0),
                pair("1.0a1", "1.0-alpha-1", 0),
                pair("1.0a1", "1.0a2", -1),
                pair("1.0rc1", "1.0-rc-1", 0),
                pair("1.0rc1", "1.0rc2", -1),
                pair("2.0a1", "1.0", 1),
                pair("1.0-alpha-1", "1.0-1", -1),
                pair("1.0-1", "1.0", 1),
                pair("1.0-1", "1.0.1", -1),
                pair("1.0", "1.0-1", -1),
                pair("1.0.0-1", "1.0-1", 0),
                pair("1-1", "1.1", -1),
                pair("1.0-SNAPSHOT", "1.0", -1),
                pair("1.0-SNAPSHOT", "1.0.0", -1),
                pair("1.0-SNAPSHOT", "1.1", -1),
                pair("1.0-SNAPSHOT", "1.0-rc1", 1),
                pair("1.0.0.0", "1.0", 0),
                pair("1.0.0.0.0", "1.0", 0),
                pair("1.0.0.1", "1.0", 1),
                pair("1.0.0.0.1", "1.0", 1),
                pair("1.0a", "1.0b", -1),
                pair("1.0pre", "1.0", 1),
                pair("99999999999999999999.1", "99999999999999999999.2", -1),
                pair("1.0.0", "1.0-ga", 0));
    }

    private static Arguments pair(String left, String right, int expected) {
        return Arguments.of(left, right, expected);
    }

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @MethodSource("versionPairs")
    public void compares_like_maven(String left, String right, int expected) {
        assertThat(Integer.signum(MavenDefaultVersionNegotiator.compareVersions(left, right)))
                .as("compareVersions(%s, %s)", left, right)
                .isEqualTo(expected);
        assertThat(Integer.signum(MavenDefaultVersionNegotiator.compareVersions(right, left)))
                .as("compareVersions(%s, %s) reversed", right, left)
                .isEqualTo(-expected);
    }
}
