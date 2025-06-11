package build.buildbuddy.maven;

import module java.base;

public record MavenLocalPom(String groupId,
                            String artifactId,
                            String version,
                            String packaging,
                            String sourceDirectory,
                            List<String> resourceDirectories,
                            String testSourceDirectory,
                            List<String> testResourceDirectories,
                            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies,
                            SequencedMap<MavenDependencyKey, MavenDependencyValue> managedDependencies) {
}
