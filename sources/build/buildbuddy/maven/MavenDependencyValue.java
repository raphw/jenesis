package build.buildbuddy.maven;

import java.nio.file.Path;
import java.util.List;

public record MavenDependencyValue(String version,
                                   MavenDependencyScope scope,
                                   Path systemPath,
                                   List<MavenDependencyName> exclusions,
                                   Boolean optional) {
}
