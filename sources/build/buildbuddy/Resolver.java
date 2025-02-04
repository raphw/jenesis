package build.buildbuddy;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

@FunctionalInterface
public interface Resolver {

    SequencedMap<String, String> dependencies(Executor executor,
                                              String prefix,
                                              Map<String, Repository> repositories,
                                              SequencedSet<String> coordinates) throws IOException;

    static Resolver identity() {
        return (_, prefix, _, coordinates) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }

    static Resolver of(Function<String, SequencedCollection<String>> translator) {
        return (_, prefix, _, coordinates) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.stream()
                    .flatMap(coordinate -> translator.apply(coordinate).stream())
                    .forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }

    static Map<String, Resolver> translate(String prefix,
                                           Function<String, Optional<String>> translator,
                                           Map<String, Resolver> resolvers) {
        Map<String, Resolver> wrapped = new HashMap<>(resolvers);
        wrapped.put(prefix, (executor, _, repositories, coordinates) -> {
            SequencedMap<String, String> dependencies = new LinkedHashMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> entry : coordinates.stream()
                    .map(coordinate -> translator.apply(coordinate).orElseGet(() -> prefix + "/" + coordinate))
                    .collect(Collectors.groupingBy(
                            coordinate -> coordinate.substring(0, coordinate.indexOf('/')),
                            Collectors.mapping(
                                    coordinate -> coordinate.substring(coordinate.indexOf('/') + 1),
                                    Collectors.toCollection(LinkedHashSet::new)))).entrySet()) {
                dependencies.putAll(Objects.requireNonNull(
                        resolvers.get(entry.getKey()),
                        "Could not resolve: " + entry.getKey()).dependencies(executor,
                        entry.getKey(),
                        repositories,
                        entry.getValue()));
            }
            return dependencies;
        });
        return wrapped;
    }
}
