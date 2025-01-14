package build.buildbuddy.project;

import build.buildbuddy.BuildExecutorModule;

import java.io.IOException;
import java.util.SequencedMap;
import java.util.SequencedSet;

@FunctionalInterface
public interface MultiProjectBuilder {

    BuildExecutorModule apply(String name, SequencedMap<String, SequencedSet<String>> dependencies) throws IOException;
}
