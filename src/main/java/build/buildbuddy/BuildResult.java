package build.buildbuddy;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

public record BuildResult(Path folder, Map<Path, ChecksumStatus> files) {

    public BuildResult toAltered() {
        return new BuildResult(folder, files.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> ChecksumStatus.ADDED)));
    }

    public boolean isChanged() {
        return files.values().stream().anyMatch(status -> status != ChecksumStatus.RETAINED);
    }
}
