package build.jenesis.maven;

public record MavenDependencyKey(String groupId, String artifactId, String type, String classifier) {
}
