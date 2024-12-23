package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface Repository {

    InputStream fetch(String coordinate) throws IOException;
}
