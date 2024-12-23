package build.buildbuddy;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

@FunctionalInterface
public interface Resolver {

    List<String> dependencies(Collection<String> descriptors) throws IOException;
}
