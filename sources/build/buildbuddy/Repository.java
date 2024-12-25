package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    static Repository files() {
        return coordinate -> {
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
