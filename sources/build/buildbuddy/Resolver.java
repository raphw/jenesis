package build.buildbuddy;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.concurrent.Executor;
import java.util.function.Function;

@FunctionalInterface
public interface Resolver {

    SequencedMap<String, String> dependencies(Executor executor, SequencedSet<String> coordinates) throws IOException;

    default Resolver andThen(Resolver resolver) {
        return (executor, coordinates) -> resolver.dependencies(
                executor,
                dependencies(executor, coordinates).sequencedKeySet());
    }

    static Resolver identity() {
        return (_, coordinates) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.forEach(coordinate -> resolved.put(coordinate, ""));
            return resolved;
        };
    }

    static Resolver of(Function<String, SequencedCollection<String>> translator) {
        return (_, coordinates) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.stream()
                    .flatMap(coordinate -> translator.apply(coordinate).stream())
                    .forEach(coordinate -> resolved.put(coordinate, ""));
            return resolved;
        };
    }
}
