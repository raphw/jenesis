package build.jenesis;

import module java.base;

@FunctionalInterface
public interface Resolver extends Serializable {

    SequencedMap<String, String> dependencies(Executor executor,
                                              String prefix,
                                              Map<String, Repository> repositories,
                                              SequencedMap<String, SequencedSet<String>> coordinates,
                                              SequencedMap<String, String> versions,
                                              boolean compile,
                                              ResolutionListener listener) throws IOException;

    default SequencedMap<String, String> dependencies(Executor executor,
                                                      String prefix,
                                                      Map<String, Repository> repositories,
                                                      SequencedMap<String, SequencedSet<String>> coordinates,
                                                      SequencedMap<String, String> versions,
                                                      boolean compile) throws IOException {
        return dependencies(executor, prefix, repositories, coordinates, versions, compile,
                (p, par, c, v, s, f, ctx) -> {});
    }

    default SequencedSet<String> managedPrefixes() {
        return Collections.emptyNavigableSet();
    }

    default <F extends BiFunction<String, String, String> & Serializable> Resolver translated(String translated, F translator) {
        return (executor, prefix, repositories, coordinates, versions, compile, listener) -> {
            SequencedMap<String, String> translatedVersions = new LinkedHashMap<>();
            versions.forEach((coordinate, version) -> translatedVersions.put(
                    pinVersion(translator.apply(prefix, coordinate), version),
                    version));
            SequencedMap<String, SequencedSet<String>> translatedCoordinates = new LinkedHashMap<>();
            coordinates.forEach((coordinate, exclusions) -> translatedCoordinates.put(
                    pinVersion(translator.apply(prefix, coordinate), versions.get(coordinate)),
                    exclusions));
            return dependencies(executor,
                    translated,
                    repositories,
                    translatedCoordinates,
                    translatedVersions,
                    compile,
                    listener);
        };
    }

    static String base(String prefix) {
        int at = prefix.indexOf('@');
        return at < 0 ? prefix : prefix.substring(0, at);
    }

    static String qualify(String coordinate, String qualifier) {
        if (qualifier == null) {
            return coordinate;
        }
        int slash = coordinate.indexOf('/');
        return slash < 0 ? coordinate : coordinate.substring(0, slash) + "@" + qualifier + coordinate.substring(slash);
    }

    private static String pinVersion(String coordinate, String version) {
        if (version == null || version.isEmpty()) {
            return coordinate;
        }
        int space = version.indexOf(' ');
        String trimmed = space < 0 ? version : version.substring(0, space);
        int lastSlash = coordinate.lastIndexOf('/');
        return lastSlash > 0 ? coordinate.substring(0, lastSlash + 1) + trimmed : coordinate;
    }

    static Resolver identity() {
        return (_, prefix, _, coordinates, _, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.sequencedKeySet().forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }

    static <F extends Function<String, SequencedCollection<String>> & Serializable> Resolver of(F translator) {
        return (_, prefix, _, coordinates, _, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.sequencedKeySet().stream()
                    .flatMap(coordinate -> translator.apply(coordinate).stream())
                    .forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }
}
