package build.buildbuddy.module;

import module java.base;

public record ModuleInfo(String coordinate, SequencedSet<String> requires) {
}
