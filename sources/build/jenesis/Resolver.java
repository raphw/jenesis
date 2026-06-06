package build.jenesis;

import module java.base;

@FunctionalInterface
public interface Resolver extends Serializable {

    SequencedMap<String, String> dependencies(Executor executor,
                                              String prefix,
                                              Map<String, Repository> repositories,
                                              SequencedMap<String, SequencedSet<String>> coordinates,
                                              SequencedMap<String, String> versions,
                                              DependencyScope scope,
                                              ResolutionListener listener) throws IOException;

    default SequencedMap<String, String> dependencies(Executor executor,
                                                      String prefix,
                                                      Map<String, Repository> repositories,
                                                      SequencedMap<String, SequencedSet<String>> coordinates,
                                                      SequencedMap<String, String> versions,
                                                      DependencyScope scope) throws IOException {
        return dependencies(executor, prefix, repositories, coordinates, versions, scope, null);
    }

    default SequencedSet<String> managedPrefixes() {
        return Collections.emptyNavigableSet();
    }

    static String base(String prefix) {
        int at = prefix.indexOf('@');
        return at < 0 ? prefix : prefix.substring(0, at);
    }

    static Resolver identity() {
        return (_, prefix, _, coordinates, _, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.sequencedKeySet().forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return resolved;
        };
    }
}
