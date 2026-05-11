package build.jenesis;

import module java.base;

@FunctionalInterface
public interface Resolver extends Serializable {

    SequencedMap<String, String> dependencies(Executor executor,
                                              String prefix,
                                              Map<String, Repository> repositories,
                                              SequencedSet<String> coordinates,
                                              SequencedMap<String, String> versions,
                                              boolean compile) throws IOException;

    default <F extends BiFunction<String, String, String> & Serializable> Resolver translated(String translated, F translator) {
        return (executor, prefix, repositories, coordinates, versions, compile) -> {
            SequencedMap<String, String> translatedVersions = new LinkedHashMap<>();
            versions.forEach((coordinate, version) -> translatedVersions.put(
                    translator.apply(prefix, coordinate),
                    version));
            return dependencies(executor,
                    translated,
                    repositories,
                    coordinates.stream()
                            .map(coordinate -> translator.apply(prefix, coordinate))
                            .collect(Collectors.toCollection(LinkedHashSet::new)),
                    translatedVersions,
                    compile);
        };
    }

    static Resolver identity() {
        return (_, prefix, _, coordinates, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }

    static <F extends Function<String, SequencedCollection<String>> & Serializable> Resolver of(F translator) {
        return (_, prefix, _, coordinates, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.stream()
                    .flatMap(coordinate -> translator.apply(coordinate).stream())
                    .forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }
}
