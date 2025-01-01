package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SequencedMap;

@FunctionalInterface
public interface BuildExecutorFactory {

    BuildExecutor make(Path root, HashFunction hash, SequencedMap<String, Path> paths) throws IOException;
}
