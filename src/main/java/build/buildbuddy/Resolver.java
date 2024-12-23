package build.buildbuddy;

import java.io.IOException;
import java.util.List;

public interface Resolver {
    List<String> dependencies(List<String> descriptors) throws IOException;
}
