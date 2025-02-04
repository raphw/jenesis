package build.buildbuddy;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

@FunctionalInterface
public interface Resolver {

    SequencedMap<String, String> dependencies(Executor executor,
                                              Repository repository,
                                              SequencedSet<String> coordinates) throws IOException;

    static Resolver identity() {
        return (_, _, coordinates) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.forEach(coordinate -> resolved.put(coordinate, ""));
            return resolved;
        };
    }

    static Resolver of(Function<String, SequencedCollection<String>> translator) {
        return (_, _, coordinates) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.stream()
                    .flatMap(coordinate -> translator.apply(coordinate).stream())
                    .forEach(coordinate -> resolved.put(coordinate, ""));
            return resolved;
        };
    }

    static Map<String, Resolver> translate(String prefix,
                                           Function<String, Optional<String>> translator,
                                           Map<String, Resolver> resolvers,
                                           Map<String, Repository> repositories) {
        Map<String, Resolver> wrapped = new HashMap<>(resolvers);
        wrapped.put(prefix, (executor, repository, coordinates) -> {
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
                        entry.getKey().equals(prefix)
                                ? repository
                                : repositories.getOrDefault(entry.getKey(), Repository.empty()),
                        entry.getValue()));
            }
            return dependencies;
        });
        return wrapped;
    }
}
