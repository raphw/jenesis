package build.jenesis.step;

import module java.base;
import build.jenesis.SequencedProperties;

@FunctionalInterface
public interface FilePlacement extends Serializable {

    Optional<Path> apply(Path file, SequencedProperties module, SequencedProperties metadata) throws IOException;
}
