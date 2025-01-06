package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

@FunctionalInterface
public interface Identifier {

    // TODO: add method that provides check for incremental build needs.

    Optional<Identification> identify(Path folder) throws IOException;
}
