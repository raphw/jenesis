package build.jenesis;

import module java.base;

@FunctionalInterface
public interface RepositoryItem {

    default Optional<Path> file() {
        return Optional.empty();
    }

    default boolean internal() {
        return false;
    }

    default boolean local() {
        return false;
    }

    InputStream toInputStream() throws IOException;

    static RepositoryItem ofFile(Path file) {
        return ofFile(file, false);
    }

    static RepositoryItem ofFile(Path file, boolean internal) {
        return new RepositoryItem() {
            @Override
            public Optional<Path> file() {
                return Optional.of(file);
            }

            @Override
            public boolean internal() {
                return internal;
            }

            @Override
            public InputStream toInputStream() throws IOException {
                return Files.newInputStream(file);
            }
        };
    }
}
