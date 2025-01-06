package build.buildbuddy.maven;

import build.buildbuddy.Repository;
import build.buildbuddy.RepositoryItem;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface MavenRepository extends Repository {

    @Override
    default Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException {
        String[] elements = coordinate.split("/", 5);
        return switch (elements.length) {
            case 3 -> fetch(elements[0], elements[1], elements[2], "jar", null, null);
            case 4 -> fetch(elements[0], elements[1], elements[3], elements[2], null, null);
            case 5 -> fetch(elements[0], elements[1], elements[4], elements[2], elements[3], null);
            default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + coordinate);
        };
    }

    Optional<RepositoryItem> fetch(String groupId,
                                   String artifactId,
                                   String version,
                                   String type,
                                   String classifier,
                                   String checksum) throws IOException;


    default Optional<RepositoryItem> fetchMetadata(String groupId,
                                                  String artifactId,
                                                  String checksum) throws IOException {
        return Optional.empty();
    }
}
