package build.buildbuddy.maven;

import module java.base;

public record MavenDependencyValue(String version,
                                   MavenDependencyScope scope,
                                   Path systemPath,
                                   List<MavenDependencyName> exclusions,
                                   Boolean optional) {
}
