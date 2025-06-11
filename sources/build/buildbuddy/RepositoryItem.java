package build.buildbuddy;

import module java.base;

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
