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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@FunctionalInterface
public interface Repository {

    Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException;

    default Repository prepend(Repository repository) {
        return (executor, coordinate) -> {
            Optional<RepositoryItem> candidate = repository.fetch(executor, coordinate);
            return candidate.isPresent() ? candidate : fetch(executor, coordinate);
        };
    }

    default Repository cached(Path folder) {
        ConcurrentMap<String, Path> cache = new ConcurrentHashMap<>();
        return (executor, coordinate) -> {
            Path previous = cache.get(coordinate);
            if (previous != null) {
                return Optional.of(RepositoryItem.ofFile(previous));
            }
            RepositoryItem item = fetch(executor, coordinate).orElse(null);
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

    static Repository empty() {
        return (_, _) -> Optional.empty();
    }

    static Repository ofUris(Map<String, URI> uris) {
        return (_, coordinate) -> {
            URI uri = uris.get(coordinate);
            if (uri == null) {
                return Optional.empty();
            } else if (Objects.equals("file", uri.getScheme())) {
                return Optional.of(RepositoryItem.ofFile(Path.of(uri)));
            } else {
                return Optional.of(() -> uri.toURL().openStream());
            }
        };
    }

    static Repository ofFiles(Map<String, Path> files) {
        return (_, coordinate) -> {
            Path file = files.get(coordinate);
            return file == null ? Optional.empty() : Optional.of(RepositoryItem.ofFile(file));
        };
    }

    static Repository files() {
        return (_, coordinate) -> {
            Path file = Paths.get(coordinate);
            return Files.exists(file) ? Optional.of(RepositoryItem.ofFile(file)) : Optional.empty();
        };
    }

    static Map<String, Repository> prepend(Map<String, ? extends Repository> left,
                                           Map<String, ? extends Repository> right) {
        return Stream.concat(left.entrySet().stream(), right.entrySet().stream()).collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                Repository::prepend));
    }
}
