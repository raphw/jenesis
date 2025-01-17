package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;

@FunctionalInterface
public interface BuildExecutorModule {

    String PREVIOUS = "../";

    default Optional<String> resolve(String path) {
        return Optional.of(path);
    }

    void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException;
}
