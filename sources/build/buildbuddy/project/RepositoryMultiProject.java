package build.buildbuddy.project;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@FunctionalInterface
public interface RepositoryMultiProject extends MultiProject {

    @Override
    default BuildExecutorModule module(String name,
                                       SequencedMap<String, SequencedSet<String>> dependencies,
                                       SequencedMap<String, Path> arguments) throws IOException {
        return module(name, dependencies, arguments, Map.of());
    }

    default MultiProject repositories(Map<String, Repository> repositories) {
        return (name, dependencies, arguments) -> module(name, dependencies, arguments, repositories);
    }

    private BuildExecutorModule module(String name,
                                       SequencedMap<String, SequencedSet<String>> dependencies,
                                       SequencedMap<String, Path> arguments,
                                       Map<String, Repository> repositories) {
        return (buildExecutor, inherited) -> {
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
                        if (!location.isEmpty()) {
                            int index = coordinate.indexOf('/');
                            artifacts.computeIfAbsent(
                                    coordinate.substring(0, index),
                                    _ -> new HashMap<>()).put(coordinate.substring(index + 1), Path.of(location));
                        }
                    }
                }
            }
            accept(buildExecutor, inherited, name, dependencies, arguments, Stream.concat(
                    artifacts.entrySet().stream().map(entry -> Map.entry(
                            entry.getKey(),
                            Repository.ofFiles(entry.getValue()))),
                    repositories.entrySet().stream()).collect(Collectors.toMap(
                    Map.Entry::getKey, Map.Entry::getValue, Repository::prepend)));
        };
    }

    void accept(BuildExecutor buildExecutor,
                SequencedMap<String, Path> inherited,
                String name,
                SequencedMap<String, SequencedSet<String>> dependencies,
                SequencedMap<String, Path> arguments,
                Map<String, Repository> repositories) throws IOException;
}
