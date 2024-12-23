package build.buildbuddy;

public record MavenDependency(String groupId,
                              String artifactId,
                              String version,
                              String type,
                              String classifier,
                              MavenDependencyScope scope,
                              boolean optional) {
}
