package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface Identifier {

    Optional<Identification> identify(Path folder) throws IOException;
}
