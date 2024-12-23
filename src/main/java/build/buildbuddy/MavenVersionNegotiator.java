package build.buildbuddy;

import java.util.SequencedSet;

public interface MavenVersionNegotiator {

    String resolve(String groupId, String artifactId, String type, String classifier, String version);

    String resolve(String groupId, String artifactId, String type, String classifier, String version, SequencedSet<String> versions);
}
