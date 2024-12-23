package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;

public interface Repository {

    InputStream fetch(String coordinate) throws IOException;
}
