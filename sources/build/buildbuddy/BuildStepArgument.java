package build.buildbuddy;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

public record BuildStepArgument(Path folder, Map<Path, ChecksumStatus> files) {

    public boolean hasChanged() {
        return files.values().stream().anyMatch(status -> status != ChecksumStatus.RETAINED);
    }

    public boolean hasChanged(Path... prefixes) {
        return hasChanged(Arrays.asList(prefixes));
    }

    public boolean hasChanged(Collection<Path> prefixes) {
        return files.entrySet().stream()
                .filter(entry -> prefixes.stream().anyMatch(prefix -> entry.getKey().startsWith(prefix)))
                .anyMatch(entry -> entry.getValue() != ChecksumStatus.RETAINED);
    }
}
