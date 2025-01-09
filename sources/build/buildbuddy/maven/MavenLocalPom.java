package build.buildbuddy.maven;

import java.util.List;
import java.util.SequencedMap;

public record MavenLocalPom(String groupId,
                            String artifactId,
                            String version,
                            String sourceDirectory,
                            List<String> resourceDirectories,
                            String testSourceDirectory,
                            List<String> testResourceDirectories,
                            SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies,
                            SequencedMap<MavenDependencyKey, MavenDependencyValue> managedDependencies) {
}
