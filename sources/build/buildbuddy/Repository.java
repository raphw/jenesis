package build.buildbuddy;

import java.io.IOException;
import java.util.Optional;

@FunctionalInterface
public interface Repository {

    Optional<RepositoryItem> fetch(String coordinate) throws IOException;

    default Repository andThen(Repository repository) {
        return coordinate -> {
            Optional<RepositoryItem> candidate = fetch(coordinate);
            return candidate.isPresent() ? candidate : repository.fetch(coordinate);
        };
    }
}
