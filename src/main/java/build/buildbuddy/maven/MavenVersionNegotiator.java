package build.buildbuddy.maven;

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
                   String current,
                   SequencedSet<String> versions) throws IOException;
}
