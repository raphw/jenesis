package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    default Repository cached(Path folder) {
        ConcurrentMap<String, Path> cache = new ConcurrentHashMap<>();
        return (executor, coordinate) -> {
            Path previous = cache.get(coordinate);
            if (previous != null) {
                return Optional.of(RepositoryItem.ofFile(previous));
            }
            RepositoryItem item = Repository.this.fetch(executor, coordinate).orElse(null);
            if (item == null) {
                return Optional.empty();
            } else {
                Path file = item.getFile().orElse(null), target = folder.resolve(URLEncoder.encode(
                        coordinate,
                        StandardCharsets.UTF_8));
                if (file != null) {
                    Files.createLink(target, file);
                } else {
                    try (InputStream inputStream = item.toInputStream()) {
                        Files.copy(inputStream, target);
                    }
                }
                Path concurrent = cache.putIfAbsent(coordinate, target);
                return Optional.of(RepositoryItem.ofFile(concurrent == null ? target : concurrent));
            }
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
