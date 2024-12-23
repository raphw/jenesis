package build.buildbuddy.maven;

public record MavenDependencyKey(String groupId, String artifactId, String type, String classifier) {
}
