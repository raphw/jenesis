package build.buildbuddy;

import java.nio.file.Path;
import java.util.Map;

public record BuildStepArgument(Path folder, Map<Path, ChecksumStatus> files) {

    public boolean isChanged() {
        return files.values().stream().anyMatch(status -> status != ChecksumStatus.RETAINED);
    }
}
