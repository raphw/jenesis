package codes.rafael.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public interface ChecksumDiff {

    Map<Path, ChecksumStatus> read(Path checksum, Path root) throws IOException;

    Map<Path, ChecksumStatus> update(Path checksum, Path root) throws IOException;
}
