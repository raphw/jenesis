package build.buildbuddy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface Repository {

    Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException;

    default Repository andThen(Repository repository) {
        return (executor, coordinate) -> {
            Optional<RepositoryItem> candidate = fetch(executor, coordinate);
            return candidate.isPresent() ? candidate : repository.fetch(executor, coordinate);
        };
    }

    default Repository prepend(Map<String, Path> coordinates) {
        return (executor, coordinate) -> {
            Path file = coordinates.get(coordinate);
            return file == null ? fetch(executor, coordinate) : Optional.of(RepositoryItem.ofFile(file));
        };
    }

    static Repository ofUris(Map<String, URI> uris) {
        return (_, coordinate) -> {
            URI uri = uris.get(coordinate);
            return uri == null ? Optional.empty() : Optional.of(() -> uri.toURL().openStream());
        };
    }

    static Repository empty() {
        return (_, _) -> Optional.empty();
    }

    static Repository files() {
        return (_, coordinate) -> {
            Path file = Paths.get(coordinate);
            return Files.exists(file) ? Optional.of(RepositoryItem.ofFile(file)) : Optional.empty();
        };
    }
}
