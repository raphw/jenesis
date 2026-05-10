package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;

@FunctionalInterface
public interface MultiProject {

    BuildExecutorModule module(String name,
                               SequencedMap<String, SequencedSet<String>> dependencies,
                               SequencedMap<String, Path> arguments) throws IOException;
}
