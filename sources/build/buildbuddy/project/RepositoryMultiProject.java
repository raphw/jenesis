package build.buildbuddy.project;

import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.BuildStep;
import build.buildbuddy.Repository;
import build.buildbuddy.SequencedProperties;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@FunctionalInterface
public interface RepositoryMultiProject extends MultiProject {

    @Override
    default BuildExecutorModule module(String name,
                                       SequencedMap<String, SequencedSet<String>> dependencies,
                                       SequencedMap<String, Path> arguments) throws IOException {
        Map<String, Map<String, Path>> artifacts = new HashMap<>();
        for (Path folder : arguments.values()) {
            Path file = folder.resolve(BuildStep.COORDINATES);
            if (Files.exists(file)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(file)) {
                    properties.load(reader);
                }
                for (String coordinate : properties.stringPropertyNames()) {
                    String location = properties.getProperty(coordinate);
                    if (location.isEmpty()) {
                        throw new IllegalStateException("Unresolved location for " + coordinate + " in " + file);
                    }
                    int index = coordinate.indexOf('/');
                    artifacts.computeIfAbsent(
                            coordinate.substring(0, index),
                            _ -> new HashMap<>()).put(coordinate.substring(index + 1), file);
                }
            }
        }
        return module(name, dependencies, arguments, artifacts.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> Repository.ofFiles(entry.getValue()))));
    }

    BuildExecutorModule module(String name,
                               SequencedMap<String, SequencedSet<String>> dependencies,
                               SequencedMap<String, Path> arguments,
                               Map<String, Repository> repositories) throws IOException;
}
