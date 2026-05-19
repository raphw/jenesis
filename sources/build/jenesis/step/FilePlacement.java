package build.jenesis.step;

import module java.base;

@FunctionalInterface
public interface FilePlacement extends Serializable {

    Optional<Path> apply(Path file, Properties metadata) throws IOException;
}
