package build.jenesis;

import module java.base;

@FunctionalInterface
public interface Resolver extends Serializable {

    SequencedMap<String, String> dependencies(Executor executor,
                                              String prefix,
                                              Map<String, Repository> repositories,
                                              SequencedSet<String> coordinates,
                                              boolean compile) throws IOException;

    default <T extends BiFunction<String, String, String> & Serializable> Resolver translated(String translated, T translator) {
        return (executor, prefix, repositories, coordinates, compile) -> dependencies(executor,
                translated,
                repositories,
                coordinates.stream()
                        .map(coordinate -> translator.apply(prefix, coordinate))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                compile);
    }

    static Resolver identity() {
        return (_, prefix, _, coordinates, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }

    static <T extends Function<String, SequencedCollection<String>> & Serializable> Resolver of(T translator) {
        return (_, prefix, _, coordinates, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.stream()
                    .flatMap(coordinate -> translator.apply(coordinate).stream())
                    .forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }
}
