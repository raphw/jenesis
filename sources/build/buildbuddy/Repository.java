package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    static Repository files() {
        return (_, coordinate) -> {
            Path file = Paths.get(coordinate);
            return Files.exists(file) ? Optional.of(new RepositoryItem() {
                @Override
                public Optional<Path> getFile() {
                    return Optional.of(file);
                }

                @Override
                public InputStream toInputStream() throws IOException {
                    return Files.newInputStream(file);
                }
            }) : Optional.empty();
        };
    }
}
