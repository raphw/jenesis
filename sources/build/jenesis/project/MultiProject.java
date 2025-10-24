package build.jenesis.project;

import build.jenesis.BuildExecutorModule;

import module java.base;

@FunctionalInterface
public interface MultiProject {

    BuildExecutorModule module(String name,
                               SequencedMap<String, SequencedSet<String>> dependencies,
                               SequencedMap<String, Path> arguments) throws IOException;
}
