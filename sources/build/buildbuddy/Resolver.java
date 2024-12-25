package build.buildbuddy;

import java.io.IOException;
import java.util.Collection;

@FunctionalInterface
public interface Resolver {

    Collection<String> dependencies(Collection<String> descriptors) throws IOException;
}
