package build.buildbuddy;

import module java.base;

@FunctionalInterface
public interface Resolver {

    SequencedMap<String, String> dependencies(Executor executor,
                                              String prefix,
                                              Map<String, Repository> repositories,
                                              SequencedSet<String> coordinates) throws IOException;

    default Resolver translated(String translated, BiFunction<String, String, String> translator) {
        return (executor, prefix, repositories, coordinates) -> dependencies(executor,
                translated,
                repositories,
                coordinates.stream()
                        .map(coordinate -> translator.apply(prefix, coordinate))
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

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
}
