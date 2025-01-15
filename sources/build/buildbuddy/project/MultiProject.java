package build.buildbuddy.project;

import build.buildbuddy.BuildExecutorModule;

import java.io.IOException;
import java.util.SequencedSet;

@FunctionalInterface
public interface MultiProject {

    BuildExecutorModule make(String name,
                             SequencedSet<String> identifiers,
                             SequencedSet<String> dependencies) throws IOException;
}
