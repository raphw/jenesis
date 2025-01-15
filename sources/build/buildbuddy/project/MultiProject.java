package build.buildbuddy.project;

import build.buildbuddy.BuildExecutorModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.SequencedSet;

@FunctionalInterface
public interface MultiProject {

    BuildExecutorModule make(String name,
                             SequencedMap<String, Path> arguments,
                             SequencedSet<String> dependencies) throws IOException;
}
