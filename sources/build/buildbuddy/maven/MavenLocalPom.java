package build.buildbuddy.maven;

import java.util.SequencedMap;

public record MavenLocalPom(String groupId,
                            String artifactId,
                            String version,
                            String sourceDirectory,
                            String testSourceDirectory,
                            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies,
                            SequencedMap<MavenDependencyKey, MavenDependencyValue> managedDependencies) {
}
