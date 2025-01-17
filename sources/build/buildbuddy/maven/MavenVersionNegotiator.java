package build.buildbuddy.maven;

import java.io.IOException;
import java.util.SequencedSet;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface MavenVersionNegotiator {

    String resolve(Executor executor,
                   String groupId,
                   String artifactId,
                   String type,
                   String classifier,
                   String version) throws IOException;

    default String resolve(Executor executor,
                           String groupId,
                           String artifactId,
                           String type,
                           String classifier,
                           String current,
                           SequencedSet<String> versions) throws IOException {
        return current;
    }
}
