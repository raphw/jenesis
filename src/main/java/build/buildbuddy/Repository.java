package build.buildbuddy;

import java.io.IOException;
import java.util.Optional;

@FunctionalInterface
public interface Repository {

    Optional<RepositoryItem> fetch(String coordinate) throws IOException;
}
