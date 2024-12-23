package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public interface ChecksumDiff {

    Map<Path, ChecksumStatus> read(Path checksum, Path folder) throws IOException;

    Map<Path, ChecksumStatus> update(Path checksum, Path folder) throws IOException;
}
