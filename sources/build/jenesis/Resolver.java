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
                    pinVersion(translator.apply(prefix, coordinate), version),
                    version));
            return dependencies(executor,
                    translated,
                    repositories,
                    coordinates.stream()
                            .map(coordinate -> pinVersion(
                                    translator.apply(prefix, coordinate),
                                    versions.get(coordinate)))
                            .collect(Collectors.toCollection(LinkedHashSet::new)),
                    translatedVersions,
                    compile);
        };
    }

    private static String pinVersion(String coordinate, String version) {
        if (version == null || version.isEmpty()) {
            return coordinate;
        }
        int lastSlash = coordinate.lastIndexOf('/');
        return lastSlash > 0 ? coordinate.substring(0, lastSlash + 1) + version : coordinate;
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
