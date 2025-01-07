package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SequencedMap;

@FunctionalInterface
public interface BuildExecutorDelegate {

    void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException;
}
