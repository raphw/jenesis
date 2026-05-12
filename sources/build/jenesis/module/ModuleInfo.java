package build.jenesis.module;

import module java.base;

public record ModuleInfo(String coordinate,
                         String release,
                         SequencedSet<String> requires,
                         SequencedSet<String> runtimeRequires,
                         SequencedMap<String, String> versions) {

    public ModuleInfo(String coordinate, SequencedSet<String> requires, SequencedSet<String> runtimeRequires) {
        this(coordinate, null, requires, runtimeRequires, new LinkedHashMap<>());
    }

    public ModuleInfo(String coordinate,
                      SequencedSet<String> requires,
                      SequencedSet<String> runtimeRequires,
                      SequencedMap<String, String> versions) {
        this(coordinate, null, requires, runtimeRequires, versions);
    }
}
