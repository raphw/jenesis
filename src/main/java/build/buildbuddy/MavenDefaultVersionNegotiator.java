package build.buildbuddy;

import java.util.SequencedSet;

public class MavenDefaultVersionNegotiator implements MavenVersionNegotiator {

    private final MavenRepository repository;

    public MavenDefaultVersionNegotiator(MavenRepository repository) {
        this.repository = repository;
    }

    @Override
    public String resolve(String groupId, String artifactId, String type, String classifier, String version) {
        return version; // TODO
    }

    @Override
    public String resolve(String groupId, String artifactId, String type, String classifier, String version, SequencedSet<String> versions) {
        return version; // TODO
    }
}
