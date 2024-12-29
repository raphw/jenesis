package build.buildbuddy;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.function.Function;

@FunctionalInterface
public interface Resolver {

    Collection<String> dependencies(Executor executor, Collection<String> coordinates) throws IOException;

    default Resolver andThen(Resolver resolver) {
        return (executor, coordinates) -> resolver.dependencies(executor, dependencies(executor, coordinates));
    }

    static Resolver identity() {
        return (_, descriptors) -> descriptors;
    }

    static Resolver of(Function<String, Collection<String>> translator) {
        return (_, coordinates) -> coordinates.stream()
                .flatMap(coordinate -> translator.apply(coordinate).stream())
                .distinct()
                .toList();
    }
}
