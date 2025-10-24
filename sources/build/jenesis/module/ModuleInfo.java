package build.jenesis.module;

import module java.base;

public record ModuleInfo(String coordinate, SequencedSet<String> requires) {
}
