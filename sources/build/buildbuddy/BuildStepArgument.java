package build.buildbuddy;

import java.nio.file.Path;
import java.util.*;

public record BuildStepArgument(Path folder, Map<Path, ChecksumStatus> files) {

    public boolean hasChanged() {
        return files.values().stream().anyMatch(status -> status != ChecksumStatus.RETAINED);
    }

    public boolean hasChanged(Collection<Path> prefixes) {
        return files.entrySet().stream()
                .filter(entry -> prefixes.stream().anyMatch(prefix -> entry.getKey().startsWith(prefix)))
                .anyMatch(entry -> entry.getValue() != ChecksumStatus.RETAINED);
    }

    public static boolean hasChanged(SequencedMap<String, BuildStepArgument> arguments, Path... prefixes) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(List.of(prefixes)));
    }
}
