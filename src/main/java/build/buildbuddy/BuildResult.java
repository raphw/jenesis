package build.buildbuddy;

import java.nio.file.Path;
import java.util.Map;

public record BuildResult(Path folder, Map<Path, ChecksumStatus> files) {

    public boolean isRetained() {
        return files.values().stream().anyMatch(status -> status != ChecksumStatus.RETAINED);
    }
}
