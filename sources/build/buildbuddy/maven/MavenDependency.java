package build.buildbuddy.maven;

import java.nio.file.Path;

public record MavenDependency(String groupId,
                              String artifactId,
                              String version,
                              String type,
                              String classifier,
                              MavenDependencyScope scope,
                              Path path,
                              boolean optional) {
}
