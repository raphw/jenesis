package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;

public interface Repository {

    InputStream download(String coordinate) throws IOException;
}
