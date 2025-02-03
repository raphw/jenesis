package build.buildbuddy.module;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.BuildStep;
import build.buildbuddy.Repository;
import build.buildbuddy.SequencedProperties;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@FunctionalInterface
public interface RepositoryBuildExecutorModule extends BuildExecutorModule {

    @Override
    default void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        Map<String, Map<String, URI>> artifacts = new HashMap<>();
        for (Path folder : inherited.values()) {
            load(folder.resolve(BuildStep.COORDINATES), artifacts, location -> Path.of(location).toUri());
            load(folder.resolve(BuildStep.URIS), artifacts, URI::create);
        }
        accept(buildExecutor, inherited, artifacts.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), Repository.ofUris(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
    
    private void load(Path file, 
                      Map<String, Map<String, URI>> artifacts, 
                      Function<String, URI> resolver) throws IOException {
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
                            _ -> new HashMap<>()).put(coordinate.substring(index + 1), resolver.apply(location));
                }
            }
        }
    }

    void accept(BuildExecutor buildExecutor,
                SequencedMap<String, Path> inherited,
                Map<String, Repository> repositories) throws IOException;
}
