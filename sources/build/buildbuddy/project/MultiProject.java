package build.buildbuddy.project;

import build.buildbuddy.BuildExecutorModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.SequencedSet;

@FunctionalInterface
public interface MultiProject {

    BuildExecutorModule module(String name,
                               SequencedMap<String, SequencedSet<String>> dependencies,
                               SequencedMap<String, Path> arguments) throws IOException;
}
