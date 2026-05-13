package build.jenesis.module;

import module java.base;

public record ModuleInfo(String coordinate,
                         String release,
                         String name,
                         String description,
                         SequencedSet<String> requires,
                         SequencedSet<String> runtimeRequires,
                         SequencedMap<String, String> versions) {

    public ModuleInfo(String coordinate, SequencedSet<String> requires, SequencedSet<String> runtimeRequires) {
        this(coordinate, null, null, null, requires, runtimeRequires, new LinkedHashMap<>());
    }

    public ModuleInfo(String coordinate,
                      SequencedSet<String> requires,
                      SequencedSet<String> runtimeRequires,
                      SequencedMap<String, String> versions) {
        this(coordinate, null, null, null, requires, runtimeRequires, versions);
    }

    public ModuleInfo(String coordinate,
                      String release,
                      SequencedSet<String> requires,
                      SequencedSet<String> runtimeRequires,
                      SequencedMap<String, String> versions) {
        this(coordinate, release, null, null, requires, runtimeRequires, versions);
    }
}
