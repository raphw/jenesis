package build.buildbuddy;

import java.io.IOException;
import java.util.SequencedSet;

public interface MavenVersionNegotiator {

    String resolve(String groupId,
                   String artifactId,
                   String type,
                   String classifier,
                   String version) throws IOException;

    String resolve(String groupId,
                   String artifactId,
                   String type,
                   String classifier,
                   String version,
                   SequencedSet<String> versions) throws IOException;
}
