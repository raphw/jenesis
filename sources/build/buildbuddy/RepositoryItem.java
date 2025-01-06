package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@FunctionalInterface
public interface RepositoryItem {

    default Optional<Path> getFile() {
        return Optional.empty();
    }

    InputStream toInputStream() throws IOException;

    static RepositoryItem ofFile(Path file) {
        return new RepositoryItem() {
            @Override
            public Optional<Path> getFile() {
                return Optional.of(file);
            }

            @Override
            public InputStream toInputStream() throws IOException {
                return Files.newInputStream(file);
            }
        };
    }
}
