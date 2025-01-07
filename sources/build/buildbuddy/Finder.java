package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SequencedSet;

@FunctionalInterface
public interface Finder {

    SequencedSet<Path> search(Path root) throws IOException;
}
