package codes.rafael.buildbuddy;

import java.nio.file.Path;
import java.util.Map;

public record BuildResult(Path root, Map<Path, ChecksumStatus> diffs) {
}
