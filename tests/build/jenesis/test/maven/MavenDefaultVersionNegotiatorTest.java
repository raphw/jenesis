package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.RepositoryItem;
import build.jenesis.maven.MavenDefaultVersionNegotiator;
import build.jenesis.maven.MavenRepository;
import build.jenesis.maven.MavenVersionNegotiator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    public void resolves_latest_without_release() throws IOException {
        String resolved = negotiator().resolve(Runnable::run,
                metadata("<metadata><versioning><latest>1.0-SNAPSHOT</latest></versioning></metadata>"),
                "group",
                "artifact",
                null,
                null,
                "LATEST");
        assertThat(resolved).isEqualTo("1.0-SNAPSHOT");
    }

    @Test
    public void resolves_range_without_latest_and_release() throws IOException {
        String resolved = negotiator().resolve(Runnable::run,
                metadata("<metadata><versioning><versions>"
                        + "<version>1.0</version><version>2.0</version>"
                        + "</versions></versioning></metadata>"),
                "group",
                "artifact",
                null,
                null,
                "[1.0,2.0)");
        assertThat(resolved).isEqualTo("1.0");
    }

    @Test
    public void release_without_release_fails() {
        assertThatThrownBy(() -> negotiator().resolve(Runnable::run,
                metadata("<metadata><versioning><latest>1.0-SNAPSHOT</latest></versioning></metadata>"),
                "group",
                "artifact",
                null,
                null,
                "RELEASE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Property not defined: release");
    }

    private static MavenVersionNegotiator negotiator() {
        Supplier<MavenVersionNegotiator> supplier = MavenDefaultVersionNegotiator.closest();
        return supplier.get();
    }

    private static MavenRepository metadata(String xml) {
        return new MavenRepository() {
            @Override
            public Optional<RepositoryItem> fetch(Executor executor,
                                                  String groupId,
                                                  String artifactId,
                                                  String version,
                                                  String type,
                                                  String classifier,
                                                  String checksum) {
                return Optional.empty();
            }

            @Override
            public Optional<RepositoryItem> fetchMetadata(Executor executor,
                                                          String groupId,
                                                          String artifactId,
                                                          String checksum) {
                return Optional.of(() -> new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            }
        };
    }
}
