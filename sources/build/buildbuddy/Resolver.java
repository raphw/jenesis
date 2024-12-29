package build.buildbuddy;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface Resolver {

    Collection<String> dependencies(Executor executor, Collection<String> coordinates) throws IOException;

    static Resolver identity() {
        return (executor, descriptors) -> descriptors;
    }
}
