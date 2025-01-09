package build.buildbuddy.module;

import java.util.SequencedSet;

public record ModuleInfo(String coordinate, SequencedSet<String> requires) {
}
