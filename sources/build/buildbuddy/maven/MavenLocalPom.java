package build.buildbuddy.maven;

import java.util.SequencedMap;

public record MavenLocalPom(String groupId,
                            String artifactId,
                            String version,
                            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies,
                            SequencedMap<MavenDependencyKey, MavenDependencyValue> managedDependencies) {
}
